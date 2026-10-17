package handler

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	apperrors "github.com/Tencent/WeKnora/internal/errors"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/tracing/langfuse"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

type traceMessageService struct {
	interfaces.MessageService
	message *types.Message
	err     error
}

func (s *traceMessageService) GetMessage(_ context.Context, session, id string) (*types.Message, error) {
	if session != "session" || id != "message" {
		panic("wrong message scope")
	}
	return s.message, s.err
}

func TestMessageTraceAuthorizesBeforeReadingLangfuse(t *testing.T) {
	for _, err := range []error{apperrors.ErrSessionNotFound, gorm.ErrRecordNotFound} {
		h := &MessageHandler{MessageService: &traceMessageService{err: err}, TraceReader: func(context.Context, *types.Message) (*langfuse.ChatTrace, error) {
			t.Fatal("Langfuse must not be queried for inaccessible messages")
			return nil, nil
		}}
		w := serveMessageTrace(h)
		if w.Code != http.StatusNotFound {
			t.Fatalf("got %d %s", w.Code, w.Body)
		}
	}
}

func TestMessageTraceStates(t *testing.T) {
	for _, tc := range []struct {
		name   string
		trace  *langfuse.ChatTrace
		err    error
		status int
	}{
		{"ready", &langfuse.ChatTrace{ID: "trace"}, nil, 200},
		{"not_found", nil, nil, 200},
		{"disabled", nil, langfuse.ErrTraceUnavailable, 200},
		{"upstream_failed", nil, errors.New("upstream private-key"), 503},
	} {
		t.Run(tc.name, func(t *testing.T) {
			message := &types.Message{ID: "message", SessionID: "session", RequestID: "request"}
			h := &MessageHandler{MessageService: &traceMessageService{message: message}, TraceReader: func(_ context.Context, m *types.Message) (*langfuse.ChatTrace, error) {
				if m != message {
					t.Fatal("reader did not receive authorized message")
				}
				return tc.trace, tc.err
			}}
			w := serveMessageTrace(h)
			if w.Code != tc.status {
				t.Fatalf("got %d %s", w.Code, w.Body)
			}
			if tc.status == 200 {
				var body struct {
					Data struct {
						State     string `json:"state"`
						RequestID string `json:"request_id"`
					} `json:"data"`
				}
				_ = json.Unmarshal(w.Body.Bytes(), &body)
				if body.Data.State != tc.name || body.Data.RequestID != "request" {
					t.Fatalf("wrong response %s", w.Body)
				}
			}
		})
	}
}

func serveMessageTrace(h *MessageHandler) *httptest.ResponseRecorder {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(middleware.ErrorHandler())
	r.GET("/messages/:session_id/:id/trace", h.GetTrace)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/messages/session/message/trace", nil))
	return w
}
