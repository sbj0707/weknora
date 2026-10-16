import assert from 'node:assert/strict'
import test from 'node:test'
import type { ChunkDetailContent } from '../types/chunkDetail'
import { chunkRelations, loadAllDocumentChunks, mapChunksWithLimit, verifyChunkDetail, buildDocumentChunksExport } from './documentChunkRelations.ts'

export function chunk(id: string, extra: Partial<ChunkDetailContent> = {}): ChunkDetailContent {
  return { id, knowledge_id: 'doc', knowledge_base_id: 'kb', content: `片段正文 ${id}`, chunk_type: 'text',
    chunk_index: 0, content_revision: 0, index_status: 'ready', is_enabled: true, updated_at: '2026-10-16', ...extra }
}

test('one page returns every mapped chunk, including cross-page parents and image children', () => {
  const chunks = [chunk('one', { source_locators: [{ type: 'pdf', mapping: 'exact', page: 2, source_hash: 'current' }] }),
    chunk('parent', { chunk_type: 'parent_text', source_locators: [{ type: 'pdf', mapping: 'exact', page: 1 }, { type: 'pdf', mapping: 'exact', page: 2 }] }),
    chunk('image', { chunk_type: 'image_ocr', parent_chunk_id: 'parent' }),
    chunk('other', { source_locators: [{ type: 'pdf', mapping: 'exact', page: 3 }] }),
    chunk('stale', { source_locators: [{ type: 'pdf', mapping: 'exact', page: 2, source_hash: 'old' }] })]
  assert.deepEqual([...chunkRelations(chunks, { type: 'pdf', page: 2 }, 'current')], [['one', 'locator'], ['parent', 'locator'], ['image', 'parent']])
})

test('sheet row ranges, Word body blocks and slides use their structural positions', () => {
  const sheet = chunk('sheet', { source_locators: [{ type: 'sheet', mapping: 'exact', sheet: '数据', row_start: 2, row_end: 5 }] })
  assert.equal(chunkRelations([sheet], { type: 'sheet', sheet: '数据', rowStart: 4 }).get('sheet'), 'locator')
  assert.equal(chunkRelations([sheet], { type: 'sheet', sheet: '其他', rowStart: 4 }).size, 0)
  const word = chunk('word', { source_locators: [{ type: 'docx', mapping: 'exact', block: 7 }] })
  assert.equal(chunkRelations([word], { type: 'docx', blockStart: 5, blockEnd: 8 }).get('word'), 'locator')
  const slide = chunk('slide', { source_locators: [{ type: 'slide', mapping: 'exact', slide: 3 }] })
  assert.equal(chunkRelations([slide], { type: 'slide', slide: 3 }).get('slide'), 'locator')
})

test('legacy text matches are candidates and edits cannot reuse their old source', () => {
  const content = '这是用于测试引用来源匹配的一段完整原文内容。'
  const chunks = [chunk('legacy', { content }), chunk('edited', { content, content_revision: 1 }),
    chunk('elsewhere', { content, source_locators: [{ type: 'pdf', mapping: 'exact', page: 9 }] })]
  assert.deepEqual([...chunkRelations(chunks, { type: 'pdf', page: 2, quote: content })], [['legacy', 'text']])
})

test('Word pagination does not associate shifted body ordinals with the wrong page', () => {
  const quote = '原文中这一段内容会因 Word 自动分页而改变渲染后的块编号。'
  const row = chunk('word', { source_locators: [{ type: 'docx', mapping: 'exact', block: 7, quote }] })
  assert.equal(chunkRelations([row], { type: 'docx', blockStart: 7, blockEnd: 9, quote: '这是另一页完全不同的内容，不应关联到原来的段落。' }).size, 0)
  assert.equal(chunkRelations([row], { type: 'docx', blockStart: 10, blockEnd: 12, quote }).get('word'), 'text')
  assert.equal(chunkRelations([row], { type: 'docx', blockStart: 5, blockEnd: 8, quote }).get('word'), 'locator')
})

test('rendered text can match chunks with source offsets without claiming a DOM offset mapping', () => {
  const content = '这是用于测试文本及 Markdown 渲染后位置匹配的完整内容。'
  const locators = [{ type: 'text', mapping: 'exact', start: 0, end: content.length, source_hash: 'hash' }]
  const chunks = [chunk('text', { content, source_locators: locators }),
    chunk('other', { content: '完全不同的另一个段落，不能因为位置类型相同而关联。', source_locators: locators })]
  assert.deepEqual([...chunkRelations(chunks, { type: 'text', quote: content }, 'hash')], [['text', 'text']])
  assert.equal(chunkRelations(chunks, { type: 'text', quote: content }, 'changed').size, 0)
})

test('all pages load without dropping disabled, summary or table chunks', async () => {
  const pages = [[chunk('text')], [chunk('summary', { chunk_type: 'summary', is_enabled: false }), chunk('table', { chunk_type: 'table_column' })]]
  const seen: number[] = []
  const rows = await loadAllDocumentChunks('doc', 'kb', async page => { seen.push(page); return { success: true, total: 3, data: pages[page - 1]! } })
  assert.deepEqual(seen, [1, 2]); assert.deepEqual(rows.map(row => row.id), ['text', 'summary', 'table'])
})

test('missing pages, duplicate IDs, changing totals and foreign-document chunks reject the entire list', async () => {
  for (const pages of [
    [{ success: true, total: 2, data: [chunk('a')] }, { success: true, total: 2, data: [] }],
    [{ success: true, total: 2, data: [chunk('a')] }, { success: true, total: 2, data: [chunk('a')] }],
    [{ success: true, total: 2, data: [chunk('a')] }, { success: true, total: 3, data: [chunk('b')] }],
    [{ success: true, total: 1, data: [chunk('foreign', { knowledge_id: 'other' })] }],
  ]) await assert.rejects(loadAllDocumentChunks('doc', 'kb', async page => pages[page - 1]!))
})

test('detail reads are bounded to four concurrent requests and preserve chunk order', async () => {
  let active = 0, peak = 0
  const chunks = Array.from({ length: 15 }, (_, i) => chunk(String(i)))
  const results = await mapChunksWithLimit(chunks, async row => {
    peak = Math.max(peak, ++active)
    await new Promise(resolve => setTimeout(resolve, Number(row.id) % 3))
    active--; return row.id
  })
  assert.equal(peak, 4); assert.deepEqual(results, chunks.map(row => row.id))
})

test('JSON export retains full content and vectors; changed details are rejected', () => {
  const base = chunk('a')
  const detail = { chunk: base, source_content: '解析原文', context_header: '# 章节', knowledge_title: '文件', refreshing: false,
    indexes: [{ kind: 'vector' as const, enabled: true, available: true, status: 'completed' as const,
      records: [{ id: 'v', store_type: 'sqlite', source_id: 'a', chunk_id: 'a', content: '# 章节\n片段', dimension: 2, embedding: [0.1, -0.2], is_enabled: true, updated_at: '' }] }] }
  const result = buildDocumentChunksExport({ id: 'doc', knowledge_base_id: 'kb' }, [verifyChunkDetail(base, detail)])
  assert.equal(result.chunk_count, 1)
  assert.deepEqual(JSON.parse(JSON.stringify(result)).chunks[0].indexes[0].records[0].embedding, [0.1, -0.2])
  for (const change of [{ content_revision: 2 }, { knowledge_id: 'foreign' }, { index_status: 'processing' }]) {
    assert.throws(() => verifyChunkDetail(base, { ...detail, chunk: { ...base, ...change } }))
  }
})
