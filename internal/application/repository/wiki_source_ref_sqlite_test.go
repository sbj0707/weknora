package repository

import (
	"context"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

func TestWikiSourceReferencesSQLiteExactAndLegacy(t *testing.T) {
	for _, doc := range []string{"doc", "文档😀", `quote"slash\_%`} {
		t.Run(doc, func(t *testing.T) {
			db := setupWikiPagesTestDB(t)
			r := &wikiPageRepository{db: db}
			for _, row := range []struct{ id, kb, ref string }{
				{"exact", "kb", doc}, {"legacy", "kb", doc + "|Title"},
				{"longer", "kb", doc + "-other"}, {"embedded", "kb", "other|Title " + doc + "|text"},
				{"foreign-kb", "other", doc}, {"deleted", "kb", doc},
				{"foreign-tenant", "kb", doc},
			} {
				tenantID := uint64(7)
				if row.id == "foreign-tenant" {
					tenantID = 8
				}
				require.NoError(t, db.Create(&types.WikiPage{ID: row.id, Slug: row.id, TenantID: tenantID,
					KnowledgeBaseID: row.kb, SourceRefs: types.StringArray{row.ref}, Content: "Full content"}).Error)
			}
			require.NoError(t, db.Delete(&types.WikiPage{ID: "deleted"}).Error)
			ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(7))
			pages, err := r.ListBySourceRef(ctx, "kb", doc)
			require.NoError(t, err)
			var ids []string
			for _, p := range pages {
				ids = append(ids, p.ID)
			}
			require.ElementsMatch(t, []string{"exact", "legacy"}, ids)
			slugs, err := r.ListSlugsBySourceRef(ctx, "kb", doc)
			require.NoError(t, err)
			require.ElementsMatch(t, []string{"exact", "legacy"}, slugs)
			previews, err := r.ListBySlugs(ctx, "kb", []string{"exact", "legacy", "foreign-tenant"})
			require.NoError(t, err)
			require.Len(t, previews, 2)
			chunkPages, err := r.ListChunkWikiPages(context.Background(), 7, "kb", doc, "chunk")
			require.NoError(t, err)
			require.Len(t, chunkPages, 2)
		})
	}
}
