//go:build ignore

// Export actual Go ES8 move requests and outcomes, including the typed client's decoding/retries.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"sync/atomic"
	"time"

	esrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/elasticsearch/v8"
	"github.com/Tencent/WeKnora/internal/config"
	local "github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/elastic/go-elasticsearch/v8"
	"github.com/elastic/go-elasticsearch/v8/typedapi/core/updatebyquery"
)

func schema(t reflect.Type) map[string]any {
	r := map[string]any{"kind": t.Kind().String(), "type": t.String()}
	if t.PkgPath() == "github.com/elastic/go-elasticsearch/v8/typedapi/types" && (t.Name() == "Retries" || t.Name() == "BulkIndexByScrollFailure" || t.Name() == "ErrorCause") {
		r["kind"] = "custom"
		r["custom"] = t.Name()
		return r
	}
	switch t.Kind() {
	case reflect.Pointer, reflect.Slice:
		if t.Kind() == reflect.Pointer {
			r["kind"] = "pointer"
		}
		r["elem"] = schema(t.Elem())
	case reflect.Struct:
		r["name"] = t.Name()
		fields := []any{}
		for i := 0; i < t.NumField(); i++ {
			f := t.Field(i)
			name := strings.Split(f.Tag.Get("json"), ",")[0]
			fields = append(fields, map[string]any{"name": name, "schema": schema(f.Type)})
		}
		r["fields"] = fields
	}
	return r
}

type scenario struct {
	Name             string   `json:"name"`
	Mapping          string   `json:"mapping"`
	Source           string   `json:"source"`
	Target           string   `json:"target"`
	Knowledge        string   `json:"knowledge"`
	Chunks           []string `json:"chunks"`
	Dimension        int      `json:"dimension"`
	Kind             string   `json:"kind"`
	Status           int      `json:"status"`
	Body             string   `json:"body"`
	ThenSuccess      bool     `json:"then_success"`
	DropFirst        bool     `json:"drop_first"`
	Location         string   `json:"location"`
	NoInitialProduct bool     `json:"no_initial_product"`
	NoMoveProduct    bool     `json:"no_move_product"`
	Error            string   `json:"error"`
	Requests         []any    `json:"requests"`
}

