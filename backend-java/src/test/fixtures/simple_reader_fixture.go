//go:build ignore

// Generate expected simple-parser results from the real production Go reader.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"os"
	"strings"

	"github.com/Tencent/WeKnora/internal/infrastructure/docparser"
	"github.com/Tencent/WeKnora/internal/types"
)

type fixture struct {
	FileName string            `json:"file_name"`
	FileType string            `json:"file_type"`
	Data     string            `json:"data"`
	Expected *types.ReadResult `json:"expected"`
	Error    string            `json:"error"`
}

func main() {
	inputs := []struct {
		name, kind string
		data       []byte
	}{}
	add := func(name, kind string, data []byte) {
		inputs = append(inputs, struct {
			name, kind string
			data       []byte
		}{name, kind, data})
	}
	for _, kind := range []string{"md", "markdown", "txt", "text", ".MD", ".Text", ""} {
		add("README.MD", kind, []byte("# 标题😀\n正文\r\n"))
	}
	add("invalid.txt", "txt", []byte{0xf0, 0x90, 0x80, 0x41, 0xff, 0xc0, 0xaf})
	for _, csv := range []string{"", "a,b\n1,2\n", "a,b\r\n1,2\r\n", "\n\na,b\n\n1,2\n", "a,b\n1\n", "a\n1,2", " a, b \n 1, 2 ", "\"a\",\"b\"\n\"1\",\"2\"", "a,b\n\"multi\nline\",\"quote\"\"value\"", "a,b\n\"open,2", "a,b\nquo\"te,2", "a,b\n\"quo\"te,2", "a,b\n\"x\" ,2", ",\n,", "\"\"", " ", "\n", "a\r", "a,b\n\u00a0一,\u2003二", "a,b\n\"x\r\ny\",2"} {
		add("rows.csv", "csv", []byte(csv))
	}
	for _, raw := range []string{"", " ", "null", "true", "1", "-0", "1.5", "1e-7", "1e21", "1e23", "9007199254740993", "1e999", "{}", "[]", "[1,2,3]", "{\"b\":2,\"a\":1}", "{\"html\":\"<&>\",\"control\":\"\\u2028\\u2029\"}", "{\"a\":1,\"a\":2}", "{bad}", "{} {}", "// comment\n{}", "{\"nested\":{\"rows\":[{\"a\":1},{\"a\":2}]}}"} {
		add("data.json", "json", []byte(raw))
	}
	add("bom.json", "json", append([]byte{0xef, 0xbb, 0xbf}, []byte("{\"标题\":\"中文\"}")...))
	add("big.json", "json", []byte(`{"root":{"a":"`+strings.Repeat("中文😀", 180)+`","b":"`+strings.Repeat("<&>", 180)+`","c":"`+strings.Repeat("c", 900)+`"},"tail":[1,2,3]}`))
	rows := make([]map[string]any, 0, 16)
	for i := 0; i < 16; i++ {
		rows = append(rows, map[string]any{"id": i, "text": strings.Repeat("中文", 100)})
	}
	array, _ := json.Marshal(rows)
	add("array.json", "json", array)
	for _, sample := range []struct {
		name string
		data []byte
	}{{"png", []byte("\x89PNG\r\n\x1a\n")}, {"jpg", []byte("\xff\xd8\xff")}, {"gif", []byte("GIF89a")}, {"bmp", []byte("BM12345")}, {"webp", []byte("RIFF\x00\x00\x00\x00WEBPVP")}, {"tiff", []byte("II\x2a\x00")}, {"unknown", []byte{0, 1, 2}}, {"text", []byte("hello")}, {"html", []byte("<html>doc")}, {"pdf", []byte("%PDF-1.7")}} {
		add(sample.name+" with space.png", "png", sample.data)
	}
	add("", "png", []byte("GIF87a"))
	for _, kind := range []string{"mp3", "wav", "m4a", "flac", "ogg"} {
		add("voice."+kind, kind, []byte{0, 1, 255})
	}
	add("", "mp3", []byte{1, 2})
	add("book.pdf", "pdf", []byte("PDF"))
	reader := &docparser.SimpleFormatReader{}
	output := make([]fixture, 0, len(inputs))
	for _, input := range inputs {
		result, err := reader.Read(context.Background(), &types.ReadRequest{FileName: input.name, FileType: input.kind, FileContent: input.data})
		item := fixture{FileName: input.name, FileType: input.kind, Data: base64.StdEncoding.EncodeToString(input.data), Expected: result}
		if err != nil {
			item.Error = err.Error()
		}
		output = append(output, item)
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(output); err != nil {
		panic(err)
	}
}
