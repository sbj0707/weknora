package types

import (
	"sync"
	"time"
)

const TypeDocumentPageImages = "document:page_images"
const PageImageRenderProfile = "pdf-jpeg-2000-q85-v1"

type DocumentPageImage struct {
	ID              string     `json:"id" gorm:"primaryKey"`
	TenantID        uint64     `json:"tenant_id"`
	KnowledgeBaseID string     `json:"knowledge_base_id"`
	KnowledgeID     string     `json:"knowledge_id"`
	SourceHash      string     `json:"source_hash"`
	PageNumber      int        `json:"page_number"`
	StorageKey      string     `json:"-"`
	MimeType        string     `json:"mime_type"`
	Width           int        `json:"width"`
	Height          int        `json:"height"`
	Size            int64      `json:"size"`
	RenderProfile   string     `json:"render_profile"`
	Status          string     `json:"status"`
	ErrorMessage    string     `json:"error_message,omitempty"`
	TaskToken       string     `json:"-"`
	LeaseUntil      *time.Time `json:"-"`
	CreatedAt       time.Time  `json:"created_at"`
	UpdatedAt       time.Time  `json:"updated_at"`
}

type ChunkPageImageLink struct {
	ID              string    `json:"id" gorm:"primaryKey"`
	TenantID        uint64    `json:"tenant_id"`
	KnowledgeBaseID string    `json:"knowledge_base_id"`
	KnowledgeID     string    `json:"knowledge_id"`
	ChunkID         string    `json:"chunk_id"`
	ContentRevision int       `json:"content_revision"`
	SourceHash      string    `json:"source_hash"`
	PageNumber      int       `json:"page_number"`
	CreatedBy       string    `json:"created_by"`
	CreatedAt       time.Time `json:"created_at"`
}

type PageImageRequest struct {
	ChunkID         string `json:"chunk_id,omitempty"`
	ContentRevision *int   `json:"content_revision,omitempty"`
	SourceHash      string `json:"source_hash"`
	Pages           []int  `json:"pages,omitempty"`
	Manual          bool   `json:"manual,omitempty"`
	Force           bool   `json:"force,omitempty"`
}

type PageImageState struct {
	Enabled    bool                `json:"enabled"`
	Supported  bool                `json:"supported"`
	SourceHash string              `json:"source_hash"`
	Pages      []int               `json:"pages"`
	Manual     bool                `json:"manual"`
	Images     []DocumentPageImage `json:"images"`
}

type PageImageSource struct {
	ID              string   `json:"id"`
	KnowledgeBaseID string   `json:"knowledge_base_id"`
	KnowledgeID     string   `json:"knowledge_id"`
	Title           string   `json:"title"`
	Page            int      `json:"page"`
	ChunkIDs        []string `json:"chunk_ids"`
	SourceHash      string   `json:"source_hash"`
	Size            int64    `json:"size"`
}

type PageImageEvidence struct {
	Images   []string          `json:"-"`
	Sources  []PageImageSource `json:"sources,omitempty"`
	Warnings []string          `json:"warnings,omitempty"`
}

// Shared by all retrieval calls of one answer, including parallel Agent tools.
type PageImageBudget struct {
	Mu             sync.Mutex
	Reserved       bool
	Seen           map[string]bool
	Count          int
	Bytes          int64
	MaxImages      int
	MaxBytes       int64
	SupportsVision bool
}
