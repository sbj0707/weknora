package types

import "time"

// ChunkDetail exposes content and persisted index evidence for one authorized chunk.
type ChunkDetail struct {
	Chunk             *Chunk             `json:"chunk"`
	SourceContent     string             `json:"source_content"`
	ContextHeader     string             `json:"context_header"`
	KnowledgeTitle    string             `json:"knowledge_title"`
	ModelID           string             `json:"model_id,omitempty"`
	ModelName         string             `json:"model_name,omitempty"`
	Refreshing        bool               `json:"refreshing"`
	Indexes           []ChunkIndexDetail `json:"indexes"`
	Children          []*Chunk           `json:"children"`
	ChildrenAvailable bool               `json:"children_available"`
}

type ChunkIndexDetail struct {
	Kind      string                 `json:"kind"`
	Enabled   bool                   `json:"enabled"`
	Available bool                   `json:"available"`
	Status    string                 `json:"status"`
	Reason    string                 `json:"reason,omitempty"`
	Records   []ChunkRetrievalRecord `json:"records,omitempty"`
	Stores    []RetrieverEngineType  `json:"stores,omitempty"`
	Graph     *ChunkGraphDetail      `json:"graph,omitempty"`
	Pages     []ChunkWikiPage        `json:"pages,omitempty"`
	Questions []ChunkQuestionDetail  `json:"questions,omitempty"`
}

type ChunkRetrievalRecord struct {
	ID        string              `json:"id"`
	StoreType RetrieverEngineType `json:"store_type"`
	SourceID  string              `json:"source_id"`
	ChunkID   string              `json:"chunk_id"`
	Content   string              `json:"content"`
	Dimension int                 `json:"dimension"`
	Embedding []float32           `json:"embedding,omitempty"`
	IsEnabled bool                `json:"is_enabled"`
	UpdatedAt time.Time           `json:"updated_at"`
}

type ChunkGraphDetail struct {
	Nodes     []ChunkGraphNode     `json:"nodes"`
	Relations []ChunkGraphRelation `json:"relations"`
}

type ChunkGraphNode struct {
	ID         string         `json:"id"`
	Name       string         `json:"name"`
	Labels     []string       `json:"labels"`
	Attributes map[string]any `json:"attributes"`
	ChunkIDs   []string       `json:"chunk_ids"`
}

// Relations connect entities associated with the chunk; Neo4j does not store
// per-relation chunk provenance, so the UI must describe them as entity links.
type ChunkGraphRelation struct {
	ID         string         `json:"id"`
	Source     string         `json:"source"`
	Target     string         `json:"target"`
	Type       string         `json:"type"`
	Attributes map[string]any `json:"attributes"`
}

type ChunkWikiPage struct {
	Page  *WikiPage `json:"page"`
	Scope string    `json:"scope"` // chunk citation or document-level context
}

type ChunkQuestionDetail struct {
	ID             string `json:"id"`
	Question       string `json:"question"`
	SourceID       string `json:"source_id"`
	Current        bool   `json:"current"`
	VectorIndexed  *bool  `json:"vector_indexed"`
	KeywordIndexed *bool  `json:"keyword_indexed"`
}
