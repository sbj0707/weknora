package service

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/hibiken/asynq"
	"github.com/stretchr/testify/require"
	"testing"
)

type imageBindingChunks struct {
	interfaces.ChunkService
	row *types.Chunk
}

func (c *imageBindingChunks) GetChunkByIDOnly(context.Context, string) (*types.Chunk, error) {
	if c.row == nil {
		return nil, ErrChunkNotFound
	}
	return c.row, nil
}
func TestImageTaskSourceBindingAndProviderFence(t *testing.T) {
	for _, name := range []string{"document-owner", "document-base", "base-owner", "parent-owner", "parent-document", "parent-base", "parent-missing", "metadata", "title", "filename", "cancelled", "deleting", "parent-content", "parent-header", "context"} {
		t.Run(name, func(t *testing.T) {
			row := &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb", Title: "title", FileName: "manual.md", Type: "manual", Metadata: types.JSON(`{"content":"source"}`)}
			base := &types.KnowledgeBase{ID: "kb", TenantID: 1}
			parent := &types.Chunk{ID: "parent", TenantID: 1, KnowledgeID: "doc", KnowledgeBaseID: "kb", Content: "parent", ContextHeader: "header"}
			chunks := &imageBindingChunks{row: parent}
			s := &ImageMultimodalService{knowledgeRepo: &orphanKnowledgeRepo{knowledge: row}, kbService: &orphanKBService{kb: base}, chunkService: chunks}
			payload := types.ImageMultimodalPayload{TenantID: 1, KnowledgeID: "doc", KnowledgeBaseID: "kb", ChunkID: "parent"}
			ctx, err := s.bindImageSource(context.Background(), &payload)
			if err != nil {
				t.Fatal(err)
			}
			switch name {
			case "document-owner":
				row.TenantID = 2
			case "document-base":
				row.KnowledgeBaseID = "other"
			case "base-owner":
				base.TenantID = 2
			case "parent-owner":
				parent.TenantID = 2
			case "parent-document":
				parent.KnowledgeID = "other"
			case "parent-base":
				parent.KnowledgeBaseID = "other"
			case "parent-missing":
				chunks.row = nil
			case "metadata":
				row.Metadata = types.JSON(`{"content":"edited"}`)
			case "title":
				row.Title = "edit"
			case "filename":
				row.FileName = "edited.md"
			case "cancelled":
				row.ParseStatus = "cancelled"
			case "deleting":
				row.ParseStatus = "deleting"
			case "parent-content":
				parent.Content = "new"
			case "parent-header":
				parent.ContextHeader = "new"
			case "context":
				var cancel context.CancelFunc
				ctx, cancel = context.WithCancel(ctx)
				cancel()
			}
			e := checkImageSource(ctx)
			if name == "context" {
				if !errors.Is(e, context.Canceled) {
					t.Fatalf("got %v", e)
				}
				return
			}
			if !errors.Is(e, errImageSourceChanged) {
				t.Fatalf("must reject changed source, got %v", e)
			}
			if name == "document-owner" || name == "document-base" || name == "base-owner" || name == "parent-owner" || name == "parent-document" || name == "parent-base" || name == "parent-missing" {
				_, e = s.bindImageSource(context.Background(), &payload)
				if !errors.Is(e, asynq.SkipRetry) {
					t.Fatalf("initial invalid queue binding must SkipRetry, got %v", e)
				}
			}
		})
	}
}

// A final delivery with a forged owner/KB must not consume another document's image slot.
func TestImageTaskFinalBindingRejectionPreservesForeignCounter(t *testing.T) {
	for _, name := range []string{"document-owner", "document-base", "base-owner"} {
		t.Run(name, func(t *testing.T) {
			mr, rdb := newSlotReleaseRedis(t)
			row := &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb", ParseStatus: types.ParseStatusProcessing}
			base := &types.KnowledgeBase{ID: "kb", TenantID: 1}
			switch name {
			case "document-owner":
				row.TenantID = 2
			case "document-base":
				row.KnowledgeBaseID = "other"
			case "base-owner":
				base.TenantID = 2
			}
			queue := &orphanTaskEnqueuer{}
			service := &ImageMultimodalService{knowledgeRepo: &orphanKnowledgeRepo{knowledge: row}, kbService: &orphanKBService{kb: base}, redisClient: rdb, taskEnqueuer: queue}
			require.NoError(t, rdb.Set(context.Background(), multimodalPendingKey("doc"), 1, 0).Err())
			payload, e := json.Marshal(types.ImageMultimodalPayload{TenantID: 1, KnowledgeID: "doc", KnowledgeBaseID: "kb"})
			require.NoError(t, e)
			e = service.Handle(types.WithTaskRetryMetadata(context.Background(), 3, 3), asynq.NewTask(types.TypeImageMultimodal, payload))
			require.ErrorIs(t, e, asynq.SkipRetry)
			require.Equal(t, "1", mustGet(t, mr, multimodalPendingKey("doc")))
			require.Empty(t, queue.enqueued)
		})
	}
}
