//go:build ignore

// Actual production Go ES8 mover + SSRF-validating persistent transport on owned HTTP sockets.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	esrepo "github.com/Tencent/WeKnora/internal/application/repository/retriever/elasticsearch/v8"
	"github.com/Tencent/WeKnora/internal/config"
	local "github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/elastic/go-elasticsearch/v8"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

// Flushing on the server does not prove that the client has received headers. Wait for an actual
// body read before cancelling a body-phase case; otherwise the error's SDK wrapper races with I/O.
type observedMoveTransport struct {
	http.RoundTripper
	read chan struct{}
	once sync.Once
}

func (t *observedMoveTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	response, err := t.RoundTripper.RoundTrip(r)
	if err == nil && strings.HasSuffix(r.URL.Path, "/_update_by_query") {
		response.Body = &observedMoveBody{ReadCloser: response.Body, signal: func() { t.once.Do(func() { close(t.read) }) }}
	}
	return response, err
}

type observedMoveBody struct {
	io.ReadCloser
	signal func()
}

func (b *observedMoveBody) Read(p []byte) (int, error) {
	b.signal()
	return b.ReadCloser.Read(p)
}

func main() {
	if len(os.Args) != 2 {
		panic("output path required")
	}
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	out := []any{}
	for _, name := range []string{"reuse", "retry-body-gate", "retry-large-body", "retry-empty-body", "retry-truncated-body", "truncated-valid-json", "truncated-partial-json", "invalid-chunk-valid-json", "invalid-chunk-partial-json", "empty-truncated-body", "reset-first", "reset-exhausted", "cancel-preheaders", "deadline-preheaders", "cancel-retry-body", "deadline-retry-body", "cancel-success-body", "deadline-success-body", "success-open-body", "error-open-body", "last-retry-open-body", "small-trailing-json", "large-trailing-json", "connection-close"} {
		func() {
			entered, release := make(chan struct{}, 1), make(chan struct{})
			var gateOnce sync.Once
			var count, connections atomic.Int32
			var mu sync.Mutex
			moveConnections := []int{}
			connectionIDs := map[string]int{}
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.Header().Set("X-Elastic-Product", "Elasticsearch")
				if r.Method == "HEAD" {
					return
				}
				if strings.HasSuffix(r.URL.Path, "/_mapping") {
					io.WriteString(w, `{"vectors":{"mappings":{"properties":{"chunk_id":{"type":"keyword"}}}}}`)
					return
				}
				if r.URL.Path != "/vectors/_update_by_query" {
					panic(r.URL.Path)
				}
				io.Copy(io.Discard, r.Body)
				n := int(count.Add(1))
				mu.Lock()
				id, ok := connectionIDs[r.RemoteAddr]
				if !ok {
					id = len(connectionIDs) + 1
					connectionIDs[r.RemoteAddr] = id
				}
				moveConnections = append(moveConnections, id)
				mu.Unlock()
				gate := func() { gateOnce.Do(func() { entered <- struct{}{} }); <-release }
				raw := func(status, headers, body string) {
					conn, _, err := w.(http.Hijacker).Hijack()
					must(err)
					defer conn.Close()
					io.WriteString(conn, "HTTP/1.1 "+status+"\r\nX-Elastic-Product: Elasticsearch\r\n"+headers+"\r\n"+body)
				}
				if n == 1 || name == "reset-exhausted" && n <= 4 || name == "last-retry-open-body" && n <= 4 {
					switch name {
					case "retry-body-gate":
						w.Header().Set("Content-Length", "2048")
						w.WriteHeader(503)
						io.WriteString(w, "partial")
						w.(http.Flusher).Flush()
						gate()
						io.WriteString(w, strings.Repeat(" ", 2041))
						return
					case "retry-large-body":
						body := strings.Repeat("x", 2<<20)
						w.Header().Set("Content-Length", fmt.Sprint(len(body)))
						w.WriteHeader(503)
						io.WriteString(w, body)
						return
					case "retry-empty-body":
						w.Header().Set("Content-Length", "0")
						w.WriteHeader(503)
						return
					case "retry-truncated-body":
						raw("503 Service Unavailable", "Content-Length: 2048\r\n", "partial")
						return
					case "truncated-valid-json":
						raw("200 OK", "Content-Length: 2048\r\n", `{"total":1,"updated":1}`)
						return
					case "truncated-partial-json":
						raw("200 OK", "Content-Length: 2048\r\n", `{"total":`)
						return
					case "invalid-chunk-valid-json", "invalid-chunk-partial-json":
						body := `{"total":1,"updated":1}`
						if strings.Contains(name, "partial") {
							body = `{"total":`
						}
						raw("200 OK", "Transfer-Encoding: chunked\r\n", fmt.Sprintf("%x\r\n%s\r\nxyz\r\n", len(body), body))
						return
					case "empty-truncated-body":
						raw("200 OK", "Content-Length: 2048\r\n", "")
						return
					case "reset-first", "reset-exhausted":
						conn, _, err := w.(http.Hijacker).Hijack()
						must(err)
						conn.Close()
						return
					case "cancel-preheaders", "deadline-preheaders":
						gate()
						return
					case "cancel-retry-body", "deadline-retry-body", "cancel-success-body", "deadline-success-body":
						w.Header().Set("Content-Length", "2048")
						if strings.Contains(name, "retry") {
							w.WriteHeader(503)
						}
						io.WriteString(w, `{"total":`)
						w.(http.Flusher).Flush()
						gate()
						return
					case "success-open-body":
						io.WriteString(w, `{"total":1,"updated":1}`)
						w.(http.Flusher).Flush()
						gate()
						return
					case "error-open-body":
						w.WriteHeader(400)
						io.WriteString(w, `{"error":{"type":"fixture","reason":"failed"}}`)
						w.(http.Flusher).Flush()
						gate()
						return
					case "last-retry-open-body":
						w.WriteHeader(503)
						io.WriteString(w, `{"error":{"type":"fixture","reason":"failed"}}`)
						if n == 4 {
							w.(http.Flusher).Flush()
							gate()
						}
						return
					case "small-trailing-json":
						io.WriteString(w, `{"total":1,"updated":1} {"ignored":true}`)
						return
					case "large-trailing-json":
						body := `{"total":1,"updated":1}` + strings.Repeat(" ", 8192)
						w.Header().Set("Content-Length", fmt.Sprint(len(body)))
						io.WriteString(w, body)
						return
					case "connection-close":
						w.Header().Set("Connection", "close")
					}
				}
				io.WriteString(w, `{"total":1,"updated":1}`)
			}))
			server.Config.ConnState = func(_ net.Conn, state http.ConnState) {
				if state == http.StateNew {
					connections.Add(1)
				}
			}
			server.Start()
			defer server.Close()
			transport := utils.NewSSRFSafeTransport(utils.DefaultSSRFSafeHTTPClientConfig())
			defer transport.CloseIdleConnections()
			observed := &observedMoveTransport{RoundTripper: &utils.SSRFValidatingRoundTripper{Base: transport}, read: make(chan struct{})}
			client, err := elasticsearch.NewTypedClient(elasticsearch.Config{Addresses: []string{server.URL}, Transport: observed})
			must(err)
			repo := esrepo.NewElasticsearchEngineRepository(client, &config.Config{}, &local.IndexConfig{IndexName: "vectors"}).(interfaces.KnowledgeIndexMover)
			errors := []string{}
			early := false
			atGate := -1
			gateCase := strings.Contains(name, "gate") || strings.Contains(name, "open-body") || strings.HasPrefix(name, "cancel-") || strings.HasPrefix(name, "deadline-")
			for attempt := 0; attempt < 2; attempt++ {
				call, cancel := context.WithCancel(context.Background())
				if attempt == 0 && strings.HasPrefix(name, "deadline-") {
					cancel()
					call, cancel = context.WithTimeout(context.Background(), 300*time.Millisecond)
				}
				done := make(chan error, 1)
				go func() { done <- repo.MoveKnowledgeIndices(call, "source", "target", "doc", nil, 2, "document") }()
				if attempt == 0 && gateCase {
					select {
					case <-entered:
					case <-time.After(3 * time.Second):
						panic("gate not entered: " + name)
					}
					if (strings.HasPrefix(name, "cancel-") || strings.HasPrefix(name, "deadline-")) && strings.HasSuffix(name, "-body") {
						select {
						case <-observed.read:
						case <-time.After(3 * time.Second):
							panic("client body read not entered: " + name)
						}
					}
					if strings.HasPrefix(name, "cancel-") {
						cancel()
					}
					if strings.HasPrefix(name, "cancel-") || strings.HasPrefix(name, "deadline-") {
						select {
						case err = <-done:
							early = true
						case <-time.After(3 * time.Second):
							panic("context did not stop: " + name)
						}
					} else {
						select {
						case err = <-done:
							early = true
						case <-time.After(100 * time.Millisecond):
						}
					}
					atGate = int(count.Load())
					close(release)
					if !early {
						select {
						case err = <-done:
						case <-time.After(3 * time.Second):
							panic("move did not finish: " + name)
						}
					}
				} else {
					select {
					case err = <-done:
					case <-time.After(3 * time.Second):
						panic("move did not finish: " + name)
					}
				}
				message := ""
				if err != nil {
					message = err.Error()
				}
				errors = append(errors, message)
				cancel()
			}
			mu.Lock()
			trace := append([]int{}, moveConnections...)
			mu.Unlock()
			out = append(out, map[string]any{"name": name, "errors": errors, "requests": count.Load(), "connections": trace, "total_connections": connections.Load(), "gate": gateCase, "completed_before_release": early, "requests_at_gate": atGate})
		}()
	}
	data, err := json.MarshalIndent(out, "", "  ")
	must(err)
	must(os.WriteFile(os.Args[1], append(data, '\n'), 0600))
	fmt.Printf("Exported %d actual Go ES8 transport cases\n", len(out))
}
