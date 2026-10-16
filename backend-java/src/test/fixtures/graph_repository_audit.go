//go:build ignore

// Real production Go repository audit. Uses only a random namespace in an explicitly isolated server.
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
	"strings"
	"time"
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
	driver, e := neo4j.NewDriver(uri, neo4j.NoAuth())
	must(e)
	defer driver.Close(ctx)
	repo := graphrepo.NewNeo4jRepository(driver)
	name := fmt.Sprintf("go-graph-audit-%d", time.Now().UnixNano())
	ns := types.NameSpace{KnowledgeBase: name + "-kb", Knowledge: name + "-doc"}
	session := driver.NewSession(ctx, neo4j.SessionConfig{})
	defer session.Close(ctx)
	foreign := types.NameSpace{KnowledgeBase: ns.KnowledgeBase, Knowledge: strings.ReplaceAll(ns.Knowledge, "-", "_")}
	defer func() {
		r, e := session.Run(ctx, "MATCH(n) WHERE n.kg IN $docs DETACH DELETE n", map[string]any{"docs": []string{ns.Knowledge, foreign.Knowledge}})
		must(e)
		_, e = r.Consume(ctx)
		must(e)
	}()
	must(repo.AddGraph(ctx, ns, []*types.GraphData{{Node: []*types.GraphNode{{Name: "Alice", Attributes: []string{"Owner"}, Chunks: []string{"c1"}}, {Name: "Release", Attributes: []string{}, Chunks: []string{"c1"}}}, Relation: []*types.GraphRelation{{Node1: "Alice", Node2: "Release", Type: "owns"}}}}))
	count := repo.(interfaces.KnowledgeGraphCounter)
	before, e := count.CountKnowledgeGraph(ctx, ns)
	must(e)
	search, e := repo.SearchNode(ctx, ns, []string{"Alice"})
	must(e)
	must(repo.AddGraph(ctx, foreign, []*types.GraphData{{Node: []*types.GraphNode{{Name: "Alice", Attributes: []string{"Foreign"}, Chunks: []string{"private-chunk"}}, {Name: "Private", Attributes: []string{}, Chunks: []string{"private-chunk"}}}, Relation: []*types.GraphRelation{{Node1: "Alice", Node2: "Private", Type: "private"}}}}))
	collision, e := repo.SearchNode(ctx, ns, []string{"Alice"})
	must(e)
	kbwide, e := repo.SearchNode(ctx, types.NameSpace{KnowledgeBase: ns.KnowledgeBase}, []string{"Alice"})
	must(e)
	must(repo.DelGraph(ctx, []types.NameSpace{ns}))
	after, e := count.CountKnowledgeGraph(ctx, ns)
	must(e)
	foreignAfter, e := count.CountKnowledgeGraph(ctx, foreign)
	must(e)
	empty, e := repo.SearchNode(ctx, ns, nil)
	must(e)
	wrong, e := repo.SearchNode(ctx, types.NameSpace{KnowledgeBase: ns.KnowledgeBase + "-wrong", Knowledge: ns.Knowledge}, []string{"Alice"})
	must(e)
	must(repo.AddGraph(ctx, ns, []*types.GraphData{{Node: []*types.GraphNode{{Name: "Alice", Attributes: []string{}, Chunks: []string{"c1"}}, {Name: "Release", Attributes: []string{}, Chunks: []string{"c1"}}}, Relation: []*types.GraphRelation{{Node1: "Alice", Node2: "Release", Type: "owns"}}}}))
	cross, e := session.Run(ctx, "MATCH(a {kg:$own,name:\"Alice\"}),(b {kg:$peer,name:\"Private\"}) MERGE(a)-[:external]->(b)", map[string]any{"own": ns.Knowledge, "peer": foreign.Knowledge})
	must(e)
	_, e = cross.Consume(ctx)
	must(e)
	crossDeleteErr := repo.DelGraph(ctx, []types.NameSpace{ns})
	crossAfter, e := count.CountKnowledgeGraph(ctx, ns)
	must(e)
	peerCrossAfter, e := count.CountKnowledgeGraph(ctx, foreign)
	must(e)
	json.NewEncoder(os.Stdout).Encode(map[string]any{"namespace": ns, "before": before, "search": search, "collision_search": collision, "kbwide_search": kbwide, "foreign_namespace": foreign, "foreign_after": foreignAfter, "cross_delete_error": crossDeleteErr != nil, "cross_after": crossAfter, "peer_cross_after": peerCrossAfter, "empty_search": empty, "wrong_namespace_search": wrong, "after": after})
}
