package container

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/stretchr/testify/require"
	"net/http"
	"net/http/httptest"
	"os"
	"sync/atomic"
	"testing"
	"time"
)

// Actual factory + SDK HTTP: a constructor must not discard the registry's build context.
func TestVectorStoreES8FactoryContextActualOracle(t *testing.T) {
	t.Setenv("SSRF_WHITELIST", "127.0.0.1")
	utils.ResetSSRFWhitelistForTest()
	defer utils.ResetSSRFWhitelistForTest()
	out := []any{}
	for _, name := range []string{"deadline", "cancel"} {
		t.Run(name, func(t *testing.T) {
			var requests atomic.Int32
			release := make(chan struct{})
			entered := make(chan struct{}, 1)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				if r.Method == "HEAD" {
					entered <- struct{}{}
					select {
					case <-release:
					case <-r.Context().Done():
						return
					}
					w.WriteHeader(404)
					return
				}
				w.Header().Set("X-Elastic-Product", "Elasticsearch")
				w.Header().Set("Content-Type", "application/json")
				w.Write([]byte(`{}`))
			}))
			defer server.Close()
			ctx, cancel := context.WithCancel(context.Background())
			if name == "deadline" {
				ctx, cancel = context.WithTimeout(context.Background(), 30*time.Millisecond)
			}
			defer cancel()
			result := make(chan struct {
				present bool
				err     error
			}, 1)
			go func() {
				engine, err := createEngineServiceFromStore(ctx, types.VectorStore{ID: "context-store", TenantID: 1, EngineType: types.ElasticsearchRetrieverEngineType, ConnectionConfig: types.ConnectionConfig{Addr: server.URL}, IndexConfig: types.IndexConfig{IndexName: "fixture_context"}}, nil, &config.Config{}, nil)
				result <- struct {
					present bool
					err     error
				}{engine != nil, err}
			}()
			<-entered
			if name == "cancel" {
				cancel()
			}
			time.AfterFunc(120*time.Millisecond, func() { close(release) })
			got := <-result
			row := map[string]any{"name": name, "returned_engine": got.present, "error_is_context": errors.Is(got.err, context.Canceled) || errors.Is(got.err, context.DeadlineExceeded), "request_context_done": ctx.Err() != nil, "http_requests": requests.Load()}
			out = append(out, row)
			if os.Getenv("WEKNORA_ES_CONTEXT_ALLOW_UNSAFE") != "1" {
				require.False(t, got.present)
				require.True(t, row["error_is_context"].(bool))
				require.EqualValues(t, 1, requests.Load())
			}
		})
	}
	if path := os.Getenv("WEKNORA_ES_CONTEXT_ORACLE"); path != "" {
		data, e := json.Marshal(out)
		require.NoError(t, e)
		require.NoError(t, os.WriteFile(path, append(data, '\n'), 0600))
	}
}
