package neo4j

import (
	"context"
	"fmt"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/neo4j/neo4j-go-driver/v6/neo4j"
)

func (n *Neo4jRepository) CountKnowledgeGraph(
	ctx context.Context, namespace types.NameSpace,
) (*types.KnowledgeGraphCounts, error) {
	if n.driver == nil {
		return nil, interfaces.ErrIndexStatisticsUnsupported
	}
	session := n.driver.NewSession(ctx, neo4j.SessionConfig{AccessMode: neo4j.AccessModeRead})
	defer session.Close(ctx)
	// Labels and document ownership are parameters; no user-controlled Cypher.
	result, err := session.ExecuteRead(ctx, func(tx neo4j.ManagedTransaction) (any, error) {
		result, err := tx.Run(ctx, `
			MATCH (n {kg: $knowledge_id})
			WHERE all(label IN $labels WHERE label IN labels(n))
			OPTIONAL MATCH (n)-[r]->(m {kg: $knowledge_id})
			WHERE all(label IN $labels WHERE label IN labels(m))
			RETURN count(DISTINCT n) AS entities, count(DISTINCT r) AS relations`, map[string]any{
			"knowledge_id": namespace.Knowledge, "labels": n.Labels(namespace),
		})
		if err != nil {
			return nil, err
		}
		record, err := result.Single(ctx)
		if err != nil {
			return nil, err
		}
		entities, entitiesOK := record.Get("entities")
		relations, relationsOK := record.Get("relations")
		entityCount, entityTypeOK := entities.(int64)
		relationCount, relationTypeOK := relations.(int64)
		if !entitiesOK || !relationsOK || !entityTypeOK || !relationTypeOK {
			return nil, fmt.Errorf("invalid graph count result")
		}
		return &types.KnowledgeGraphCounts{Entities: entityCount, Relations: relationCount}, nil
	})
	if err != nil {
		return nil, err
	}
	return result.(*types.KnowledgeGraphCounts), nil
}
