package azureaisearch

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"slices"
	"strings"
	"time"
)

const filterBatchSize = 100

func (r *Repository) deleteBy(ctx context.Context, field string, ids []string, dimension int, kind string) error {
	if len(ids) == 0 {
		return nil
	}
	if err := r.checkDimension(dimension); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 90*time.Second)
	defer cancel()
	for start := 0; start < len(ids); start += filterBatchSize {
		end := min(start+filterBatchSize, len(ids))
		filter := inFilter(field, ids[start:end])
		if kind != "" {
			filter = and(filter, "knowledge_type eq "+quote(normalizedKnowledgeType(kind)))
		}
		// Require two empty passes separated by a visibility grace period. Source
		// mutation must be fenced by the application's deletion/transfer state.
		emptyPasses := 0
		for pass := 0; pass < 8; pass++ {
			found := 0
			err := r.scan(ctx, filter, false, func(docs []document) error {
				found += len(docs)
				actions := make([]action, 0, len(docs))
				for _, d := range docs {
					actions = append(actions, action{"@search.action": "delete", "id": d.ID})
				}
				return r.submit(ctx, actions)
			})
			if err != nil {
				return err
			}
			if found == 0 {
				emptyPasses++
			} else {
				emptyPasses = 0
			}
			if emptyPasses >= 2 {
				break
			}
			if pass == 7 {
				return fmt.Errorf("Azure AI Search deletion is not yet visible; retry the task")
			}
			if err := waitRetry(ctx, time.Duration(min(pass+1, 3))*500*time.Millisecond); err != nil {
				return err
			}
		}
	}
	return nil
}
func (r *Repository) DeleteByChunkIDList(ctx context.Context, ids []string, dim int, kind string) error {
	return r.deleteBy(ctx, "chunk_id", ids, dim, kind)
}
func (r *Repository) DeleteBySourceIDList(ctx context.Context, ids []string, dim int, kind string) error {
	return r.deleteBy(ctx, "source_id", ids, dim, kind)
}
func (r *Repository) DeleteByKnowledgeIDList(ctx context.Context, ids []string, dim int, kind string) error {
	return r.deleteBy(ctx, "knowledge_id", ids, dim, kind)
}

func (r *Repository) updateChunks(ctx context.Context, values map[string]any, field string) error {
	ids := make([]string, 0, len(values))
	for id := range values {
		ids = append(ids, id)
	}
	slices.Sort(ids)
	for start := 0; start < len(ids); start += filterBatchSize {
		end := min(start+filterBatchSize, len(ids))
		if err := r.scan(ctx, inFilter("chunk_id", ids[start:end]), false, func(docs []document) error {
			actions := make([]action, 0, len(docs))
			for _, d := range docs {
				value, ok := values[d.ChunkID]
				if !ok {
					return fmt.Errorf("Azure AI Search returned an unrelated chunk during update")
				}
				actions = append(actions, action{"@search.action": "merge", "id": d.ID, field: value})
			}
			return r.submit(ctx, actions)
		}); err != nil {
			return err
		}
	}
	return nil
}
func (r *Repository) BatchUpdateChunkEnabledStatus(ctx context.Context, values map[string]bool) error {
	converted := make(map[string]any, len(values))
	for k, v := range values {
		converted[k] = v
	}
	return r.updateChunks(ctx, converted, "is_enabled")
}
func (r *Repository) BatchUpdateChunkTagID(ctx context.Context, values map[string]string) error {
	converted := make(map[string]any, len(values))
	for k, v := range values {
		converted[k] = v
	}
	return r.updateChunks(ctx, converted, "tag_id")
}

// remapSource preserves generated-question suffixes (including hashed question
// suffixes). Other source forms use deterministic IDs for safe task retries.
func remapSource(source, chunk, target string) string {
	if source == chunk {
		return target
	}
	if strings.HasPrefix(source, chunk+"-") {
		return target + strings.TrimPrefix(source, chunk)
	}
	sum := sha256.Sum256([]byte(target + "\x00" + source))
	return target + "-s" + hex.EncodeToString(sum[:12])
}
func (r *Repository) CopyIndices(ctx context.Context, sourceKB string, knowledgeMap, chunkMap map[string]string, targetKB string, dim int, kind string) error {
	if sourceKB == "" || targetKB == "" || sourceKB == targetKB {
		return fmt.Errorf("Azure AI Search copy requires distinct source and target knowledge bases")
	}
	if err := r.checkDimension(dim); err != nil {
		return err
	}
	if len(chunkMap) == 0 {
		return nil
	}
	filter := "knowledge_base_id eq " + quote(sourceKB)
	if kind != "" {
		filter = and(filter, "knowledge_type eq "+quote(normalizedKnowledgeType(kind)))
	}
	return r.scan(ctx, filter, true, func(docs []document) error {
		actions := make([]action, 0, len(docs))
		for _, d := range docs {
			if d.KnowledgeBaseID != sourceKB {
				return fmt.Errorf("Azure AI Search copy returned an unrelated knowledge base")
			}
			targetChunk, ok := chunkMap[d.ChunkID]
			if !ok {
				continue
			}
			targetKnowledge, ok := knowledgeMap[d.KnowledgeID]
			if !ok || targetKnowledge == "" || targetChunk == "" {
				return fmt.Errorf("Azure AI Search copy is missing a document or chunk mapping")
			}
			if len(d.Embedding) > 0 {
				if err := r.validateVector(d.Embedding); err != nil {
					return err
				}
			}
			d.SourceID = remapSource(d.SourceID, d.ChunkID, targetChunk)
			d.ChunkID = targetChunk
			d.KnowledgeID = targetKnowledge
			d.KnowledgeBaseID = targetKB
			d.TagID = ""
			d.ID = r.documentID(d.SourceType, d.SourceID)
			a, err := uploadAction(d)
			if err != nil {
				return err
			}
			actions = append(actions, a)
		}
		return r.submit(ctx, actions)
	})
}
func (r *Repository) MoveKnowledgeIndices(ctx context.Context, sourceKB, targetKB, knowledgeID string, chunkIDs []string, dim int, kind string) error {
	if sourceKB == "" || targetKB == "" || sourceKB == targetKB || knowledgeID == "" {
		return fmt.Errorf("Azure AI Search move requires source/target knowledge bases and document")
	}
	if err := r.checkDimension(dim); err != nil {
		return err
	}
	if len(chunkIDs) == 0 {
		return nil
	}
	for start := 0; start < len(chunkIDs); start += filterBatchSize {
		end := min(start+filterBatchSize, len(chunkIDs))
		filter := and("knowledge_base_id eq "+quote(sourceKB), "knowledge_id eq "+quote(knowledgeID), inFilter("chunk_id", chunkIDs[start:end]))
		if kind != "" {
			filter = and(filter, "knowledge_type eq "+quote(normalizedKnowledgeType(kind)))
		}
		if err := r.scan(ctx, filter, false, func(docs []document) error {
			actions := make([]action, 0, len(docs))
			for _, d := range docs {
				if d.KnowledgeBaseID != sourceKB || d.KnowledgeID != knowledgeID || !slices.Contains(chunkIDs[start:end], d.ChunkID) {
					return fmt.Errorf("Azure AI Search move returned an unrelated record")
				}
				actions = append(actions, action{"@search.action": "merge", "id": d.ID, "knowledge_base_id": targetKB, "tag_id": ""})
			}
			return r.submit(ctx, actions)
		}); err != nil {
			return err
		}
	}
	return nil
}
