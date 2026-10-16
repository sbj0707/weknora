package retriever

import "context"

type manualBatchOrderKey struct{}

// WithManualBatchOrder makes provider batches independent of Go map iteration.
// Other document/chunk callers retain their existing behavior.
func WithManualBatchOrder(ctx context.Context) context.Context {
	return context.WithValue(ctx, manualBatchOrderKey{}, true)
}
