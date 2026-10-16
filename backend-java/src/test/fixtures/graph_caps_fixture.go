//go:build ignore

// Real production Go bounded search and batched deletion, with actual Neo4j/APOC.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	graphrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/neo4j"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
	"os"
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
	d, e := neo4j.NewDriver(uri, neo4j.NoAuth())
	must(e)
	defer d.Close(ctx)
	r := graphrepo.NewNeo4jRepository(d)
	counter := r.(interfaces.KnowledgeGraphCounter)
	out := []any{}
	for _, kind := range []string{"seed-cap", "row-cap"} {
		ns := types.NameSpace{KnowledgeBase: "graph-cap-oracle-kb", Knowledge: "graph-cap-oracle-" + kind}
		s := d.NewSession(ctx, neo4j.SessionConfig{})
		reset := func() {
			q, e := s.Run(ctx, "MATCH(n) WHERE n.kg=$doc DETACH DELETE n", map[string]any{"doc": ns.Knowledge})
			must(e)
			_, e = q.Consume(ctx)
			must(e)
		}
		reset()
		g := &types.GraphData{}
		names := []string{"Seed"}
		if kind == "seed-cap" {
			for i := 0; i < 211; i++ {
				name := fmt.Sprintf("Seed%03d", i)
				if i == 0 {
					name = "Seed"
				}
				target := fmt.Sprintf("Target%03d", i)
				g.Node = append(g.Node, &types.GraphNode{Name: name, Chunks: []string{name}, Attributes: []string{}}, &types.GraphNode{Name: target, Chunks: []string{target}, Attributes: []string{}})
				g.Relation = append(g.Relation, &types.GraphRelation{Node1: name, Node2: target, Type: "links"})
			}
			g.Node = append(g.Node, &types.GraphNode{Name: "Seed-a", Chunks: []string{"isolated"}, Attributes: []string{}})
		} else {
			names = []string{"Hub"}
			g.Node = append(g.Node, &types.GraphNode{Name: "Hub", Chunks: []string{"Hub"}, Attributes: []string{}})
			for i := 0; i < 2005; i++ {
				name := fmt.Sprintf("Neighbor%04d", i)
				g.Node = append(g.Node, &types.GraphNode{Name: name, Chunks: []string{name}, Attributes: []string{}})
				g.Relation = append(g.Relation, &types.GraphRelation{Node1: "Hub", Node2: name, Type: "links"})
			}
		}
		must(r.AddGraph(ctx, ns, []*types.GraphData{g}))
		count, e := counter.CountKnowledgeGraph(ctx, ns)
		must(e)
		found, e := r.SearchNode(ctx, ns, names)
		must(e)
		must(r.DelGraph(ctx, []types.NameSpace{ns}))
		after, e := counter.CountKnowledgeGraph(ctx, ns)
		must(e)
		out = append(out, map[string]any{"name": kind, "namespace": ns, "graph": g, "names": names, "counts": count, "search": found, "after": after})
		reset()
		s.Close(ctx)
	}
	json.NewEncoder(os.Stdout).Encode(out)
}
