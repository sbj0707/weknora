package pageimage

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/Tencent/WeKnora/internal/models/api"
	"github.com/Tencent/WeKnora/internal/models/api/openaicompletions"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

func TestAnswerProviderReceivesRealJPEGAndPageSource(t *testing.T) {
	t.Setenv("SSRF_WHITELIST", "127.0.0.1")
	f := newFixture(t)
	f.chunk(t, "chunk", 2)
	f.render(t)
	evidence := f.s.Collect(f.ctx, []*types.SearchResult{hit("chunk")}, f.scope(), NewBudget(true, 0, 0))
	require.Len(t, evidence.Images, 1)
	var received map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := json.NewDecoder(r.Body).Decode(&received); err != nil {
			t.Error(err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"answer","choices":[{"index":0,"message":{"role":"assistant","content":"Page two"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}`))
	}))
	defer server.Close()
	client := openaicompletions.New(openaicompletions.Config{Endpoint: api.Endpoint{BaseURL: server.URL, Model: "vision-test", Client: server.Client()}, Settings: api.DefaultOpenAICompletions()})
	_, err := client.Chat(context.Background(), []api.Message{{Role: "user", MultiContent: MessageParts("Read the chart", nil, evidence)}}, nil)
	require.NoError(t, err)
	messages := received["messages"].([]any)
	parts := messages[0].(map[string]any)["content"].([]any)
	require.Len(t, parts, 3)
	require.Contains(t, parts[1].(map[string]any)["text"], `"page":2`)
	url := parts[2].(map[string]any)["image_url"].(map[string]any)["url"].(string)
	require.Equal(t, evidence.Images[0], url)
	data, err := base64.StdEncoding.DecodeString(url[len("data:image/jpeg;base64,"):])
	require.NoError(t, err)
	_, err = jpeg.Decode(bytes.NewReader(data))
	require.NoError(t, err)
}
