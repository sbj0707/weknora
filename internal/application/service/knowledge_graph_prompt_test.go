package service

import (
	"context"
	chatpipeline "github.com/Tencent/WeKnora/internal/application/service/chat_pipeline"
	"github.com/Tencent/WeKnora/internal/types"
	"strings"
	"testing"
)

func TestGraphPromptCustomPercentSignsRemainLiteral(t *testing.T) {
	text := "Retain 10% growth, literal %s and %q, %% and 100%"
	for _, tags := range [][]string{nil, {"owns"}} {
		cfg := &types.ExtractConfig{CustomInstructions: text, Tags: tags}
		template := &types.PromptTemplateStructured{Description: graphExtractionPromptDescription("System tag placeholder: %s", cfg), Tags: tags}
		prompt := chatpipeline.NewQAPromptGenerator(chatpipeline.NewFormater(), template).System(context.Background())
		if !strings.Contains(prompt, text) || strings.Contains(prompt, "%!") {
			t.Fatalf("user instructions were formatted: %q", prompt)
		}
		if len(tags) > 0 && !strings.Contains(prompt, `System tag placeholder: ["owns"]`) {
			t.Fatalf("system tag placeholder was not rendered: %q", prompt)
		}
	}
}
