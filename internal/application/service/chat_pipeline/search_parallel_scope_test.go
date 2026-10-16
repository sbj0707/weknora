package chatpipeline

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

type parallelScopeBases struct {
	interfaces.KnowledgeBaseRepository
}
type parallelScopeShares struct{ interfaces.KBShareService }

func TestParallelProductionConstructorBindsInnerEntityScope(t *testing.T) {
	manager := NewEventManager()
	bases, shares := &parallelScopeBases{}, &parallelScopeShares{}
	plugin := NewPluginSearchParallelAuthorized(manager, nil, nil, nil, nil, nil,
		nil, nil, nil, nil, nil, nil, nil, bases, shares)
	if plugin.searchEntityPlugin.bases != bases || plugin.searchEntityPlugin.shares != shares {
		t.Fatal("parallel entity stage must receive production ownership and fresh-share resolvers")
	}
	// Registration remains on the parallel event; no standalone ENTITY_SEARCH registration.
	if len(manager.listeners[types.CHUNK_SEARCH_PARALLEL]) != 1 || len(manager.listeners[types.ENTITY_SEARCH]) != 0 {
		t.Fatal("authorized wrapper changed event registration")
	}
	cm := &types.ChatManage{PipelineState: types.PipelineState{Intent: types.IntentGreeting}}
	if err := manager.Trigger(context.Background(), types.CHUNK_SEARCH_PARALLEL, cm); err != nil {
		t.Fatalf("non-retrieval event failed: %v", err)
	}
}
