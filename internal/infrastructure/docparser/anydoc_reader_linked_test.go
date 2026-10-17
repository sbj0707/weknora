//go:build anydoc && cgo

package docparser

import (
	"bytes"
	"context"
	"os"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
)

func TestAnydocReaderRetainsPhysicalPDFPages(t *testing.T) {
	data, err := os.ReadFile("anydoc/testdata/upstream/pdf/text.pdf")
	if err != nil {
		t.Fatal(err)
	}
	result, err := NewAnydocReader(nil, nil).Read(context.Background(), &types.ReadRequest{
		FileName: "text.pdf", FileType: "pdf", FileContent: data,
	})
	if err != nil {
		t.Fatal(err)
	}
	pages := map[int]bool{}
	for _, b := range result.SourceBlocks {
		if b.Locator.Type != "pdf" || b.Locator.Mapping != "exact" || b.Start < 0 || b.End <= b.Start || b.End > len([]rune(result.MarkdownContent)) {
			t.Fatalf("invalid source block: %+v", b)
		}
		pages[b.Locator.Page] = true
	}
	if len(pages) != 2 || !pages[1] || !pages[2] {
		t.Fatalf("physical PDF pages lost: %+v", pages)
	}
}

func TestAnydocReaderMixedPDFFallsBackThroughRead(t *testing.T) {
	data, err := os.ReadFile("anydoc/testdata/upstream/pdf/handmade-mixed.pdf")
	if err != nil {
		t.Fatal(err)
	}
	fallback := &stubDocReader{result: &types.ReadResult{MarkdownContent: "Both pages including OCR"}}
	req := &types.ReadRequest{FileName: "mixed.pdf", FileType: "pdf", FileContent: data}
	result, err := NewAnydocReader(nil, fallback).Read(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	if fallback.got == nil ||
		!bytes.Equal(fallback.got.FileContent, req.FileContent) ||
		fallback.got.ParserEngine != BuiltinEngineName ||
		result.MarkdownContent != "Both pages including OCR" ||
		result.Metadata["anydoc_fallback"] != "scanned_pdf" {
		t.Fatalf("mixed PDF did not reach fallback intact: %+v", result)
	}
	if result, err := NewAnydocReader(nil, nil).Read(context.Background(), req); err == nil || result != nil {
		t.Fatalf("mixed PDF without OCR returned partial success: result=%+v err=%v", result, err)
	}
}
