//go:build ignore

// Actual Go SearchNode boundary capture over owned namespaces in real Neo4j/APOC.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"os"

	graphrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/neo4j"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}

func main() {
	logger.SetOutput(os.Stderr)
	uri := os.Getenv("GRAPH_ORACLE_URI")
	if uri == "" {
		panic("isolated GRAPH_ORACLE_URI required")
	}
	ctx := context.Background()
	d, err := neo4j.NewDriver(uri, neo4j.NoAuth())
	must(err)
	defer d.Close(ctx)
	r := graphrepo.NewNeo4jRepository(d)
	s := d.NewSession(ctx, neo4j.SessionConfig{})
	defer s.Close(ctx)
	cases := []any{}
	for _, kind := range []string{"relation-only", "nil-properties", "float-attributes", "integer-attributes", "boolean-attributes"} {
		ns := types.NameSpace{KnowledgeBase: "graph-property-oracle-kb", Knowledge: "graph-property-oracle-" + kind}
		reset := func() {
			v, e := s.Run(ctx, "MATCH(n) WHERE n.kg=$doc DETACH DELETE n", map[string]any{"doc": ns.Knowledge})
			must(e)
			_, e = v.Consume(ctx)
			must(e)
		}
		reset()
		g := &types.GraphData{Relation: []*types.GraphRelation{{Node1: "Alice", Node2: "Release", Type: "owns"}}}
		if kind != "relation-only" {
			g.Node = []*types.GraphNode{{Name: "Alice"}, {Name: "Release"}}
		}
		if kind != "nil-properties" && kind != "relation-only" {
			for _, n := range g.Node {
				n.Attributes = []string{}
				n.Chunks = []string{"chunk"}
			}
		}
		must(r.AddGraph(ctx, ns, []*types.GraphData{g}))
		var property any
		floatBits := []string{}
		switch kind {
		case "float-attributes":
			values := []float64{1e6, math.Copysign(0, -1), 1e-8, 1.0000000000000002, math.SmallestNonzeroFloat64, math.MaxFloat64}
			property = values
			for _, value := range values {
				floatBits = append(floatBits, fmt.Sprintf("%016x", math.Float64bits(value)))
			}
		case "integer-attributes":
			property = []int64{1000000, 9223372036854775807, -9223372036854775808}
		case "boolean-attributes":
			property = []bool{true, false}
		}
		if property != nil {
			v, e := s.Run(ctx, "MATCH(n {kg:$doc}) SET n.attributes=$values", map[string]any{"doc": ns.Knowledge, "values": property})
			must(e)
			_, e = v.Consume(ctx)
			must(e)
		}
		var result *types.GraphData
		failure := ""
		func() {
			defer func() {
				if p := recover(); p != nil {
					failure = fmt.Sprint(p)
				}
			}()
			var e error
			result, e = r.SearchNode(ctx, ns, []string{"Alice"})
			if e != nil {
				failure = e.Error()
			}
		}()
		cases = append(cases, map[string]any{"name": kind, "namespace": ns, "graph": g, "attributes": property, "float_bits": floatBits, "search": result, "failure": failure})
		reset()
	}
	must(json.NewEncoder(os.Stdout).Encode(cases))
}
