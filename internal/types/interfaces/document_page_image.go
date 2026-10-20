package interfaces

import (
	"context"
	"io"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/hibiken/asynq"
)

type DocumentPageImageService interface {
	State(context.Context, string, string) (*types.PageImageState, error)
	Request(context.Context, string, types.PageImageRequest) (*types.PageImageState, error)
	Open(context.Context, string, string) (io.ReadCloser, string, error)
	Handle(context.Context, *asynq.Task) error
	AutoSave(context.Context, *types.Knowledge, *types.KnowledgeBase) error
	Collect(context.Context, []*types.SearchResult, types.SearchTargets, *types.PageImageBudget) types.PageImageEvidence
	Delete(context.Context, uint64, []string) error
	Copy(context.Context, *types.Knowledge, *types.Knowledge, map[string]string) error
	Move(context.Context, *types.Knowledge, *types.KnowledgeBase) error
}
