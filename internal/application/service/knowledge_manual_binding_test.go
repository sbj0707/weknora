package service

import (
	"context"
	"errors"
	"testing"

	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/require"
)

type manualGuardKB struct {
	interfaces.KnowledgeBaseService
	base *types.KnowledgeBase
}

func (s *manualGuardKB) GetKnowledgeBaseByID(context.Context, string) (*types.KnowledgeBase, error) {
	return s.base, nil
}

type manualGuardEmbedder struct {
	embedding.Embedder
	calls int
	after func()
}

func (s *manualGuardEmbedder) BatchEmbedWithPool(context.Context, embedding.Embedder, []string) ([][]float32, error) {
	s.calls++
	if s.after != nil {
		s.after()
	}
	return [][]float32{{7, 11}}, nil
}

func TestManualSourceFenceRejectsMutatedBodyOwnerAndCancelAfterProvider(t *testing.T) {
	for _, name := range []string{"metadata", "owner", "base", "type", "title", "filename", "cancelled", "deleting", "base-owner"} {
		t.Run(name, func(t *testing.T) {
			row := &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb", Type: "manual", Title: "Title", FileName: "Title.md", Metadata: types.JSON(`{"content":"body","version":1}`)}
			repository := &abortCheckRepo{knowledge: row}
			base := &manualGuardKB{base: &types.KnowledgeBase{ID: "kb", TenantID: 1}}
			svc := &knowledgeService{repo: repository, kbService: base}
			ctx := svc.withManualSourceGuard(context.Background(), row)
			inner := &manualGuardEmbedder{after: func() {
				switch name {
				case "metadata":
					row.Metadata = types.JSON(`{"content":"new","version":2}`)
				case "owner":
					row.TenantID = 2
				case "base":
					row.KnowledgeBaseID = "other"
				case "type":
					row.Type = "file"
				case "title":
					row.Title = "new"
				case "filename":
					row.FileName = "new.md"
				case "cancelled", "deleting":
					row.ParseStatus = name
				case "base-owner":
					base.base.TenantID = 2
				}
			}}
			fenced := manualFencedEmbedder{inner}
			_, err := fenced.BatchEmbedWithPool(ctx, inner, []string{"body"})
			require.ErrorIs(t, err, errManualSourceChanged)
			require.Equal(t, 1, inner.calls)
			_, err = fenced.BatchEmbedWithPool(ctx, inner, []string{"body"})
			require.ErrorIs(t, err, errManualSourceChanged)
			require.Equal(t, 1, inner.calls, "stale backoff retries must not make another HTTP call")
		})
	}
}
func TestManualSourceFenceKeepsReadFailureRetryableAndAllowsUnchangedBody(t *testing.T) {
	row := &types.Knowledge{ID: "doc", TenantID: 1, KnowledgeBaseID: "kb", Type: "manual", Metadata: types.JSON(`{"content":"body","version":1}`)}
	repository := &abortCheckRepo{knowledge: row}
	svc := &knowledgeService{repo: repository, kbService: &manualGuardKB{base: &types.KnowledgeBase{ID: "kb", TenantID: 1}}}
	ctx := svc.withManualSourceGuard(context.Background(), row)
	inner := &manualGuardEmbedder{}
	result, err := (manualFencedEmbedder{inner}).BatchEmbedWithPool(ctx, inner, []string{"body"})
	require.NoError(t, err)
	require.Equal(t, [][]float32{{7, 11}}, result)
	repository.err = errors.New("controlled SQL read unavailable")
	err = checkManualSource(ctx)
	require.ErrorIs(t, err, repository.err)
	require.NotErrorIs(t, err, errManualSourceChanged)
	parent, cancel := context.WithCancel(ctx)
	cancel()
	require.ErrorIs(t, checkManualSource(parent), context.Canceled)
	// No generic document/chunk task opts into the manual guard.
	require.NoError(t, checkManualSource(context.Background()))
}
