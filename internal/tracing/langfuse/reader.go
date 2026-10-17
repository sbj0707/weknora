package langfuse

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/Tencent/WeKnora/internal/types"
	"go.opentelemetry.io/otel/trace"
)

var ErrTraceUnavailable = errors.New("tracing is not configured")

// ChatTrace exposes only the fields needed by the authenticated chat viewer.
// Project credentials and ingestion resource attributes never reach the browser.
type ChatTrace struct {
	ID           string             `json:"id"`
	Name         string             `json:"name"`
	SessionID    string             `json:"sessionId"`
	Timestamp    string             `json:"timestamp"`
	Latency      float64            `json:"latency"`
	Input        any                `json:"input"`
	Output       any                `json:"output"`
	Metadata     map[string]any     `json:"metadata"`
	Observations []TraceObservation `json:"observations"`
}

type TraceObservation struct {
	ID                  string         `json:"id"`
	ParentObservationID string         `json:"parentObservationId"`
	Name                string         `json:"name"`
	Type                string         `json:"type"`
	StartTime           string         `json:"startTime"`
	EndTime             string         `json:"endTime"`
	CompletionStartTime string         `json:"completionStartTime"`
	Model               string         `json:"model"`
	Level               string         `json:"level"`
	StatusMessage       string         `json:"statusMessage"`
	Input               any            `json:"input"`
	Output              any            `json:"output"`
	Metadata            map[string]any `json:"metadata"`
	UsageDetails        map[string]any `json:"usageDetails"`
}

// FindMessageTrace accepts a message already authorized by MessageService.
// Persisted traceparent is the exact lookup; old messages use session + request ID.
func (m *Manager) FindMessageTrace(ctx context.Context, message *types.Message) (*ChatTrace, error) {
	if !m.Enabled() {
		return nil, ErrTraceUnavailable
	}
	return readMessageTrace(ctx, m.cfg, message)
}

func readMessageTrace(ctx context.Context, cfg Config, message *types.Message) (*ChatTrace, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	if parts := strings.Split(message.ExecutionContext.LangfuseTraceparent, "-"); len(parts) == 4 {
		if id, err := trace.TraceIDFromHex(parts[1]); err == nil && id.IsValid() {
			found, err := readTrace(ctx, cfg, id.String())
			if err != nil {
				return nil, err
			}
			if found != nil && traceMatchesMessage(found, message) {
				return cleanChatTrace(found), nil
			}
		}
	}
	if message.RequestID == "" {
		return nil, nil
	}
	// Scope the legacy search to the owning session. Never choose the latest trace
	// as a substitute for the requested turn; several questions can share a session.
	for page := 1; page <= 10; page++ {
		query := url.Values{"sessionId": {message.SessionID}, "limit": {"100"}, "page": {strconv.Itoa(page)}}
		var list struct {
			Data []ChatTrace `json:"data"`
			Meta struct {
				TotalPages int `json:"totalPages"`
			} `json:"meta"`
		}
		if _, err := readLangfuseJSON(ctx, cfg, "/api/public/traces?"+query.Encode(), &list); err != nil {
			return nil, err
		}
		for _, candidate := range list.Data {
			if candidate.SessionID == message.SessionID && candidate.Metadata["request_id"] == message.RequestID {
				found, err := readTrace(ctx, cfg, candidate.ID)
				if err != nil {
					return nil, err
				}
				if found != nil && traceMatchesMessage(found, message) {
					return cleanChatTrace(found), nil
				}
			}
		}
		if len(list.Data) < 100 || page >= list.Meta.TotalPages {
			return nil, nil
		}
	}
	return nil, errors.New("trace search exceeded page limit")
}

func traceMatchesMessage(t *ChatTrace, message *types.Message) bool {
	if t.SessionID != message.SessionID {
		return false
	}
	if message.RequestID != "" && t.Metadata["request_id"] == message.RequestID {
		return true
	}
	for _, observation := range t.Observations {
		if observation.Metadata["message_id"] == message.ID && observation.Metadata["session_id"] == message.SessionID {
			return true
		}
	}
	return false
}

func readTrace(ctx context.Context, cfg Config, id string) (*ChatTrace, error) {
	if parsed, err := trace.TraceIDFromHex(id); err != nil || !parsed.IsValid() {
		return nil, errors.New("invalid trace ID")
	}
	var result ChatTrace
	status, err := readLangfuseJSON(ctx, cfg, "/api/public/traces/"+url.PathEscape(id), &result)
	if err != nil || status == http.StatusNotFound {
		return nil, err
	}
	return &result, nil
}

func readLangfuseJSON(ctx context.Context, cfg Config, path string, target any) (int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, strings.TrimRight(cfg.Host, "/")+path, nil)
	if err != nil {
		return 0, errors.New("invalid tracing server URL")
	}
	req.SetBasicAuth(cfg.PublicKey, cfg.SecretKey)
	client := &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(req)
	if err != nil {
		return 0, errors.New("tracing server is unreachable")
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotFound {
		return response.StatusCode, nil
	}
	if response.StatusCode != http.StatusOK {
		return response.StatusCode, fmt.Errorf("tracing server returned status %d", response.StatusCode)
	}
	const maxBytes = 16 << 20
	body, err := io.ReadAll(io.LimitReader(response.Body, maxBytes+1))
	if err != nil || len(body) > maxBytes {
		return response.StatusCode, errors.New("cannot read tracing response")
	}
	if err := json.Unmarshal(body, target); err != nil {
		return response.StatusCode, errors.New("invalid tracing response")
	}
	return response.StatusCode, nil
}

func cleanChatTrace(t *ChatTrace) *ChatTrace {
	delete(t.Metadata, "resourceAttributes")
	delete(t.Metadata, "scope")
	for i := range t.Observations {
		delete(t.Observations[i].Metadata, "resourceAttributes")
		delete(t.Observations[i].Metadata, "scope")
	}
	return t
}
