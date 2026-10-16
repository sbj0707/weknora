//go:build ignore

// Production composite scoring; explicit FMA makes rounding architecture independent.
package main

import (
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/reranking"
	"github.com/Tencent/WeKnora/internal/types"
	"math"
	"os"
	"runtime"
)

func main() {
	rows := []map[string]any{}
	for _, m := range []float64{.74, .88, .63, .15, 0, 1, -.25, 2} {
		for _, b := range []float64{.9999999999999998, .9838709677419354, .6777777777777777, .5, .1, 0, 1, -.1, 2} {
			for _, web := range []bool{false, true} {
				for _, graph := range []bool{false, true} {
					r := &types.SearchResult{}
					if web {
						r.KnowledgeSource = "web_search"
					}
					if graph {
						r.MatchType = types.MatchTypeGraph
					}
					v := reranking.CompositeScore(r, m, b)
					rows = append(rows, map[string]any{"model": m, "base": b, "web": web, "graph": graph, "score": v, "bits": math.Float64bits(v)})
				}
			}
		}
	}
	f, e := os.Create(os.Args[1])
	if e != nil {
		panic(e)
	}
	defer f.Close()
	if e = json.NewEncoder(f).Encode(map[string]any{"goarch": runtime.GOARCH, "goos": runtime.GOOS, "version": runtime.Version(), "cases": rows}); e != nil {
		panic(e)
	}
}
