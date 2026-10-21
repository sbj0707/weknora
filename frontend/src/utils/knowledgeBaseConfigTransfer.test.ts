import assert from 'node:assert/strict'
import test from 'node:test'
import {
  applyKnowledgeBaseConfig, exportKnowledgeBaseConfig, parseKnowledgeBaseConfig,
  knowledgeBaseConfigFilename, KnowledgeBaseConfigError, MAX_KB_CONFIG_BYTES,
} from './knowledgeBaseConfigTransfer.ts'

const source = {
  id: 'source-id', tenant_id: 9, creator_id: 'owner', name: '规范库', type: 'document',
  description: '业务规范', summary_model_id: 'chat', embedding_model_id: 'embedding',
  chunking_config: { chunk_size: 512, chunk_overlap: 0, separators: ['\n'],
    parser_engine_rules: [{ file_types: ['xlsx'], engine: 'builtin', xlsx_first_row_as_header: false }] },
  indexing_strategy: { vector_enabled: true, keyword_enabled: false, graph_enabled: true, wiki_enabled: false },
  extract_config: { enabled: true, nodes: [{ name: '项目', attributes: ['名称'] }],
    relations: [{ node1: '项目', node2: '单位', type: '建设' }], custom_instructions: '提取项目' },
  wiki_config: { synthesis_model_id: 'wiki', max_pages_per_ingest: 0, extraction_granularity: 'focused' },
  image_processing_config: { model_id: 'image', image_attrs_enabled: false,
    use_page_images_for_answer: true, auto_save_page_images: false,
    image_actions: { ocr: { on: [{ prop: 'contain.text', is: 'block' }], on_unobserved: false } } },
  storage_provider_config: { provider: 'cos', secret_key: 'DO_NOT_EXPORT' },
  storage_config: { secret_key: 'DO_NOT_EXPORT' }, vector_store_id: 'immutable-store',
  generated_profile: { gist: 'generated text' }, knowledge_count: 45,
}
const roundTrip = () => parseKnowledgeBaseConfig(JSON.stringify(exportKnowledgeBaseConfig(source)))

test('configuration round trips zeroes, false switches, parser rules, images and graph details', () => {
  const file = roundTrip()
  assert.deepEqual(file.configuration.chunking_config, source.chunking_config)
  assert.deepEqual(file.configuration.extract_config, source.extract_config)
  assert.deepEqual(file.configuration.image_processing_config, source.image_processing_config)
  assert.deepEqual(file.configuration.wiki_config, source.wiki_config)
})

test('export contains only editable settings and no identity, generated data or credentials', () => {
  const file = exportKnowledgeBaseConfig(source)
  assert.deepEqual(file.configuration.storage_provider_config, { provider: 'cos' })
  for (const key of ['id', 'tenant_id', 'creator_id', 'name', 'storage_config', 'vector_store_id', 'generated_profile', 'knowledge_count']) {
    assert.equal(Object.hasOwn(file.configuration, key), false, key)
  }
  assert.equal(JSON.stringify(file).includes('DO_NOT_EXPORT'), false)
})

test('legacy storage selection is portable without exporting connection credentials', () => {
  const file = exportKnowledgeBaseConfig({ type: 'document', storage_config: { provider: 'minio', secret_key: 'secret' } })
  assert.deepEqual(file.configuration.storage_provider_config, { provider: 'minio' })
  assert.equal(file.configuration.storage_backend_id, '')
  assert.equal(JSON.stringify(file).includes('secret'), false)
})

test('import preserves target identity, immutable bindings and omitted nested settings', () => {
  const current = { ...source, id: 'target', name: '目标', vector_store_id: 'target-store',
    chunking_config: { ...source.chunking_config, strategy: 'heading' } }
  const merged = applyKnowledgeBaseConfig(current, roundTrip(), false)
  assert.equal(merged.id, 'target')
  assert.equal(merged.name, '目标')
  assert.equal(merged.vector_store_id, 'target-store')
  assert.equal(merged.chunking_config.strategy, 'heading')
  assert.notEqual(merged.extract_config.nodes, source.extract_config.nodes)
  assert.deepEqual(current.chunking_config, { ...source.chunking_config, strategy: 'heading' })
})

test('existing documents retain the target embedding model and index switches', () => {
  const current = { ...source, embedding_model_id: 'locked-model', indexing_strategy: { vector_enabled: false, wiki_enabled: true } }
  const merged = applyKnowledgeBaseConfig(current, roundTrip(), true)
  assert.equal(merged.embedding_model_id, 'locked-model')
  assert.deepEqual(merged.indexing_strategy, current.indexing_strategy)
  assert.deepEqual(merged.chunking_config, source.chunking_config)
})

test('FAQ settings round trip and cannot be applied to a document knowledge base', () => {
  const file = exportKnowledgeBaseConfig({ type: 'faq', faq_config: { index_mode: 'question_answer', question_index_mode: 'combined' } })
  assert.deepEqual(parseKnowledgeBaseConfig(JSON.stringify(file)), file)
  assert.throws(() => applyKnowledgeBaseConfig(source, file, false), (error: unknown) => error instanceof KnowledgeBaseConfigError && error.code === 'type')
})

test('invalid files, versions, field types, unknown fields and prototype keys are rejected', () => {
  const valid = roundTrip()
  for (const value of [null, [], { ...valid, format: 'other' }, { ...valid, version: 2 }, { ...valid, type: 'unknown' },
    { ...valid, configuration: {} }, { ...valid, configuration: { chunking_config: { chunk_size: '512' } } },
    { ...valid, configuration: { chunking_config: { chunk_size: -1 } } },
    { ...valid, configuration: { extract_config: { nodes: 'wrong' } } },
    { ...valid, configuration: { storage_provider_config: { secret_key: 'secret' } } },
    { ...valid, configuration: JSON.parse('{"__proto__":{"polluted":true}}') },
  ]) assert.throws(() => parseKnowledgeBaseConfig(JSON.stringify(value)), KnowledgeBaseConfigError)
  assert.throws(() => parseKnowledgeBaseConfig('not json'), KnowledgeBaseConfigError)
  assert.equal(({} as Record<string, unknown>).polluted, undefined)
})

test('UTF-8 size limits, BOM input and portable download filenames are handled', () => {
  assert.deepEqual(parseKnowledgeBaseConfig('\uFEFF' + JSON.stringify(roundTrip())), roundTrip())
  assert.throws(() => parseKnowledgeBaseConfig('字'.repeat(MAX_KB_CONFIG_BYTES / 2)),
    (error: unknown) => error instanceof KnowledgeBaseConfigError && error.code === 'tooLarge')
  assert.equal(knowledgeBaseConfigFilename('业务/规范:库'), '业务_规范_库.config.json')
})
