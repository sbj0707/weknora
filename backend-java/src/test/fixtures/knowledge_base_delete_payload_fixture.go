//go:build ignore

package main

import (
	"encoding/json"
	"fmt"
	"os"

	"github.com/Tencent/WeKnora/internal/types"
)

func main() {
	rows := []map[string]any{}
	add := func(name, raw string) {
		var payload types.KBDeletePayload
		err := json.Unmarshal([]byte(raw), &payload)
		message := ""
		if err != nil {
			message = err.Error()
		}
		rows = append(rows, map[string]any{"name": name, "raw": raw, "error": message, "decoded": payload})
	}
	for _, row := range []struct{ name, raw string }{
		{"empty", ""}, {"malformed", "{"}, {"null", "null"}, {"object", "{}"}, {"array", "[]"}, {"trailing", "{}{}"},
		{"casefold", `{"TENANT_ID":1,"KNOWLEDGE_BASE_ID":"kb","DATA_SOURCE_IDS":["source",null],"EFFECTIVE_ENGINES":[{"RETRIEVER_ENGINE_TYPE":"sqlite","RETRIEVER_TYPE":"vector"}]}`},
		{"duplicate-null", `{"knowledge_base_id":"kb","knowledge_base_id":null}`},
		{"duplicate-error", `{"tenant_id":"bad","tenant_id":1}`},
		{"duplicate-slice", `{"data_source_ids":["a"],"data_source_ids":["b",null]}`},
		{"duplicate-pointer", `{"vector_store_id":"one","vector_store_id":null}`},
		{"duplicate-pointer-string", `{"vector_store_id":"one","vector_store_id":"two"}`},
		{"duplicate-engines", `{"effective_engines":[{"retriever_engine_type":"sqlite"}],"effective_engines":[{"retriever_type":"keywords"}]}`},
		{"null-engine", `{"effective_engines":[null]}`},
		{"duplicate-source-null", `{"data_source_ids":["source"],"data_source_ids":[null]}`},
		{"source-shrink-expand", `{"data_source_ids":["a","b"],"data_source_ids":["new"],"data_source_ids":[null,null]}`},
		{"source-empty-resets", `{"data_source_ids":["a","b"],"data_source_ids":[],"data_source_ids":[null,null]}`},
		{"source-null-resets", `{"data_source_ids":["a","b"],"data_source_ids":null,"data_source_ids":[null,null]}`},
		{"engine-null-retains", `{"effective_engines":[{"retriever_engine_type":"sqlite","retriever_type":"vector"}],"effective_engines":[null]}`},
		{"engine-shrink-expand", `{"effective_engines":[{"retriever_engine_type":"sqlite"},{"retriever_engine_type":"postgres"}],"effective_engines":[{"retriever_type":"vector"}],"effective_engines":[null,null]}`},
		{"engine-empty-resets", `{"effective_engines":[{"retriever_engine_type":"sqlite"}],"effective_engines":[],"effective_engines":[null]}`},
		{"engine-null-resets", `{"effective_engines":[{"retriever_engine_type":"sqlite"}],"effective_engines":null,"effective_engines":[null]}`},
		{"duplicate-engine-field-error", `{"effective_engines":[{"retriever_type":7,"retriever_type":"vector"}]}`},
		{"uint-max", `{"tenant_id":18446744073709551615}`}, {"uint-overflow", `{"tenant_id":18446744073709551616}`},
		{"uint-negative", `{"tenant_id":-1}`}, {"uint-exponent", `{"tenant_id":1e0}`},
		{"unknown-fields", `{"initiator":{"api_key_id":false},"unknown":["ignored"]}`},
	} {
		add(row.name, row.raw)
	}
	for _, field := range []string{"tenant_id", "knowledge_base_id", "data_source_ids", "effective_engines", "vector_store_id", "lf_trace_id", "lf_parent_obs_id", "lf_traceparent", "lf_user_id", "lf_session_id"} {
		for _, value := range []string{`7`, `true`, `{}`, `[]`, `null`, `"value"`} {
			add(field+"/"+value, fmt.Sprintf(`{"%s":%s}`, field, value))
		}
	}
	data, err := json.MarshalIndent(rows, "", "  ")
	if err != nil {
		panic(err)
	}
	if err = os.WriteFile(os.Args[1], append(data, '\n'), 0600); err != nil {
		panic(err)
	}
	fmt.Println("exported", len(rows), "actual Go KB deletion payload cases")
}
