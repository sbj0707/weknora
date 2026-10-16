package repository

import (
	"context"
	"encoding/json"

	"github.com/Tencent/WeKnora/internal/types"
)

// Include exact chunk citations and document summaries/legacy pages without
// chunk citations. Pages citing only other chunks are not attributed to this one.
func (r *wikiPageRepository) ListChunkWikiPages(ctx context.Context, tenantID uint64, kbID, docID, chunkID string) ([]*types.WikiPage, error) {
	query := r.db.WithContext(ctx).Model(&types.WikiPage{}).Where("tenant_id = ? AND knowledge_base_id = ?", tenantID, kbID)
	prefix := docID + "|"
	if r.db.Dialector.Name() == "sqlite" {
		query = query.Where(`EXISTS (SELECT 1 FROM json_each(wiki_pages.chunk_refs) WHERE value = ?)
   OR (coalesce(json_array_length(wiki_pages.chunk_refs), 0) = 0 AND EXISTS
    (SELECT 1 FROM json_each(wiki_pages.source_refs) WHERE value = ? OR substr(value, 1, length(?)) = ?))`, chunkID, docID, prefix, prefix)
	} else {
		chunkNeedle, _ := json.Marshal([]string{chunkID})
		docNeedle, _ := json.Marshal([]string{docID})
		encodedPrefix, _ := json.Marshal(prefix)
		pattern := "%" + escapeLikePattern(string(encodedPrefix[:len(encodedPrefix)-1])) + "%"
		query = query.Where(`chunk_refs @> ?::jsonb OR (coalesce(jsonb_array_length(chunk_refs), 0) = 0
   AND (source_refs @> ?::jsonb OR source_refs::text LIKE ?))`, string(chunkNeedle), string(docNeedle), pattern)
	}
	var pages []*types.WikiPage
	err := query.Order("title ASC, slug ASC").Find(&pages).Error
	return pages, err
}
