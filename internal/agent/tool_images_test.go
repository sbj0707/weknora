package agent

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"github.com/Tencent/WeKnora/internal/models/api"
	"github.com/Tencent/WeKnora/internal/models/api/openaicompletions"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

func TestToolImagesReachNextModelTurnAfterAllReplies(t *testing.T) {
	step := types.AgentStep{ToolCalls: []types.ToolCall{
		{ID: "shot", Name: "local_browser", Result: &types.ToolResult{
			Success: true, Output: `{"width":1}`, Images: []string{"data:image/png;base64,YQ=="},
		}},
		{ID: "read", Name: "local_browser", Result: &types.ToolResult{Success: true, Output: "page text"}},
	}}
	for _, vision := range []bool{true, false} {
		engine := &AgentEngine{config: &types.AgentConfig{ChatModelSupportsVision: vision}}
		messages := engine.appendToolResults(nil, step)
		messages = engine.appendToolImages(t.Context(), messages, step)
		require.Equal(t, "tool", messages[1].Role)
		require.Equal(t, "tool", messages[2].Role)
		if vision {
			require.Len(t, messages, 4)
			require.Equal(t, "user", messages[3].Role)
			require.Equal(t, step.ToolCalls[0].Result.Images, messages[3].Images)
			require.Contains(t, messages[3].Content, "untrusted tool evidence")
			client := openaicompletions.New(openaicompletions.Config{Settings: api.DefaultOpenAICompletions()})
			body, err := client.BuildRequestBody(messages, nil, false)
			require.NoError(t, err)
			raw, err := json.Marshal(body["messages"])
			require.NoError(t, err)
			var wire []map[string]any
			require.NoError(t, json.Unmarshal(raw, &wire))
			parts := wire[3]["content"].([]any)
			require.Len(t, parts, 2)
			imagePart := parts[0].(map[string]any)["image_url"].(map[string]any)
			require.Equal(t, step.ToolCalls[0].Result.Images[0], imagePart["url"])
		} else {
			require.Len(t, messages, 3)
			require.Contains(t, messages[1].Content, "cannot view")
			require.NotContains(t, messages[1].Content, "YQ==")
		}
	}
	engine := &AgentEngine{imageDescriber: func(context.Context, []byte, string) (string, error) {
		return "A chart", nil
	}}
	messages := engine.appendToolImages(t.Context(), engine.appendToolResults(nil, step), step)
	require.Contains(t, messages[1].Content, "A chart")
	require.Equal(t, `{"width":1}`, step.ToolCalls[0].Result.Output,
		"model-only fallback must not alter persisted output")
	engine.imageDescriber = func(context.Context, []byte, string) (string, error) {
		return "", errors.New("unavailable")
	}
	messages = engine.appendToolImages(t.Context(), engine.appendToolResults(nil, step), step)
	require.Contains(t, messages[1].Content, "cannot view")
	engine.imageDescriber = func(context.Context, []byte, string) (string, error) { return "  ", nil }
	messages = engine.appendToolImages(t.Context(), engine.appendToolResults(nil, step), step)
	require.Contains(t, messages[1].Content, "cannot view")
}

func TestRetrievedPageImagesCarryCitationPagesToModel(t *testing.T) {
	source := types.PageImageSource{ID: "asset-private", KnowledgeID: "doc", KnowledgeBaseID: "kb", Title: "Report", Page: 42, ChunkIDs: []string{"chunk"}, SourceHash: "private-hash"}
	step := types.AgentStep{ToolCalls: []types.ToolCall{{ID: "search", Name: "search_knowledge", Result: &types.ToolResult{
		Success: true, Output: "Source text", Images: []string{"data:image/jpeg;base64,YQ=="}, Data: map[string]interface{}{"page_image_sources": []types.PageImageSource{source}},
	}}}}
	engine := &AgentEngine{config: &types.AgentConfig{ChatModelSupportsVision: true}}
	messages := engine.appendToolImages(t.Context(), engine.appendToolResults(nil, step), step)
	require.Equal(t, "tool", messages[len(messages)-2].Role)
	last := messages[len(messages)-1]
	require.Contains(t, last.Content, `"page":42`)
	require.Contains(t, last.Content, `"chunk_ids":["chunk"]`)
	require.NotContains(t, last.Content, "private-hash")
	require.NotContains(t, last.Content, "asset-private")
	require.Equal(t, step.ToolCalls[0].Result.Images, last.Images)
}
