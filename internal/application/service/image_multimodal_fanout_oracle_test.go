package service

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/Tencent/WeKnora/internal/infrastructure/docparser"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/alicebob/miniredis/v2"
	"github.com/hibiken/asynq"
	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/require"
	"os"
	"testing"
	"time"
)

type imageFanoutActualQueue struct {
	client   *asynq.Client
	tasks    []any
	failAt   map[int]bool
	seen     int
	postFail bool
}

func (q *imageFanoutActualQueue) Enqueue(task *asynq.Task, options ...asynq.Option) (*asynq.TaskInfo, error) {
	if task.Type() == types.TypeImageMultimodal {
		n := q.seen
		q.seen++
		if q.failAt[n] {
			return nil, errors.New("controlled image queue unavailable")
		}
	} else if q.postFail {
		return nil, errors.New("controlled post queue unavailable")
	}
	info, err := q.client.Enqueue(task, options...)
	if err != nil {
		return nil, err
	}
	var payload any
	if err = json.Unmarshal(task.Payload(), &payload); err != nil {
		return nil, err
	}
	q.tasks = append(q.tasks, map[string]any{"type": task.Type(), "payload": payload, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": info.Timeout / time.Millisecond})
	return info, nil
}

// Actual production fan-out and real Asynq/Redis options, including its partial-admission behavior.
func TestImageMultimodalActualFanoutOracle(t *testing.T) {
	cases := []any{}
	for _, name := range []string{"one", "two", "ambiguous-parent", "unmatched-parent", "attrs-language", "partial-first", "partial-last", "none-accepted", "post-unavailable"} {
		redis, err := miniredis.Run()
		require.NoError(t, err)
		client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
		rdb := redisclient(redis.Addr())
		q := &imageFanoutActualQueue{client: client, tasks: []any{}, failAt: map[int]bool{}, postFail: name == "post-unavailable"}
		knowledge := &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb", ParseStatus: "processing"}
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, VLMConfig: types.VLMConfig{Enabled: true, ModelID: "vlm"}}
		images := []docparser.StoredImage{{OriginalRef: "one", ServingURL: "resource://aaaaaaaaaaaaaaaaaaaaaa"}}
		chunks := []types.ParsedChunk{{ChunkID: "parent", Content: "![one](resource://aaaaaaaaaaaaaaaaaaaaaa)"}}
		if name == "two" || name == "partial-first" || name == "partial-last" || name == "none-accepted" || name == "post-unavailable" {
			images = append(images, docparser.StoredImage{OriginalRef: "two", ServingURL: "resource://bbbbbbbbbbbbbbbbbbbbbb"})
			chunks[0].Content += "\n![two](resource://bbbbbbbbbbbbbbbbbbbbbb)"
		}
		if name == "ambiguous-parent" {
			chunks = append(chunks, types.ParsedChunk{ChunkID: "duplicate", Content: chunks[0].Content})
		}
		if name == "unmatched-parent" {
			chunks[0].Content = "resource://aaaaaaaaaaaaaaaaaaaaaa is prose"
		}
		if name == "attrs-language" {
			kb.ImageProcessingConfig.ImageAttrsEnabled = true
		}
		if name == "partial-first" || name == "none-accepted" || name == "post-unavailable" {
			q.failAt[0] = true
		}
		if name == "partial-last" || name == "none-accepted" || name == "post-unavailable" {
			q.failAt[1] = true
		}
		ctx := withAttempt(context.WithValue(context.Background(), types.LanguageContextKey, "fr-FR"), 2)
		service := &knowledgeService{task: q, redisClient: rdb}
		err = service.enqueueImageMultimodalTasks(ctx, knowledge, kb, images, chunks, nil)
		message := ""
		if err != nil {
			message = err.Error()
		}
		pending, _ := rdb.Get(context.Background(), multimodalPendingKey("doc")).Int64()
		cases = append(cases, map[string]any{"name": name, "tasks": q.tasks, "error": message, "pending": pending, "kb": kb, "images": images, "chunks": chunks})
		require.NoError(t, rdb.Close())
		require.NoError(t, client.Close())
		redis.Close()
	}
	if path := os.Getenv("WEKNORA_IMAGE_FANOUT_ORACLE"); path != "" {
		bytes, err := json.Marshal(cases)
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(path, append(bytes, '\n'), 0600))
	}
	require.Len(t, cases, 9)
}
func redisclient(address string) *redis.Client { return redis.NewClient(&redis.Options{Addr: address}) }
