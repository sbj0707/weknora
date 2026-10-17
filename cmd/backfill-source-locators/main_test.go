package main

import (
	"testing"

	"github.com/Tencent/WeKnora/internal/infrastructure/docparser"
	"github.com/Tencent/WeKnora/internal/infrastructure/docparser/anydoc"
)

func TestRepairPreservesExistingMapsAndEditedChunks(t *testing.T) {
	md := "First source paragraph\n\nSecond source paragraph\n"
	blocks := docparser.AnydocPDFSourceBlocks(md, []anydoc.PDFPage{{Page: 1, Markdown: "First source paragraph"}, {Page: 2, Markdown: "Second source paragraph"}})
	rows := []chunkRow{
		{ID: "cross-page", Kind: "parent_text", Content: md, End: len([]rune(md)), SourceJSON: "[]"},
		{ID: "edited", Kind: "text", Content: "Changed", Start: 0, End: 22, Revision: 1, SourceJSON: "[]"},
		{ID: "mapped", Kind: "text", Content: md, End: len([]rune(md)), SourceJSON: `[{"type":"pdf","page":1}]`},
		{ID: "summary", Kind: "summary", Content: "Generated summary", SourceJSON: "[]"},
	}
	updates, err := plan(rows, md, blocks, "hash")
	if err != nil {
		t.Fatal(err)
	}
	if len(updates) != 1 || updates[0].ID != "cross-page" || len(updates[0].Locators) != 2 || updates[0].Locators[1].Page != 2 || updates[0].Locators[0].SourceHash != "hash" {
		t.Fatalf("unexpected changes: %+v", updates)
	}
	rows[0].Content = "Unrelated regenerated text"
	if _, err := plan(rows, md, blocks, "hash"); err == nil {
		t.Fatal("changed source offsets accepted")
	}
}
