package neo4j

import (
	"context"
	"fmt"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

func (n *Neo4jRepository) GetChunkGraph(ctx context.Context, namespace types.NameSpace, chunkID string) (*types.ChunkGraphDetail, error) {
	if n.driver == nil {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	session := n.driver.NewSession(ctx, neo4j.SessionConfig{AccessMode: neo4j.AccessModeRead})
	defer session.Close(ctx)
	result, err := session.ExecuteRead(ctx, func(tx neo4j.ManagedTransaction) (any, error) {
		result, err := tx.Run(ctx, `MATCH (n {kg: $knowledge_id})
   WHERE all(label IN $labels WHERE label IN labels(n)) AND $chunk_id IN coalesce(n.chunks, [])
   OPTIONAL MATCH (n)-[r]-(m {kg: $knowledge_id})
   WHERE all(label IN $labels WHERE label IN labels(m))
   RETURN n, r, startNode(r).name AS source, endNode(r).name AS target
   ORDER BY n.name, type(r)`, map[string]any{"knowledge_id": namespace.Knowledge, "labels": n.Labels(namespace), "chunk_id": chunkID})
		if err != nil {
			return nil, err
		}
		graph := &types.ChunkGraphDetail{Nodes: []types.ChunkGraphNode{}, Relations: []types.ChunkGraphRelation{}}
		nodes, relations := map[string]bool{}, map[string]bool{}
		for result.Next(ctx) {
			record := result.Record()
			value, _ := record.Get("n")
			node, ok := value.(neo4j.Node)
			if !ok {
				return nil, fmt.Errorf("invalid graph node")
			}
			if !nodes[node.ElementId] {
				name, _ := node.Props["name"].(string)
				var chunkIDs []string
				if chunks, ok := node.Props["chunks"].([]any); ok {
					for _, chunk := range chunks {
						if id, ok := chunk.(string); ok {
							chunkIDs = append(chunkIDs, id)
						}
					}
				}
				graph.Nodes = append(graph.Nodes, types.ChunkGraphNode{ID: node.ElementId, Name: name, Labels: node.Labels, Attributes: node.Props, ChunkIDs: chunkIDs})
				nodes[node.ElementId] = true
			}
			value, _ = record.Get("r")
			if relation, ok := value.(neo4j.Relationship); ok && !relations[relation.ElementId] {
				source, _ := record.Get("source")
				target, _ := record.Get("target")
				sourceName, _ := source.(string)
				targetName, _ := target.(string)
				graph.Relations = append(graph.Relations, types.ChunkGraphRelation{ID: relation.ElementId, Source: sourceName, Target: targetName, Type: relation.Type, Attributes: relation.Props})
				relations[relation.ElementId] = true
			}
		}
		if err := result.Err(); err != nil {
			return nil, err
		}
		return graph, nil
	})
	if err != nil {
		return nil, err
	}
	return result.(*types.ChunkGraphDetail), nil
}
