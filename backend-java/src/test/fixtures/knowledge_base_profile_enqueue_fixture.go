//go:build ignore

// Actual Go producer and Asynq enqueue against an isolated Redis-compatible backend.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/alicebob/miniredis/v2"
	"github.com/hibiken/asynq"
	"os"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}

type capture struct {
	client *asynq.Client
	rows   []any
	fail   bool
}

func (c *capture) Enqueue(t *asynq.Task, opts ...asynq.Option) (*asynq.TaskInfo, error) {
	if c.fail {
		return nil, fmt.Errorf("synthetic enqueue failure")
	}
	info, e := c.client.Enqueue(t, opts...)
	if e != nil {
		return nil, e
	}
	var payload any
	must(json.Unmarshal(t.Payload(), &payload))
	c.rows = append(c.rows, map[string]any{"type": t.Type(), "payload": payload, "queue": info.Queue, "max_retry": info.MaxRetry, "timeout": int64(info.Timeout / time.Millisecond), "scheduled": info.State == asynq.TaskStateScheduled, "debounced_id": len(info.ID) > 11 && info.ID[:11] == "kb-profile:"})
	return info, nil
}
func main() {
	logger.SetOutput(os.Stderr)
	out := []any{}
	for _, name := range []string{"automatic", "duplicate", "force", "force-twice", "disabled", "nil-config", "force-disabled", "faq", "temporary", "empty-id", "failure", "chinese"} {
		redis, e := miniredis.Run()
		must(e)
		client := asynq.NewClient(asynq.RedisClientOpt{Addr: redis.Addr()})
		c := &capture{client: client, rows: []any{}, fail: name == "failure"}
		kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Name: "KB", Type: "document", ProfileConfig: &types.KnowledgeBaseProfileConfig{Enabled: name != "disabled" && name != "force-disabled"}}
		if name == "nil-config" {
			kb.ProfileConfig = nil
		}
		if name == "faq" {
			kb.Type = "faq"
		}
		if name == "temporary" {
			kb.IsTemporary = true
		}
		if name == "empty-id" {
			kb.ID = ""
		}
		language := "en-US"
		if name == "chinese" {
			language = "zh-CN"
		}
		ctx := context.WithValue(context.Background(), types.LanguageContextKey, language)
		force := name == "force" || name == "force-twice" || name == "force-disabled"
		handler := svc.NewKnowledgeBaseProfileService(nil, nil, nil, nil, c)
		err := handler.RequestKnowledgeBaseProfileRefresh(ctx, kb, force)
		if name == "duplicate" || name == "force-twice" {
			err = handler.RequestKnowledgeBaseProfileRefresh(ctx, kb, force)
		}
		out = append(out, map[string]any{"name": name, "base": kb, "force": force, "language": language, "error": err != nil, "tasks": c.rows})
		client.Close()
		redis.Close()
	}
	b, e := json.Marshal(out)
	must(e)
	if len(os.Args) > 1 {
		must(os.WriteFile(os.Args[1], append(b, 10), 0600))
	} else {
		fmt.Println(string(b))
	}
}
