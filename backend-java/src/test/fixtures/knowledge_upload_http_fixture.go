//go:build ignore

package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
	"mime/multipart"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"unicode/utf8"
)

type knowledge struct {
	interfaces.KnowledgeService
	captured map[string]interface{}
}

func (k *knowledge) CreateKnowledgeFromFile(ctx context.Context, kb string, file *multipart.FileHeader, metadata map[string]string, multi *bool, custom string, tags []string, channel string, overrides *types.KnowledgeProcessOverrides) (*types.Knowledge, error) {
	f, _ := file.Open()
	defer f.Close()
	var content bytes.Buffer
	content.ReadFrom(f)
	k.captured = map[string]interface{}{"kb": kb, "tenant": ctx.Value(types.TenantIDContextKey), "filename": file.Filename, "content": content.String(), "size": file.Size, "metadata": metadata, "multimodel": multi, "custom": custom, "tags": tags, "channel": channel, "overrides": overrides}
	return &types.Knowledge{ID: "ok"}, nil
}

type knowledgebase struct {
	interfaces.KnowledgeBaseService
}

func (k *knowledgebase) GetKnowledgeBaseByID(context.Context, string) (*types.KnowledgeBase, error) {
	return &types.KnowledgeBase{ID: "kb", TenantID: 1, CreatorID: "user"}, nil
}
func schema(t reflect.Type) map[string]interface{} {
	out := map[string]interface{}{"kind": t.Kind().String(), "type": t.String(), "name": t.Name()}
	switch t.Kind() {
	case reflect.Ptr:
		out["kind"] = "pointer"
		out["elem"] = schema(t.Elem())
	case reflect.Slice, reflect.Array, reflect.Map:
		out["elem"] = schema(t.Elem())
	case reflect.Struct:
		fields := []interface{}{}
		for i := 0; i < t.NumField(); i++ {
			field := t.Field(i)
			if field.PkgPath != "" {
				continue
			}
			name := strings.Split(field.Tag.Get("json"), ",")[0]
			if name == "-" {
				continue
			}
			if name == "" {
				name = field.Name
			}
			fields = append(fields, map[string]interface{}{"name": name, "schema": schema(field.Type)})
		}
		out["fields"] = fields
	}
	return out
}
func main() {
	if len(os.Args) > 1 && os.Args[1] == "schema" {
		json.NewEncoder(os.Stdout).Encode(schema(reflect.TypeOf(types.KnowledgeProcessOverrides{})))
		return
	}
	gin.SetMode(gin.ReleaseMode)
	logger.SetOutput(os.Stderr)
	os.Setenv("MAX_FILE_SIZE_MB", "1")
	cases := []map[string]interface{}{}
	for key, values := range map[string][]string{"metadata": {"", "{}", "null", "{\"a\":null}", "{\"a\":\"x\"}", "{\"a\":1}", "{\"a\":false}", "[]", "1", "{", "{} {}", "{\"a\":1,\"a\":\"x\"}"}, "enable_multimodel": {"", "1", "t", "T", "true", "TRUE", "True", "0", "f", "F", "false", "FALSE", "False", " true ", "yes", "null"}, "process_config": {"", "{}", "null", "{\"enable_multimodel\":null}", "{\"enable_multimodel\":true}", "{\"ENABLE_MULTIMODEL\":false}", "{\"summary_enabled\":false}", "{\"chunking_config\":{\"chunk_size\":128}}", "[]", "1", "{", "{} {}", "{\"summary_enabled\":\"false\"}", "{\"chunking_config\":[]}", "{\"unknown\":123}", "{\"question_generation_config\":{\"question_count\":1.5}}"}, "tag_ids": {"", "a,b", " a , b ", "a,__untagged__,,", "a,a", "\u00a0tag\u00a0"}, "channel": {"", "web", "gitlab", "unknown", " WEB "}, "fileName": {"", "docs/spec/design.md", "folder/", "docs\nfile.txt", "onerror=x.txt"}} {
		for _, v := range values {
			cases = append(cases, map[string]interface{}{"fields": map[string]string{key: v}, "name": "note.txt", "content": "hello,图"})
		}
	}
	for _, content := range []string{"", " ", "{}", "null", "1e999999", "{", "{} {}", "\ufeff{}", "\ufeff", "{\"x\":\"\\ud800\"}", "{\"x\":\"" + string([]byte{0xff}) + "\"}"} {
		cases = append(cases, map[string]interface{}{"fields": map[string]string{}, "name": "note.JSON", "content": content})
	}
	cases = append(cases, map[string]interface{}{"fields": map[string]string{"process_config": "{\"enable_multimodel\":false}", "enable_multimodel": "true"}, "name": "note.txt", "content": "x"}, map[string]interface{}{"fields": map[string]string{"fileName": "custom.json"}, "name": "original.txt", "content": "{"}, map[string]interface{}{"fields": map[string]string{"fileName": "custom.txt"}, "name": "original.json", "content": "{"}, map[string]interface{}{"fields": map[string]string{}, "name": "dir/file.txt", "content": "x"}, map[string]interface{}{"fields": map[string]string{}, "name": "large.txt", "content": strings.Repeat("x", (1<<20)+1)})
	rows := []interface{}{}
	for _, input := range cases {
		if !utf8.ValidString(input["content"].(string)) {
			input["content_base64"] = base64.StdEncoding.EncodeToString([]byte(input["content"].(string)))
		}
		var b bytes.Buffer
		w := multipart.NewWriter(&b)
		fields := input["fields"].(map[string]string)
		for key, v := range fields {
			w.WriteField(key, v)
		}
		part, _ := w.CreateFormFile("file", input["name"].(string))
		part.Write([]byte(input["content"].(string)))
		w.Close()
		svc := &knowledge{}
		h := handler.NewKnowledgeHandler(nil, svc, &knowledgebase{}, nil, nil, nil, nil, nil, nil, nil, nil)
		router := gin.New()
		router.Use(middleware.ErrorHandler())
		router.Use(func(c *gin.Context) {
			c.Set(types.TenantIDContextKey.String(), uint64(1))
			c.Set(types.UserIDContextKey.String(), "user")
			ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, uint64(1))
			c.Request = c.Request.WithContext(ctx)
			c.Next()
		})
		router.POST("/knowledge-bases/:id/knowledge/file", h.CreateKnowledgeFromFile)
		req := httptest.NewRequest("POST", "/knowledge-bases/kb/knowledge/file", &b)
		req.Header.Set("Content-Type", w.FormDataContentType())
		resp := httptest.NewRecorder()
		router.ServeHTTP(resp, req)
		var body interface{}
		json.Unmarshal(resp.Body.Bytes(), &body)
		rows = append(rows, map[string]interface{}{"input": input, "status": resp.Code, "response": body, "captured": svc.captured})
	}
	if err := json.NewEncoder(os.Stdout).Encode(rows); err != nil {
		panic(fmt.Sprint(err))
	}
}
