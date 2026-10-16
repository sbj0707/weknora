package retriever

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/stretchr/testify/require"
	"os"
	"sync/atomic"
	"testing"
	"time"
)

// Exports real factory/registry decisions; instrumented construction is intentionally not physical-engine proof.
func TestVectorStoreLifecycleActualOracle(t *testing.T) {
	output := []any{}
	for _, name := range []string{"cold", "cache", "foreign", "missing", "read-error", "factory-error", "factory-nil", "factory-panic", "cooldown-expired", "unregistered", "register-wins", "unregister-during-build", "leader-cancel"} {
		t.Run(name, func(t *testing.T) {
			var calls atomic.Int32
			store := &fakeStoreRepo{store: &types.VectorStore{ID: "store", TenantID: 1}}
			if name == "missing" {
				store.store = nil
			}
			if name == "read-error" {
				store.err = errors.New("SQL unavailable")
			}
			started := make(chan struct{}, 4)
			release := make(chan struct{})
			block := name == "register-wins" || name == "unregister-during-build" || name == "leader-cancel"
			factory := func(ctx context.Context, _ types.VectorStore) (interfaces.RetrieveEngineService, error) {
				n := calls.Add(1)
				if block {
					started <- struct{}{}
					select {
					case <-release:
					case <-ctx.Done():
						return nil, ctx.Err()
					}
				}
				if name == "factory-error" || name == "cooldown-expired" && n == 1 {
					return nil, errors.New("endpoint/secret must not escape")
				}
				if name == "factory-nil" {
					return nil, nil
				}
				if name == "factory-panic" {
					panic("third party")
				}
				return &fakeEngine{engineType: types.ElasticsearchRetrieverEngineType, support: []types.RetrieverType{types.VectorRetrieverType, types.KeywordsRetrieverType}}, nil
			}
			registry := NewRetrieveEngineRegistry(store, factory).(*RetrieveEngineRegistry)
			ownership := &fakeOwnership{owned: map[string]uint64{"store": 1}}
			if name == "foreign" {
				ownership.owned["store"] = 2
			}
			outcomes := []string{}
			record := func(err error) {
				if err != nil {
					outcomes = append(outcomes, err.Error())
				} else {
					outcomes = append(outcomes, "ok")
				}
			}
			resolve := func(ctx context.Context) error {
				_, err := CreateRetrieveEngineForKB(ctx, registry, ownership, 1, func() *string { x := "store"; return &x }())
				return err
			}
			if block {
				ctx, cancel := context.WithCancel(context.Background())
				done := make(chan error, 1)
				go func() { done <- resolve(ctx) }()
				<-started
				if name == "register-wins" {
					registry.RegisterWithStoreID("store", &fakeEngine{})
				}
				if name == "unregister-during-build" {
					registry.UnregisterByStoreID("store")
				}
				if name == "leader-cancel" {
					cancel()
					record(<-done)
					close(release)
					deadline := time.Now().Add(time.Second)
					for {
						if _, e := registry.GetByStoreID("store"); e == nil {
							break
						}
						if time.Now().After(deadline) {
							t.Fatal("detached build not published")
						}
						time.Sleep(time.Millisecond)
					}
					record(resolve(context.Background()))
				} else {
					close(release)
					record(<-done)
					record(resolve(context.Background()))
				}
				cancel()
			} else {
				record(resolve(context.Background()))
				if name == "cache" || name == "factory-error" || name == "factory-nil" || name == "factory-panic" {
					record(resolve(context.Background()))
				}
				if name == "cooldown-expired" {
					registry.failedUntil["store"] = time.Now().Add(-time.Second)
					record(resolve(context.Background()))
				}
				if name == "unregistered" {
					registry.UnregisterByStoreID("store")
					record(resolve(context.Background()))
				}
			}
			_, published := registry.GetByStoreID("store")
			output = append(output, map[string]any{"name": name, "outcomes": outcomes, "builds": calls.Load(), "published": published == nil})
		})
	}
	if path := os.Getenv("WEKNORA_VECTOR_REGISTRY_ORACLE"); path != "" {
		data, e := json.Marshal(output)
		require.NoError(t, e)
		require.NoError(t, os.WriteFile(path, append(data, '\n'), 0600))
	}
}
