//go:build ignore

// Regenerate from the canonical Go protobufs and typed source-locator JSON.
// go run ./backend-java/src/test/fixtures/docreader_fixture.go > backend-java/src/test/resources/go-docreader.json
package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"

	"github.com/Tencent/WeKnora/docreader/proto"
	"github.com/Tencent/WeKnora/internal/types"
	wireproto "google.golang.org/protobuf/proto"
)

func wire(message wireproto.Message) string {
	value, err := wireproto.Marshal(message)
	if err != nil {
		panic(err)
	}
	return base64.StdEncoding.EncodeToString(value)
}

type sourceCase struct {
	Start       uint32             `json:"start"`
	End         uint32             `json:"end"`
	LocatorJSON string             `json:"locator_json"`
	Expected    *types.SourceBlock `json:"expected"`
}

func main() {
	request := &proto.ReadRequest{FileContent: []byte{0, 1, 255}, FileName: "文档.pdf", FileType: "pdf", Title: "测试", RequestId: "request-42", Config: &proto.ReadConfig{ParserEngine: "builtin", ParserEngineOverrides: map[string]string{"ocr": "true"}}}
	locators := []string{
		`{"type":"pdf","page":3,"bbox":[0.1,0.2,0.9,1],"quote":"😀中"}`,
		`{"type":"docx","block":2,"mapping":"verified","partial":true,"source_id":"block-2","source_hash":"test-hash"}`,
		`{"type":"slide","slide":1,"title":"演示"}`,
		`{"type":"sheet","sheet":"表一","row_start":2,"row_end":8}`,
		`{"type":"text","start":0,"end":2}`,
		`{"type":"time","start_ms":1200,"end_ms":3600}`,
		`{"type":"section","section":2,"title":"第二章"}`,
		`{"type":"future-engine","unknown":{"nested":true}}`,
		`{"Type":"pdf","Page":4}`, `{"TYPE":"pdf","PAGE":5}`,
		`{"type":"pdf","page":null,"bbox":[null,1]}`, `{"type":"pdf","bbox":[]}`,
		`{"type":"pdf","page":-3}`, `{"type":"pdf","page":9223372036854775807}`,
		`{"type":"pdf","page":"3"}`, `{"type":"pdf","page":1.0}`, `{"type":"pdf","page":1.5}`,
		`{"type":"pdf","page":9223372036854775808}`,
		`{"type":"pdf","partial":"true"}`, `{"type":"pdf","partial":1}`, `{"type":"pdf","partial":null}`,
		`{"type":"pdf","bbox":["0.1"]}`, `{"type":"pdf","bbox":[true]}`, `{"type":"pdf","bbox":null}`,
		`{"type":"pdf","bbox":[1e999]}`, `{"type":1}`, `{"type":null}`, `{"type":""}`, `{"page":3}`,
		`null`, `[]`, `"pdf"`, `{bad JSON}`, ``,
		`{"type":"pdf","type":null}`, `{"type":"pdf","type":""}`, `{"type":1,"type":"pdf"}`,
		`{"type":"pdf","page":"3","page":3}`, `{"type":"pdf","page":3,"page":0}`, `{"type":"pdf","page":3,"page":null}`,
		`{"type":"pdf","partial":true,"partial":false}`, `{"type":"pdf","bbox":[1],"bbox":[]}`, `{"type":"pdf","bbox":[1],"bbox":null}`,
		`{"type":"pdf","unknown":{"nested":true},"unknown":null}`,
		`{"type":"pdf"}{"type":"docx"}`, `{"type":"pdf"}junk`,
	}
	cases := make([]sourceCase, 0)
	for _, raw := range locators {
		for _, bounds := range [][2]uint32{{0, 2}, {1, 1}, {4294967294, 4294967295}} {
			item := sourceCase{Start: bounds[0], End: bounds[1], LocatorJSON: raw}
			// Same private decoder rules as internal/infrastructure/docparser/source_blocks.go;
			// json.Unmarshal uses the actual production types.SourceLocator.
			var locator types.SourceLocator
			if item.End > item.Start && raw != "" && json.Unmarshal([]byte(raw), &locator) == nil && locator.Type != "" {
				item.Expected = &types.SourceBlock{Start: int(item.Start), End: int(item.End), Locator: locator}
			}
			cases = append(cases, item)
		}
	}
	meta := &proto.ReadStreamResponse{Payload: &proto.ReadStreamResponse_Meta{Meta: &proto.ReadStreamMeta{MarkdownContent: "😀中\n正文", ImageDirPath: "/tmp/images", Metadata: map[string]string{"parser": "builtin"}, ImageCount: 2, SourceBlocks: []*proto.SourceBlock{{Start: 0, End: 2, LocatorJson: locators[0]}}}}}
	image := &proto.ImageRef{Filename: "one.png", OriginalRef: "original://one.png", MimeType: "image/png", StorageKey: "storage/one.png", ImageData: []byte{0, 1, 2}}
	frame := &proto.ReadStreamResponse{Payload: &proto.ReadStreamResponse_Image{Image: image}}
	unary := &proto.ReadResponse{MarkdownContent: meta.GetMeta().MarkdownContent, ImageDirPath: meta.GetMeta().ImageDirPath, Metadata: meta.GetMeta().Metadata, SourceBlocks: meta.GetMeta().SourceBlocks, ImageRefs: []*proto.ImageRef{image}}
	result := map[string]any{"request": wire(request), "stream_frames": []string{wire(meta), wire(frame)}, "unary": wire(unary), "source_cases": cases}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(result); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
