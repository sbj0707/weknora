package langfuse

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
)

const readerTraceID = "aaf80158f84d948b299e083ffbdbb524"

func TestReadMessageTraceExactAndCredentialIsolation(t *testing.T) {
	message := &types.Message{ID: "message", SessionID: "session", RequestID: "request"}
	message.ExecutionContext.LangfuseTraceparent = "00-" + readerTraceID + "-237087553ae9a2d1-01"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/public/traces/"+readerTraceID {
			t.Errorf("expected exact trace lookup, got %s", r.URL)
		}
		user, password, ok := r.BasicAuth()
		if !ok || user != "public" || password != "private" {
			t.Error("missing server-side credentials")
		}
		_ = json.NewEncoder(w).Encode(ChatTrace{ID: readerTraceID, SessionID: "session",
			Metadata:     map[string]any{"request_id": "request", "resourceAttributes": map[string]any{"key": "private"}, "scope": "credentials"},
			Observations: []TraceObservation{{ID: "node", Metadata: map[string]any{"resourceAttributes": "private", "scope": "private", "message_id": "message"}}}})
	}))
	defer server.Close()
	found, err := readMessageTrace(context.Background(), Config{Host: server.URL, PublicKey: "public", SecretKey: "private"}, message)
	if err != nil || found == nil {
		t.Fatalf("trace not found: %v", err)
	}
	encoded, _ := json.Marshal(found)
	if strings.Contains(string(encoded), "private") || strings.Contains(string(encoded), "resourceAttributes") || strings.Contains(string(encoded), "scope") {
		t.Fatalf("ingestion credentials leaked: %s", encoded)
	}
}

func TestReadMessageTraceLegacySearchDoesNotChooseAnotherTurn(t *testing.T) {
	message := &types.Message{ID: "message", SessionID: "session", RequestID: "wanted"}
	var calls []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls = append(calls, r.URL.Path)
		if r.URL.Path == "/api/public/traces" {
			if r.URL.Query().Get("sessionId") != "session" {
				t.Error("missing session scope")
			}
			if r.URL.Query().Get("page") == "1" {
				rows := make([]ChatTrace, 100)
				for i := range rows {
					rows[i] = ChatTrace{ID: "wrong", SessionID: "session", Metadata: map[string]any{"request_id": "other"}}
				}
				_ = json.NewEncoder(w).Encode(map[string]any{"data": rows, "meta": map[string]int{"totalPages": 2}})
			} else {
				_ = json.NewEncoder(w).Encode(map[string]any{"data": []ChatTrace{{ID: readerTraceID, SessionID: "session", Metadata: map[string]any{"request_id": "wanted"}}}, "meta": map[string]int{"totalPages": 2}})
			}
			return
		}
		_ = json.NewEncoder(w).Encode(ChatTrace{ID: readerTraceID, SessionID: "session", Metadata: map[string]any{"request_id": "wanted"}})
	}))
	defer server.Close()
	found, err := readMessageTrace(context.Background(), Config{Host: server.URL}, message)
	if err != nil || found == nil || found.ID != readerTraceID || len(calls) != 3 {
		t.Fatalf("legacy lookup selected wrong turn: trace=%v error=%v calls=%v", found, err, calls)
	}
}

func TestReadMessageTraceRejectsForeignSession(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/public/traces" {
			_, _ = w.Write([]byte(`{"data":[],"meta":{"totalPages":0}}`))
			return
		}
		_ = json.NewEncoder(w).Encode(ChatTrace{ID: readerTraceID, SessionID: "foreign", Metadata: map[string]any{"request_id": "request"}})
	}))
	defer server.Close()
	message := &types.Message{ID: "message", SessionID: "session", RequestID: "request"}
	message.ExecutionContext.LangfuseTraceparent = "00-" + readerTraceID + "-237087553ae9a2d1-01"
	found, err := readMessageTrace(context.Background(), Config{Host: server.URL}, message)
	if err != nil || found != nil {
		t.Fatalf("foreign trace returned: %v %v", found, err)
	}
}

func TestReadTraceUpstreamFailureAndNoCredentialRedirect(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusInternalServerError, http.StatusFound} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Location", "http://invalid.example/")
				w.WriteHeader(status)
				_, _ = w.Write([]byte("private-key"))
			}))
			defer server.Close()
			_, err := readTrace(context.Background(), Config{Host: server.URL}, readerTraceID)
			if err == nil || strings.Contains(err.Error(), "private-key") {
				t.Fatalf("unsafe upstream error: %v", err)
			}
		})
	}
}
