export type KnowledgeIndexKind = 'vector' | 'keywords' | 'graph' | 'wiki' | 'questions'
export type KnowledgeIndexBuildState = 'disabled' | 'waiting' | 'building' | 'completed' |
  'partial_failed' | 'failed' | 'stale' | 'unknown' | 'cancelled'

export interface KnowledgeIndexStatusRow {
  kind: KnowledgeIndexKind
  enabled: boolean
  status: KnowledgeIndexBuildState
  count: number | null
  indexed_count?: number | null
  relation_count?: number | null
  model_id?: string
  model_name?: string
  last_built_at?: string
  reason?: string
  error_message?: string
  pages?: { slug: string; title: string }[]
}

export interface KnowledgeIndexStatus {
  knowledge_id: string
  attempt: number
  refreshing: boolean
  last_built_at?: string
  indexes: KnowledgeIndexStatusRow[]
}

export interface KnowledgeIndexStatusResponse {
  success: boolean
  data: KnowledgeIndexStatus
}
