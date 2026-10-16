package service

import (
	"github.com/Tencent/WeKnora/internal/types"
	"strings"
)

// The graph QA generator formats system-owned tag placeholders after appending
// business guidance. Keep user-authored percent signs literal through that pass.
func graphExtractionPromptDescription(description string, config *types.ExtractConfig) string {
	if config == nil {
		return description
	}
	instructions := config.CustomInstructions
	if len(config.Tags) > 0 {
		instructions = strings.ReplaceAll(instructions, "%", "%%")
	}
	return types.AppendCustomPromptInstructions(description, instructions, "graph_extraction")
}
