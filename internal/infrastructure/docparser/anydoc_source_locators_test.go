package docparser

import (
	"testing"

	"github.com/Tencent/WeKnora/internal/infrastructure/docparser/anydoc"
	"github.com/Tencent/WeKnora/internal/sourceloc"
)

func TestAnydocPDFPageMappingKeepsCrossPageChunks(t *testing.T) {
	md := "# First page\n\nLimit <1.5 mm\n\n# Second page\n\nApproved final result\n"
	blocks := AnydocPDFSourceBlocks(md, []anydoc.PDFPage{{Page: 1, Markdown: "# First page\n\nLimit <1.5 mm"}, {Page: 2, Markdown: "# Second page\n\nApproved final result"}})
	locs := sourceloc.NewIndex(md, blocks).Locators(0, len([]rune(md)))
	if len(locs) != 2 || locs[0].Page != 1 || locs[1].Page != 2 || locs[0].Mapping != "exact" {
		t.Fatalf("cross-page positions lost: %+v", locs)
	}
}

func TestAnydocPDFMappingRejectsWrongValuesAndPageOrdinals(t *testing.T) {
	if blocks := AnydocPDFSourceBlocks("Limit <1.5 mm", []anydoc.PDFPage{{Page: 1, Markdown: "Limit >1.5 mm"}}); len(blocks) != 0 {
		t.Fatalf("different numeric meaning mapped: %+v", blocks)
	}
	if blocks := AnydocPDFSourceBlocks("First page", []anydoc.PDFPage{{Page: 55, Markdown: "First page"}}); len(blocks) != 0 {
		t.Fatalf("invalid physical ordinal accepted: %+v", blocks)
	}
}
