import type { KnowledgeIndexBuildState, KnowledgeIndexKind } from './knowledgeIndexStatus'

export interface ChunkDetailContent {
  id: string
  knowledge_id: string
  knowledge_base_id: string
  content: string
  chunk_type: string
  chunk_index: number
  content_revision: number
  index_status: string
  is_enabled: boolean
  [key: string]: unknown
}
export interface ChunkRetrievalRecord {
  id: string
  store_type: string
  source_id: string
  chunk_id: string
  content: string
  dimension: number
  embedding?: number[]
  is_enabled: boolean
  updated_at: string
}
export interface ChunkGraphNode {
  id: string
  name: string
  labels: string[]
  attributes: Record<string, unknown>
  chunk_ids: string[]
}
export interface ChunkGraphRelation {
  id: string
  source: string
  target: string
  type: string
  attributes: Record<string, unknown>
}
export interface ChunkWikiPage {
  scope: 'chunk' | 'document'
  page: {
    id: string
    slug: string
    title: string
    content: string
    status: string
    chunk_refs: string[]
    source_refs: string[]
    [key: string]: unknown
  }
}
export interface ChunkQuestionDetail {
  id: string
  question: string
  source_id: string
  current: boolean
  vector_indexed: boolean | null
  keyword_indexed: boolean | null
}
export interface ChunkIndexDetail {
  kind: KnowledgeIndexKind
  enabled: boolean
  available: boolean
  status: KnowledgeIndexBuildState
  reason?: string
  stores?: string[]
  records?: ChunkRetrievalRecord[]
  graph?: { nodes: ChunkGraphNode[]; relations: ChunkGraphRelation[] }
  pages?: ChunkWikiPage[]
  questions?: ChunkQuestionDetail[]
}
export interface ChunkDetail {
  chunk: ChunkDetailContent
  source_content: string
  context_header: string
  knowledge_title: string
  model_id?: string
  model_name?: string
  refreshing: boolean
  indexes: ChunkIndexDetail[]
  children?: ChunkDetailContent[]
  children_available?: boolean
}
export interface ChunkDetailResponse { success: boolean; data: ChunkDetail }
