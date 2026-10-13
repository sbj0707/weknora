package repository

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

func TestChunkWikiPagesExactCitationAndDocumentFallback(t *testing.T) {
	db := setupWikiPagesTestDB(t)
	repo := &wikiPageRepository{db: db}
	for _, row := range []struct {
		id, kb       string
		tenant       uint64
		refs, chunks types.StringArray
	}{
		{"direct", "kb", 7, types.StringArray{"doc"}, types.StringArray{"chunk"}},
		{"summary", "kb", 7, types.StringArray{"doc|Title"}, types.StringArray{}},
		{"legacy", "kb", 7, types.StringArray{"doc"}, nil},
		{"other-chunk", "kb", 7, types.StringArray{"doc"}, types.StringArray{"other"}},
		{"prefix-doc", "kb", 7, types.StringArray{"doc-other"}, nil},
		{"tenant", "kb", 8, types.StringArray{"doc"}, types.StringArray{"chunk"}},
		{"kb", "other", 7, types.StringArray{"doc"}, types.StringArray{"chunk"}},
		{"deleted", "kb", 7, types.StringArray{"doc"}, types.StringArray{"chunk"}},
	} {
		require.NoError(t, db.Create(&types.WikiPage{ID: row.id, Slug: row.id, TenantID: row.tenant, KnowledgeBaseID: row.kb, SourceRefs: row.refs, ChunkRefs: row.chunks, Content: "Full wiki content"}).Error)
	}
	require.NoError(t, db.Delete(&types.WikiPage{ID: "deleted"}).Error)
	pages, err := repo.ListChunkWikiPages(context.Background(), 7, "kb", "doc", "chunk")
	require.NoError(t, err)
	require.Len(t, pages, 3)
	var ids []string
	for _, page := range pages {
		ids = append(ids, page.ID)
		require.Equal(t, "Full wiki content", page.Content)
	}
	require.ElementsMatch(t, []string{"direct", "summary", "legacy"}, ids)
}
