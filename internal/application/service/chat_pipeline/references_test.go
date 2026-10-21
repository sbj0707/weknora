package chatpipeline

import (
	"context"
	"strings"
	"testing"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/stretchr/testify/require"
)

func TestPrepareMessagesWithModelContextUsesChunkCentricContext(t *testing.T) {
	rendered := `<context id="1">first content</context><context id="2">second content</context>`
	manage := &types.ChatManage{
		PipelineRequest: types.PipelineRequest{
			Query: "question",
			SummaryConfig: types.SummaryConfig{
				Prompt: "system",
			},
		},
		PipelineState: types.PipelineState{
			RenderedContexts: rendered,
			UserContent:      "References:\n" + rendered + "\nQuestion: question",
			MergeResult: []*types.SearchResult{
				{ID: "chunk-1", KnowledgeID: "doc-1", KnowledgeBaseID: "kb-1", KnowledgeTitle: "Doc", ChunkIndex: 1, Content: "first content"},
				{ID: "chunk-2", KnowledgeID: "doc-1", KnowledgeBaseID: "kb-1", KnowledgeTitle: "Doc", ChunkIndex: 2, Content: "second content"},
			},
		},
	}

	messages, refs := prepareMessagesWithModelContext(context.Background(), manage)
	require.Len(t, messages, 2)
	require.Contains(t, messages[0].Content, "Source handling protocol")
	require.Contains(t, messages[1].Content, `<document id="d1" kb="b1" title="Doc">`)
	require.Contains(t, messages[1].Content, `<chunk id="c1" index="1" view="full">`)
	require.Contains(t, messages[1].Content, `<chunk id="c2" index="2" view="full">`)
	require.False(t, strings.Contains(messages[1].Content, "chunk-1"))
	require.Equal(t,
		`<kb doc="Doc" chunk_id="chunk-1" kb_id="kb-1" />`,
		refs.DecodeOutputText(`<ref id="c1"/>`),
	)
}

func TestPrepareMessagesWithModelContextReplacesSystemPromptContextAndHistoryCitations(t *testing.T) {
	rendered := `<context id="1">first content</context>`
	manage := &types.ChatManage{
		PipelineRequest: types.PipelineRequest{
			Query: "question",
			SummaryConfig: types.SummaryConfig{
				Prompt: `System references: {{contexts}}`,
			},
		},
		PipelineState: types.PipelineState{
			RenderedContexts: rendered,
			UserContent:      "Question: question",
			History: []*types.History{{
				Query:  "previous",
				Answer: `Previous <kb doc="Old" chunk_id="old-chunk" kb_id="old-kb" />`,
			}},
			MergeResult: []*types.SearchResult{{
				ID: "current-chunk", KnowledgeID: "current-doc", KnowledgeBaseID: "current-kb", KnowledgeTitle: "Current", Content: "first content",
			}},
		},
	}

	messages, refs := prepareMessagesWithModelContext(context.Background(), manage)
	messages = refs.EncodeMessages(messages)
	require.NotContains(t, messages[0].Content, rendered)
	require.Contains(t, messages[0].Content, `<chunk id="c1"`)
	require.Contains(t, messages[2].Content, `<ref id="c2"/>`)
	require.NotContains(t, messages[2].Content, "old-chunk")
}

func TestPrepareMessagesWithModelContextKeepsWebSeparateFromChunks(t *testing.T) {
	manage := &types.ChatManage{
		PipelineRequest: types.PipelineRequest{Query: "question", SummaryConfig: types.SummaryConfig{Prompt: "system"}},
		PipelineState: types.PipelineState{
			UserContent: "question",
			MergeResult: []*types.SearchResult{{
				ID:              "https://example.com/page",
				KnowledgeTitle:  "Example",
				Content:         "web content",
				ChunkType:       string(types.ChunkTypeWebSearch),
				KnowledgeSource: "web_search",
			}},
		},
	}

	messages, refs := prepareMessagesWithModelContext(context.Background(), manage)
	require.Contains(t, messages[1].Content, `<retrieval type="web" mode="search" trust="untrusted">`)
	require.Contains(t, messages[1].Content, `<page id="w1" title="Example">`)
	require.NotContains(t, messages[1].Content, `<chunk id="c1"`)
	require.Equal(t,
		`<web url="https://example.com/page" title="Example" />`,
		refs.DecodeOutputText(`<ref id="w1"/>`),
	)
}

