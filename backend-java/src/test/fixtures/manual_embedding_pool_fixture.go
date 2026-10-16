//go:build ignore

// Actual Go BatchEmbedWithPool + generic model HTTP; barrier proves pool capacity
// and out-of-order HTTP responses must retain their original source positions.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
	utils "github.com/Tencent/WeKnora/internal/utils"
	"github.com/panjf2000/ants/v2"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

func main() {
	logger.SetOutput(os.Stderr)
	os.Setenv("SSRF_WHITELIST", "127.0.0.1")
	utils.ResetSSRFWhitelistForTest()
	os.Setenv("BATCH_EMBED_SIZE", "2")
	out := []any{}
	for _, name := range []string{"pool-one", "pool-five", "pool-five-error", "pool-five-cancel"} {
		size := 5
		if name == "pool-one" {
			size = 1
		}
		var mu sync.Mutex
		active, max, calls := 0, 0, 0
		gate := make(chan struct{})
		ctx, cancel := context.WithCancel(context.Background())
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			var b struct {
				Input []string `json:"input"`
			}
			json.NewDecoder(r.Body).Decode(&b)
			mu.Lock()
			calls++
			active++
			if active > max {
				max = active
			}
			if size == 5 && calls == 5 {
				if name == "pool-five-cancel" {
					cancel()
				}
				close(gate)
			}
			mu.Unlock()
			if name == "pool-five-cancel" {
				<-r.Context().Done()
				mu.Lock()
				active--
				mu.Unlock()
				return
			}
			if size == 5 {
				select {
				case <-gate:
				case <-r.Context().Done():
					return
				}
			}
			first, _ := strconv.Atoi(strings.TrimPrefix(b.Input[0], "manual-"))
			if name == "pool-five-error" && first == 0 {
				w.WriteHeader(500)
				fmt.Fprint(w, `{"error":{"message":"controlled manual provider failure"}}`)
			} else {
				time.Sleep(time.Duration(12-first) * time.Millisecond)
				data := []any{}
				for i := len(b.Input) - 1; i >= 0; i-- {
					n, _ := strconv.Atoi(strings.TrimPrefix(b.Input[i], "manual-"))
					data = append(data, map[string]any{"index": i, "embedding": []float32{float32(n + 100), float32(n * 3)}})
				}
				w.Header().Set("Content-Type", "application/json")
				json.NewEncoder(w).Encode(map[string]any{"data": data})
			}
			mu.Lock()
			active--
			mu.Unlock()
		}))
		pool, e := ants.NewPool(size)
		if e != nil {
			panic(e)
		}
		model, e := embedding.NewEmbedder(embedding.Config{Source: types.ModelSourceRemote, BaseURL: server.URL + "/v1", Provider: "generic", ModelName: "manual-model", ModelID: "manual-model", Dimensions: 2}, embedding.NewBatchEmbedder(pool), nil)
		if e != nil {
			panic(e)
		}
		texts := []string{}
		for n := 0; n < 12; n++ {
			texts = append(texts, fmt.Sprintf("manual-%d", n))
		}
		values, e := model.BatchEmbedWithPool(ctx, model, texts)
		bits := [][]uint32{}
		for _, v := range values {
			row := []uint32{}
			for _, f := range v {
				row = append(row, math.Float32bits(f))
			}
			bits = append(bits, row)
		}
		out = append(out, map[string]any{"name": name, "capacity": size, "max_active": max, "calls": calls, "failed": e != nil, "vectors": bits})
		cancel()
		pool.Release()
		server.Close()
	}
	json.NewEncoder(os.Stdout).Encode(out)
}
