package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/Tencent/WeKnora/internal/common"
	"github.com/Tencent/WeKnora/internal/models/embedding"
	"github.com/Tencent/WeKnora/internal/types"
)

// A remote index call must not publish the snapshot of an edit already superseded
// by another editor, a question mutation or an owner/KB relocation.
func (s *chunkService) chunkIndexBinding(ctx context.Context, expected *types.Chunk, kb *types.KnowledgeBase, title string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	current, err := s.chunkRepository.GetChunkByID(ctx, expected.TenantID, expected.ID)
	if err != nil || current == nil || current.TenantID != expected.TenantID || current.KnowledgeID != expected.KnowledgeID || current.KnowledgeBaseID != expected.KnowledgeBaseID || current.ContentRevision != expected.ContentRevision || current.Content != common.CleanInvalidUTF8(expected.Content) || current.IsEnabled != expected.IsEnabled || current.ContextHeader != expected.ContextHeader || current.ParentChunkID != expected.ParentChunkID || !sameChunkIndexMetadata(current.Metadata, expected.Metadata) {
		return ErrChunkRevisionConflict
	}
	knowledge, err := s.knowledgeRepo.GetKnowledgeByID(ctx, expected.TenantID, expected.KnowledgeID)
	if err != nil || knowledge == nil || knowledge.TenantID != expected.TenantID || knowledge.KnowledgeBaseID != expected.KnowledgeBaseID || knowledge.Title != title {
		return ErrChunkRevisionConflict
	}
	currentKB, err := s.kbRepository.GetKnowledgeBaseByID(ctx, expected.KnowledgeBaseID)
	if err != nil || currentKB == nil || currentKB.TenantID != expected.TenantID || currentKB.EmbeddingModelID != kb.EmbeddingModelID || currentKB.Type != kb.Type || vectorStoreBinding(currentKB.VectorStoreID) != vectorStoreBinding(kb.VectorStoreID) {
		return ErrChunkRevisionConflict
	}
	return nil
}

// JSONB reorders object keys and adds whitespace on a round trip. Keep all
// values (including integer precision) while comparing the persisted snapshot.
func sameChunkIndexMetadata(a, b []byte) bool {
	if bytes.Equal(a, b) {
		return true
	}
	canonical := func(raw []byte) ([]byte, error) {
		if !json.Valid(raw) {
			return nil, fmt.Errorf("invalid chunk metadata")
		}
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		var value any
		if err := decoder.Decode(&value); err != nil {
			return nil, err
		}
		return json.Marshal(value)
	}
	left, err := canonical(a)
	if err != nil {
		return false
	}
	right, err := canonical(b)
	return err == nil && bytes.Equal(left, right)
}

func vectorStoreBinding(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

type chunkIndexStateSaver interface {
	UpdateChunkIndexState(context.Context, *types.Chunk) error
}

func (s *chunkService) saveChunkEditIndexState(ctx context.Context, chunk *types.Chunk) error {
	chunk.UpdatedAt = time.Now()
	if saver, ok := s.chunkRepository.(chunkIndexStateSaver); ok {
		return saver.UpdateChunkIndexState(ctx, chunk)
	}
	current, err := s.chunkRepository.GetChunkByID(ctx, chunk.TenantID, chunk.ID)
	if err != nil || current == nil || current.ContentRevision != chunk.ContentRevision || current.Content != common.CleanInvalidUTF8(chunk.Content) || current.IsEnabled != chunk.IsEnabled || !sameChunkIndexMetadata(current.Metadata, chunk.Metadata) {
		return ErrChunkRevisionConflict
	}
	return s.chunkRepository.UpdateChunk(ctx, chunk)
}

type chunkBoundEmbedder struct {
	embedding.Embedder
	check func(context.Context) error
}

func (e chunkBoundEmbedder) Embed(ctx context.Context, text string) ([]float32, error) {
	if err := e.check(ctx); err != nil {
		return nil, err
	}
	v, err := e.Embedder.Embed(ctx, text)
	if source := e.check(ctx); source != nil {
		return nil, source
	}
	return v, err
}
func (e chunkBoundEmbedder) BatchEmbed(ctx context.Context, texts []string) ([][]float32, error) {
	if err := e.check(ctx); err != nil {
		return nil, err
	}
	v, err := e.Embedder.BatchEmbed(ctx, texts)
	if source := e.check(ctx); source != nil {
		return nil, source
	}
	return v, err
}
func (e chunkBoundEmbedder) BatchEmbedWithPool(ctx context.Context, model embedding.Embedder, texts []string) ([][]float32, error) {
	if err := e.check(ctx); err != nil {
		return nil, err
	}
	v, err := e.Embedder.BatchEmbedWithPool(ctx, model, texts)
	if source := e.check(ctx); source != nil {
		return nil, source
	}
	return v, err
}