func main() {
	if len(os.Args) != 2 {
		panic("output path required")
	}
	cases := []scenario{}
	add := func(name, body string) {
		cases = append(cases, scenario{Name: name, Mapping: "keyword", Source: "source", Target: "target", Knowledge: "doc", Chunks: []string{"ignored"}, Dimension: 7, Kind: "ignored", Status: 200, Body: body, Requests: []any{}})
	}
	add("complete", `{"total":2,"updated":2}`)
	add("empty-retry", `{"total":0,"updated":0}`)
	add("partial", `{"total":2,"updated":1}`)
	add("missing-total", `{"updated":0}`)
	add("missing-updated", `{"total":0}`)
	add("negative", `{"total":-1,"updated":-1}`)
	add("max-count", `{"total":9223372036854775807,"updated":9223372036854775807}`)
	add("duplicate-null", `{"total":1,"total":null,"updated":1}`)
	add("duplicate-last", `{"total":1,"total":2,"updated":2}`)
	add("folded-fields", `{"TOTAL":1,"Updated":1,"TIMED_OUT":false}`)
	add("trailing-json", `{"total":1,"updated":1} {"timed_out":true}`)
	add("trailing-invalid", `{"total":1,"updated":1}garbage`)
	for _, root := range []string{"null", "[]", "1", "true", `"root"`, "", " ", "{", `{"total":`, `{"total":01}`, `{"total":1,}`, `{"total":1,"updated":1} `} {
		add(fmt.Sprintf("root-%d", len(cases)), root)
	}
	for _, field := range []string{"total", "updated", "version_conflicts", "timed_out", "batches", "deleted", "noops", "requests_per_second", "throttled_millis", "throttled_until_millis", "took", "task", "throttled", "throttled_until", "retries", "failures"} {
		for n, value := range []string{"null", `"1"`, "1", "1.0", "true", "{}", "[]", "9223372036854775808", "1e1000"} {
			add(fmt.Sprintf("typed-%s-%d", field, n), `{"total":1,"updated":1,"`+field+`":`+value+`}`)
		}
	}
	for n, value := range []string{`{"bulk":"2","search":1.5}`, `{"bulk":"bad"}`, `{"search":"9223372036854775808"}`, `{"bulk":true}`, `{"unknown":{"bulk":"bad"}}`, `"bulk"`, `["bulk",2]`, `{"bulk":"+2"}`, `{"bulk":" 2"}`, `{"bulk":1e1000}`} {
		add(fmt.Sprintf("retries-%d", n), `{"total":1,"updated":1,"retries":`+value+`}`)
	}
	for n, value := range []string{`[{}]`, `[null]`, `[1]`, `[true]`, `["unknown"]`, `[{"id":3}]`, `[{"index":false}]`, `[{"status":"bad"}]`, `[{"status":1.5}]`, `[{"cause":{"reason":12,"type":{}}}]`, `[{"cause":{"root_cause":{}}}]`, `[{"cause":{"suppressed":1}}]`, `[{"cause":"raw reason"}]`, `[{"unknown":{"id":3}}]`, `[{"cause":{"caused_by":{"root_cause":1}}}]`} {
		add(fmt.Sprintf("failures-%d", n), `{"total":1,"updated":1,"failures":`+value+`}`)
	}
	add("deferred-error-before-custom", `{"total":"bad","retries":{"bulk":"bad"}}`)
	add("deferred-error-before-failure", `{"total":"bad","failures":[{"id":3}]}`)
	for _, status := range []int{201, 204, 298, 299, 400, 401, 404, 429, 500, 502, 503, 504} {
		add(fmt.Sprintf("status-%d", status), `{"error":{"type":"fixture_error","reason":"failed"}}`)
		cases[len(cases)-1].Status = status
	}
	for n, body := range []string{`{}`, `null`, `[]`, `"error"`, `{"status":"400"}`, `{"status":null,"error":"raw reason"}`, `{"status":418,"error":{"type":12,"reason":{"x":1}}}`, `{"STATUS":400,"ERROR":{"type":"ignored"}}`, `{"error":{"root_cause":{}}}`, `{"error":{"suppressed":1}}`, `{"error":{"caused_by":{"root_cause":1}}}`, `{"error":{"type":"a"},"error":{"reason":"b"}}`, "", `{"error":`} {
		add(fmt.Sprintf("http-error-%d", n), body)
		cases[len(cases)-1].Status = 400
	}
	for _, status := range []int{502, 503, 504, 429} {
		add(fmt.Sprintf("retry-%d", status), `{"error":{"type":"temporary","reason":"retry"}}`)
		c := &cases[len(cases)-1]
		c.Status = status
		c.ThenSuccess = true
	}
	for _, mapping := range []string{"text", "unknown"} {
		add("mapping-"+mapping, `{"total":1,"updated":1}`)
		cases[len(cases)-1].Mapping = mapping
	}
	add("transport-retry", `{"total":1,"updated":1}`)
	cases[len(cases)-1].DropFirst = true
	add("float32-overflow", `{"total":1,"updated":1,"requests_per_second":1e39}`)
	add("float32-rounding", `{"total":1,"updated":1,"requests_per_second":1.0000000596046448}`)
	for _, status := range []int{301, 307, 308} {
		add(fmt.Sprintf("redirect-%d", status), `{"error":{"type":"redirect","reason":"stay here"}}`)
		c := &cases[len(cases)-1]
		c.Status = status
		c.Location = "/must-not-follow"
	}
	for _, name := range []string{"unverified-rejected", "verify-on-move", "already-verified", "unverified-error", "unverified-status299"} {
		add(name, `{"total":1,"updated":1}`)
		c := &cases[len(cases)-1]
		c.NoInitialProduct = name != "already-verified"
		c.NoMoveProduct = name != "verify-on-move"
		if name == "unverified-error" || name == "unverified-status299" {
			c.Status = 400
			if name == "unverified-status299" {
				c.Status = 299
			}
			c.Body = `{"error":{"type":"fixture","reason":"error"}}`
		}
	}
	for n, body := range []string{
		`{"status":9223372036854775807,"error":{"type":"large status","reason":"ok"}}`,
		`{"error":{"type":"a","reason":"a\/b"}}`,
		`{"error":{"type":"a","reason":"\ud800"}}`,
		`{"error":{"type":"a","reason":"\ud83d\ude00"}}`,
		`{"error":{"type":"a","reason":"\\ud800"}}`,
		`{"error":{"type":"a","reason":"\u4e2d\n\t"}}`,
	} {
		add(fmt.Sprintf("error-strings-%d", n), body)
		cases[len(cases)-1].Status = 400
	}
	for n, value := range []string{`"\u0007"`, `"\u000b"`, `"\u007f"`, `"\u2028"`, `"\u00a0"`, `"\ud800"`} {
		add(fmt.Sprintf("strconv-quote-%d", n), `{"total":1,"updated":1,"retries":{"bulk":`+value+`}}`)
	}
	add("quoted-scope", `{"total":1,"updated":1}`)
	c := &cases[len(cases)-1]
	c.Source = " 源\"\\\n"
	c.Target = "<目标>&\"\\\n"
	c.Knowledge = " 文档\"\\\n"
	c.Chunks = nil
	c.Dimension = -1
	c.Kind = ""
	add("empty-scope", `{"total":0,"updated":0}`)
	c = &cases[len(cases)-1]
	c.Source = ""
	c.Target = ""
	c.Knowledge = ""
	c.Chunks = nil
	for i := range cases {
		c := &cases[i]
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("X-Elastic-Product", "Elasticsearch")
			if (r.URL.Path == "/vectors/_update_by_query" && c.NoMoveProduct) || (r.URL.Path != "/vectors/_update_by_query" && c.NoInitialProduct) {
				w.Header().Del("X-Elastic-Product")
			}
			if c.Location != "" {
				w.Header().Set("Location", c.Location)
			}
			if r.Method == "HEAD" {
				w.WriteHeader(200)
				return
			}
			if r.URL.Path == "/vectors/_mapping" {
				fmt.Fprintf(w, `{"vectors":{"mappings":{"properties":{"chunk_id":{"type":%q}}}}}`, c.Mapping)
				return
			}
			if r.URL.Path != "/vectors/_update_by_query" {
				panic(r.URL.Path)
			}
			data, _ := io.ReadAll(r.Body)
			var body any
			if err := json.Unmarshal(data, &body); err != nil {
				panic(err)
			}
			c.Requests = append(c.Requests, map[string]any{"method": r.Method, "path": r.URL.Path, "query": r.URL.RawQuery, "body": body})
			if c.DropFirst && len(c.Requests) == 1 {
				conn, _, err := w.(http.Hijacker).Hijack()
				if err != nil {
					panic(err)
				}
				conn.Close()
				return
			}
			if c.ThenSuccess && len(c.Requests) > 1 {
				io.WriteString(w, `{"total":1,"updated":1}`)
				return
			}
			w.WriteHeader(c.Status)
			io.WriteString(w, c.Body)
		}))
		client, err := elasticsearch.NewTypedClient(elasticsearch.Config{Addresses: []string{server.URL}})
		if err != nil {
			panic(err)
		}
		repo := esrepo.NewElasticsearchEngineRepository(client, &config.Config{}, &local.IndexConfig{IndexName: "vectors"})
		err = repo.(interfaces.KnowledgeIndexMover).MoveKnowledgeIndices(context.Background(), c.Source, c.Target, c.Knowledge, c.Chunks, c.Dimension, c.Kind)
		if err != nil {
			c.Error = err.Error()
		}
		server.Close()
	}
	contexts := []any{}
	for _, mode := range []string{"cancel", "deadline"} {
		entered := make(chan struct{}, 1)
		release := make(chan struct{})
		var count atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("X-Elastic-Product", "Elasticsearch")
			w.Header().Set("Content-Type", "application/json")
			if r.Method == "HEAD" {
				return
			}
			if strings.HasSuffix(r.URL.Path, "/_mapping") {
				io.WriteString(w, `{"vectors":{"mappings":{"properties":{"chunk_id":{"type":"keyword"}}}}}`)
				return
			}
			count.Add(1)
			select {
			case entered <- struct{}{}:
			default:
			}
			<-release
			io.WriteString(w, `{"total":1,"updated":1}`)
		}))
		client, err := elasticsearch.NewTypedClient(elasticsearch.Config{Addresses: []string{server.URL}})
		if err != nil {
			panic(err)
		}
		repo := esrepo.NewElasticsearchEngineRepository(client, &config.Config{}, &local.IndexConfig{IndexName: "vectors"})
		var call context.Context
		var cancel context.CancelFunc
		if mode == "deadline" {
			call, cancel = context.WithTimeout(context.Background(), 200*time.Millisecond)
		} else {
			call, cancel = context.WithCancel(context.Background())
		}
		done := make(chan error, 1)
		go func() {
			done <- repo.(interfaces.KnowledgeIndexMover).MoveKnowledgeIndices(call, "source", "target", "doc", nil, 2, "document")
		}()
		select {
		case <-entered:
		case <-time.After(3 * time.Second):
			panic("move HTTP not entered")
		}
		if mode == "cancel" {
			cancel()
		}
		select {
		case err = <-done:
		case <-time.After(3 * time.Second):
			panic("move cancellation did not finish")
		}
		contexts = append(contexts, map[string]any{"name": mode, "requests": count.Load(), "cancelled": errors.Is(err, context.Canceled), "deadline": errors.Is(err, context.DeadlineExceeded), "completed_before_response": true})
		cancel()
		close(release)
		server.Close()
	}
	out := map[string]any{"schema": schema(reflect.TypeOf(updatebyquery.Response{})), "cases": cases, "contexts": contexts}
	data, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		panic(err)
	}
	if err = os.WriteFile(os.Args[1], append(data, '\n'), 0600); err != nil {
		panic(err)
	}
	fmt.Printf("Exported %d actual Go ES8 move scenarios\n", len(cases))
}