func TestPrepareMessagesWithModelContextCompactsHistoryWithoutCurrentRetrieval(t *testing.T) {
	manage := &types.ChatManage{
		PipelineRequest: types.PipelineRequest{Query: "follow-up", SummaryConfig: types.SummaryConfig{Prompt: "system"}},
		PipelineState: types.PipelineState{
			UserContent: "follow-up",
			History: []*types.History{{
				Query:  "previous",
				Answer: `Previous <web url="https://example.com/old" title="Old" />`,
			}},
		},
	}

	messages, refs := prepareMessagesWithModelContext(context.Background(), manage)
	messages = refs.EncodeMessages(messages)
	require.Contains(t, messages[0].Content, "Source handling protocol")
	require.Contains(t, messages[2].Content, `<ref id="w1"/>`)
	require.NotContains(t, messages[2].Content, "https://example.com/old")
}

func TestPrepareMessagesWithModelContextSuppressesCitationsWhenDisabled(t *testing.T) {
	disabled := false
	manage := &types.ChatManage{
		PipelineRequest: types.PipelineRequest{
			Query:           "question",
			CitationEnabled: &disabled,
			SummaryConfig:   types.SummaryConfig{Prompt: "custom system prompt"},
		},
		PipelineState: types.PipelineState{
			UserContent: "question",
			MergeResult: []*types.SearchResult{{
				ID: "chunk-1", KnowledgeID: "doc-1", KnowledgeBaseID: "kb-1", KnowledgeTitle: "Doc", Content: "evidence",
			}},
		},
	}

	messages, refs := prepareMessagesWithModelContext(context.Background(), manage)
	require.Contains(t, messages[0].Content, "Source citations are disabled")
	require.Contains(t, messages[1].Content, `<chunk id="c1"`)
	require.NotContains(t, messages[1].Content, "chunk-1")
	require.Equal(t, "answer ", refs.DecodeOutputText(`answer <ref id="c1"/>`))
}

func TestPageImagePartsKeepRewrittenSourcesAndUserAttachments(t *testing.T) {
	manage := &types.ChatManage{
		PipelineRequest: types.PipelineRequest{ChatModelSupportsVision: true, Query: "question", Images: []string{"data:image/jpeg;base64,dXNlcg=="}, SummaryConfig: types.SummaryConfig{Prompt: "system"}},
		PipelineState: types.PipelineState{
			RenderedContexts: `<context id="1">source text</context>`, UserContent: `Question <context id="1">source text</context>`,
			MergeResult:       []*types.SearchResult{{ID: "chunk-real", KnowledgeID: "doc-real", KnowledgeBaseID: "kb-real", KnowledgeTitle: "Report", Content: "source text"}},
			PageImageEvidence: types.PageImageEvidence{Images: []string{"data:image/jpeg;base64,cGFnZQ=="}, Sources: []types.PageImageSource{{KnowledgeID: "doc-real", KnowledgeBaseID: "kb-real", Title: "Report", Page: 42, ChunkIDs: []string{"chunk-real"}}}},
		},
	}
	messages, registry := prepareMessagesWithModelContext(context.Background(), manage)
	messages = registry.EncodeMessages(messages)
	last := messages[len(messages)-1]
	require.Equal(t, last.Content, last.MultiContent[0].Text, "wire multimodal text must contain the same compact source handles")
	require.NotContains(t, last.MultiContent[0].Text, `<context id="1">`)
	require.Contains(t, last.MultiContent[0].Text, `<chunk id="c1"`)
	require.Len(t, last.MultiContent, 4)
	require.Equal(t, "data:image/jpeg;base64,dXNlcg==", last.MultiContent[1].ImageURL.URL)
	require.Equal(t, "data:image/jpeg;base64,cGFnZQ==", last.MultiContent[3].ImageURL.URL)
	require.Contains(t, last.MultiContent[2].Text, `"page":42`)
}
