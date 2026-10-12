package types

import "time"

// KnowledgeIndexStatus describes persisted document indices, independently of
// the KB's current switches. Nil counts mean unavailable, never zero.
type KnowledgeIndexStatus struct {
	KnowledgeID string                    `json:"knowledge_id"`
	Attempt     int                       `json:"attempt"`
	Refreshing  bool                      `json:"refreshing"`
	LastBuiltAt *time.Time                `json:"last_built_at,omitempty"`
	Indexes     []KnowledgeIndexStatusRow `json:"indexes"`
}

type KnowledgeIndexStatusRow struct {
	Kind          string          `json:"kind"`
	Enabled       bool            `json:"enabled"`
	Status        string          `json:"status"`
	Count         *int64          `json:"count"`
	IndexedCount  *int64          `json:"indexed_count,omitempty"`
	RelationCount *int64          `json:"relation_count,omitempty"`
	ModelID       string          `json:"model_id,omitempty"`
	ModelName     string          `json:"model_name,omitempty"`
	LastBuiltAt   *time.Time      `json:"last_built_at,omitempty"`
	Reason        string          `json:"reason,omitempty"`
	ErrorMessage  string          `json:"error_message,omitempty"`
	Pages         []WikiIndexPage `json:"pages,omitempty"`
}

type WikiIndexPage struct {
	Slug  string `json:"slug"`
	Title string `json:"title"`
}

// KnowledgeRetrievalIndexCounts counts primary chunk entries separately from
// generated-question aliases, which share the same chunk_id.
type KnowledgeRetrievalIndexCounts struct {
	Chunks    int64 `json:"chunks"`
	Questions int64 `json:"questions"`
}

type KnowledgeGraphCounts struct {
	Entities  int64
	Relations int64
}
