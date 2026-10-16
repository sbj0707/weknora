package router

import (
	"encoding/json"
	"fmt"
	"os"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
)

// Export the production matcher, including fields not used directly by scope matching:
// a type error anywhere in runtimeTaskPayloadProbe invalidates the entire probe.
func TestKnowledgeBaseTaskScopeActualOracle(t *testing.T) {
	path := os.Getenv("WEKNORA_KB_TASK_SCOPE_ORACLE")
	if path == "" {
		t.Skip("set WEKNORA_KB_TASK_SCOPE_ORACLE to export production Go scope decisions")
	}
	rows := []map[string]any{}
	add := func(name, taskType, raw, kb string, documents, sources []string) {
		docs, ds := map[string]struct{}{}, map[string]struct{}{}
		for _, id := range documents {
			if id != "" {
				docs[id] = struct{}{}
			}
		}
		for _, id := range sources {
			if id != "" {
				ds[id] = struct{}{}
			}
		}
		rows = append(rows, map[string]any{"name": name, "type": taskType, "raw": raw,
			"kb": kb, "documents": documents, "sources": sources,
			"matches": matchesKnowledgeBase(taskType, []byte(raw), kb, docs, ds)})
	}
	documents, sources := []string{"doc-1", "doc-2", ""}, []string{"source-1", ""}
	taskTypes := []string{"unknown:future"}
	for _, queue := range types.QueueDefinitions() {
		taskTypes = append(taskTypes, queue.TaskTypes...)
	}
	for _, taskType := range taskTypes {
		for _, field := range []string{"knowledge_base_id", "kb_id", "source_id", "target_id", "source_kb_id", "target_kb_id"} {
			add(taskType+"/"+field, taskType, fmt.Sprintf(`{"%s":"kb-1"}`, field), "kb-1", documents, sources)
		}
		for _, item := range []struct{ name, raw string }{
			{"document", `{"knowledge_id":"doc-1"}`},
			{"documents", `{"knowledge_ids":[null,"other","doc-2"]}`},
			{"source", `{"data_source_id":"source-1"}`},
			{"unrelated", `{"knowledge_base_id":"other","knowledge_id":"other"}`},
		} {
			add(taskType+"/"+item.name, taskType, item.raw, "kb-1", documents, sources)
		}
	}
	for _, field := range []string{"tenant_id", "knowledge_base_id", "kb_id", "knowledge_id", "task_id", "source_id", "target_id", "source_kb_id", "target_kb_id", "data_source_id", "sync_log_id", "knowledge_ids", "enqueued_at", "created_at"} {
		for _, value := range []string{`7`, `true`, `{}`, `[]`, `null`, `"value"`} {
			add(field+"/"+value, types.TypeDocumentProcess,
				fmt.Sprintf(`{"knowledge_base_id":"kb-1","%s":%s}`, field, value), "kb-1", documents, sources)
		}
	}
	for _, item := range []struct{ name, raw string }{
		{"casefold", `{"KNOWLEDGE_BASE_ID":"kb-1"}`},
		{"duplicate-null", `{"knowledge_base_id":"kb-1","knowledge_base_id":null}`},
		{"duplicate-overwrite", `{"knowledge_base_id":"kb-1","knowledge_base_id":"other"}`},
		{"duplicate-invalid-before", `{"knowledge_base_id":7,"knowledge_base_id":"kb-1"}`},
		{"duplicate-invalid-after", `{"knowledge_base_id":"kb-1","knowledge_base_id":7}`},
		{"duplicate-array-reset", `{"knowledge_ids":["doc-1"],"knowledge_ids":["other"]}`},
		{"duplicate-array-null", `{"knowledge_ids":["doc-1"],"knowledge_ids":null}`},
		{"array-null-retains", `{"knowledge_ids":["doc-1"],"knowledge_ids":[null]}`},
		{"array-shrink-retains", `{"knowledge_ids":["other","doc-1"],"knowledge_ids":["new"],"knowledge_ids":[null,null]}`},
		{"array-empty-resets", `{"knowledge_ids":["doc-1"],"knowledge_ids":[],"knowledge_ids":[null]}`},
		{"array-null-resets", `{"knowledge_ids":["doc-1"],"knowledge_ids":null,"knowledge_ids":[null]}`},
		{"array-invalid-element", `{"knowledge_ids":["doc-1",7]}`},
		{"ignored-unknown", `{"knowledge_base_id":"kb-1","unknown":{"tenant_id":-1}}`},
		{"uint-max", `{"knowledge_base_id":"kb-1","tenant_id":18446744073709551615}`},
		{"uint-overflow", `{"knowledge_base_id":"kb-1","tenant_id":18446744073709551616}`},
		{"uint-negative", `{"knowledge_base_id":"kb-1","tenant_id":-1}`},
		{"uint-exponent", `{"knowledge_base_id":"kb-1","tenant_id":1e0}`},
		{"int-min", `{"knowledge_base_id":"kb-1","created_at":-9223372036854775808}`},
		{"int-overflow", `{"knowledge_base_id":"kb-1","enqueued_at":9223372036854775808}`},
		{"empty", ``}, {"malformed", `{`}, {"array", `[]`}, {"null", `null`},
		{"trailing", `{"knowledge_base_id":"kb-1"}{}`},
		{"trailing-space", "{\"knowledge_base_id\":\"kb-1\"} \r\n\t"},
	} {
		add(item.name, types.TypeDocumentProcess, item.raw, "kb-1", documents, sources)
	}
	add("empty-base-doc", types.TypeDocumentProcess, `{"knowledge_id":"doc-1"}`, "", documents, nil)
	add("empty-base-source", types.TypeDataSourceSync, `{"data_source_id":"source-1"}`, "", nil, sources)
	add("empty-identifiers", types.TypeDocumentProcess, `{"knowledge_id":"","data_source_id":""}`, "", []string{""}, []string{""})
	data, err := json.MarshalIndent(rows, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, append(data, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
	t.Logf("exported %d production matcher cases", len(rows))
}
