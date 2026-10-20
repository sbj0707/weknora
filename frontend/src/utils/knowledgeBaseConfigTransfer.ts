const FORMAT = 'weknora.knowledge-base-config'
export const MAX_KB_CONFIG_BYTES = 1024 * 1024

type Schema = 'string' | 'boolean' | 'number' | { [key: string]: Schema } | readonly [Schema]
const strings = ['string'] as const
// Explicitly list editable settings. API responses also contain identity,
// credentials, counters, generated content and immutable store bindings.
const schema: Record<string, Schema> = {
  description: 'string',
  summary_model_id: 'string', embedding_model_id: 'string',
  chunking_config: {
    chunk_size: 'number', chunk_overlap: 'number', separators: strings,
    parser_engine_rules: [{ file_types: strings, engine: 'string', xlsx_first_row_as_header: 'boolean' }],
    enable_parent_child: 'boolean', parent_chunk_size: 'number', child_chunk_size: 'number',
    strategy: 'string', token_limit: 'number', languages: strings, table_metadata_instructions: 'string',
  },
  vlm_config: { enabled: 'boolean', model_id: 'string', description_language: 'string', custom_instructions: 'string' },
  asr_config: { enabled: 'boolean', model_id: 'string', language: 'string' },
  image_processing_config: {
    model_id: 'string', image_attrs_enabled: 'boolean',
    use_page_images_for_answer: 'boolean', auto_save_page_images: 'boolean',
    image_actions: { ocr: { on: [{ prop: 'string', is: 'string' }], on_unobserved: 'boolean' } },
  },
  storage_backend_id: 'string', storage_provider_config: { provider: 'string' },
  extract_config: {
    enabled: 'boolean', text: 'string', tags: strings,
    nodes: [{ name: 'string', attributes: strings }],
    relations: [{ node1: 'string', node2: 'string', type: 'string' }], custom_instructions: 'string',
  },
  faq_config: { index_mode: 'string', question_index_mode: 'string' },
  wiki_config: {
    synthesis_model_id: 'string', max_pages_per_ingest: 'number', extraction_granularity: 'string',
    content_instructions: 'string', extraction_instructions: 'string',
  },
  question_generation_config: { enabled: 'boolean', question_count: 'number', custom_instructions: 'string' },
  auto_tag_config: { enabled: 'boolean', model_id: 'string', max_tags: 'number', skip_if_tagged: 'boolean' },
  profile_config: { enabled: 'boolean', model_id: 'string', custom_instructions: 'string' },
  indexing_strategy: { vector_enabled: 'boolean', keyword_enabled: 'boolean', wiki_enabled: 'boolean', graph_enabled: 'boolean' },
}

export interface KnowledgeBaseConfigFile {
  format: typeof FORMAT
  version: 1
  type: 'document' | 'faq'
  source_name: string
  configuration: Record<string, any>
}

export class KnowledgeBaseConfigError extends Error {
  constructor(public code: 'invalid' | 'version' | 'type' | 'empty' | 'tooLarge', public field = '') {
    super(code)
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function project(value: unknown, shape: Schema, field: string, importing: boolean): any {
  if (typeof shape === 'string') {
    if (typeof value !== shape || (shape === 'number' && (!Number.isSafeInteger(value) || (value as number) < 0))) {
      throw new KnowledgeBaseConfigError('invalid', field)
    }
    return value
  }
  if (Array.isArray(shape)) {
    if (!Array.isArray(value)) throw new KnowledgeBaseConfigError('invalid', field)
    return value.map((item, index) => project(item, shape[0]!, `${field}[${index}]`, importing))
  }
  if (!isObject(value)) throw new KnowledgeBaseConfigError('invalid', field)
  const result: Record<string, unknown> = {}
  for (const [key, child] of Object.entries(shape)) {
    if (!Object.hasOwn(value, key) || value[key] == null) continue
    result[key] = project(value[key], child, field ? `${field}.${key}` : key, importing)
  }
  if (importing && Object.keys(value).some(key => !Object.hasOwn(shape, key))) {
    throw new KnowledgeBaseConfigError('invalid', field)
  }
  return result
}

export function exportKnowledgeBaseConfig(kb: Record<string, any>): KnowledgeBaseConfigFile {
  const type = kb.type || 'document'
  if (type !== 'document' && type !== 'faq') throw new KnowledgeBaseConfigError('type')
  return {
    format: FORMAT, version: 1, type, source_name: String(kb.name || ''),
    configuration: project({
      ...kb,
      storage_backend_id: kb.storage_backend_id || '',
      storage_provider_config: { provider: kb.storage_provider_config?.provider || kb.storage_config?.provider || 'local' },
    }, schema, '', false),
  }
}

export function parseKnowledgeBaseConfig(text: string): KnowledgeBaseConfigFile {
  if (new TextEncoder().encode(text).byteLength > MAX_KB_CONFIG_BYTES) throw new KnowledgeBaseConfigError('tooLarge')
  let value: unknown
  try { value = JSON.parse(text.replace(/^\uFEFF/, '')) } catch { throw new KnowledgeBaseConfigError('invalid') }
  if (!isObject(value) || value.format !== FORMAT) throw new KnowledgeBaseConfigError('invalid')
  if (value.version !== 1) throw new KnowledgeBaseConfigError('version')
  if (value.type !== 'document' && value.type !== 'faq') throw new KnowledgeBaseConfigError('type')
  const configuration = project(value.configuration, schema, '', true)
  if (!Object.keys(configuration).length) throw new KnowledgeBaseConfigError('empty')
  return { format: FORMAT, version: 1, type: value.type, source_name: String(value.source_name || ''), configuration }
}

/** Merge partial settings without replacing omitted nested fields or arrays. */
function mergeSettings(current: Record<string, any>, changes: Record<string, any>): Record<string, any> {
  const merged = { ...current }
  for (const [key, value] of Object.entries(changes)) {
    merged[key] = isObject(value) ? mergeSettings(isObject(current[key]) ? current[key] : {}, value) : structuredClone(value)
  }
  return merged
}

export function applyKnowledgeBaseConfig(current: Record<string, any>, file: KnowledgeBaseConfigFile, hasFiles: boolean) {
  if ((current.type || 'document') !== file.type) throw new KnowledgeBaseConfigError('type')
  const merged = mergeSettings(current, project(file.configuration, schema, '', true))
  // Import must observe the same locks as editing settings manually.
  if (hasFiles) {
    merged.embedding_model_id = current.embedding_model_id
    merged.indexing_strategy = current.indexing_strategy
  }
  return merged
}

export function knowledgeBaseConfigFilename(name: string) {
  return `${name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').slice(0, 100) || 'knowledge-base'}.config.json`
}
