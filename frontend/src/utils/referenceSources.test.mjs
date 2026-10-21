import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildReferenceSections,
  buildReferenceList,
  getDomainFromUrl,
  normalizeReferenceUrl,
  resolveReferenceHighlightKey,
  referenceNavigationChunkId,
  documentChunkRelationsRoute,
  attachToolPageImageEvidence,
  mergeDocumentReferences,
} from './referenceSources.ts'
import { createRouter, createMemoryHistory } from 'vue-router'

test('tool page images reach grouped document cards and remain available on replay', () => {
  const refs = mergeDocumentReferences([
    { id: 'chunk-1', knowledge_id: 'doc-1', knowledge_base_id: 'kb-1' },
    { id: 'chunk-2', knowledge_id: 'doc-1', knowledge_base_id: 'kb-1' },
  ])
  const evidence = { page_image_sources: [
    { id: 'image-1', page: 1, chunk_ids: ['chunk-2'], knowledge_id: 'doc-1', knowledge_base_id: 'kb-1' },
  ] }
  const original = structuredClone(refs)
  const enriched = attachToolPageImageEvidence(refs, evidence)
  assert.deepEqual(buildReferenceList(enriched)[0].pageImagePages, [1])
  assert.deepEqual(attachToolPageImageEvidence(enriched, evidence), enriched)
  assert.deepEqual(refs, original)
})

test('page images are limited to the matching document, knowledge base and retrieved chunks', () => {
  const refs = [
    { id: 'chunk-1', knowledge_id: 'doc-1', knowledge_base_id: 'kb-1' },
    { id: 'chunk-1', knowledge_id: 'doc-2', knowledge_base_id: 'kb-1' },
    { id: 'chunk-1', knowledge_id: 'doc-1', knowledge_base_id: 'kb-2' },
    { id: 'chunk-2', knowledge_id: 'doc-1', knowledge_base_id: 'kb-1' },
  ]
  const result = attachToolPageImageEvidence(refs, { page_image_sources: [
    { id: 'image-1', page: 1, chunk_ids: ['chunk-1'], knowledge_id: 'doc-1', knowledge_base_id: 'kb-1' },
  ] })
  assert.deepEqual(result.map(ref => ref.page_image_sources.length), [1, 0, 0, 0])
})

test('whole-document tool cards retain page images and textual fallback warnings', () => {
  const refs = [{ id: 'doc-1', knowledge_id: 'doc-1' }]
  const evidence = {
    page_image_sources: [{ id: 'image-1', page: 3, chunk_ids: ['chunk-1'], knowledge_id: 'doc-1' }],
    page_image_warnings: ['部分页面图片尚未就绪，本次使用其文本证据。'],
  }
  const [card] = buildReferenceList(attachToolPageImageEvidence(refs, evidence))
  assert.deepEqual(card.pageImagePages, [3])
  assert.deepEqual(card.pageImageWarnings, evidence.page_image_warnings)
  assert.equal(attachToolPageImageEvidence(refs, undefined), refs)
})

test('relationship links preserve the cited chunk after grouping and encode document scope', () => {
  const [item] = buildReferenceList([
    { id: 'summary', knowledge_id: 'doc/1', knowledge_base_id: 'kb 1', chunk_type: 'summary' },
    { id: 'chunk-1', knowledge_id: 'doc/1', knowledge_base_id: 'kb 1', source_locators: [{ type: 'pdf', page: 2 }] },
    { id: 'chunk/2?x=1', knowledge_id: 'doc/1', knowledge_base_id: 'kb 1' },
  ])
  const chunkId = referenceNavigationChunkId(item, 'chunk/2?x=1')
  assert.equal(chunkId, 'chunk/2?x=1')
  assert.equal(referenceNavigationChunkId(item, 'another-document-chunk'), 'chunk-1')
  const router = createRouter({ history: createMemoryHistory(), routes: [{ name: 'documentChunkRelations', path: '/platform/knowledge-bases/:kbId/documents/:knowledgeId/source-chunks', component: {} }] })
  const resolved = router.resolve(documentChunkRelationsRoute(item.knowledgeBaseId, item.knowledgeId, chunkId))
  assert.match(resolved.href, /kb%201\/documents\/doc%2F1\/source-chunks\?chunk_id=chunk\/2\?x=1$/)
  assert.equal(resolved.query.chunk_id, 'chunk/2?x=1')
  assert.equal(resolved.params.knowledgeId, 'doc/1')
  assert.equal(documentChunkRelationsRoute('', 'doc'), null)
  assert.equal(documentChunkRelationsRoute('kb', ''), null)
})

test('buildReferenceList separates web and document references', () => {
  const items = buildReferenceList([
    {
      id: 'https://example.com/a',
      chunk_type: 'web_search',
      knowledge_title: 'Example A',
      metadata: { url: 'https://example.com/a', snippet: 'snippet a' },
      content: 'Example A\n\nsnippet a',
    },
    {
      id: 'chunk-1',
      knowledge_id: 'doc-1',
      knowledge_title: 'Policy',
      content: 'refund rules',
    },
  ])

  assert.equal(items.length, 2)
  assert.equal(items[0].kind, 'web')
  assert.equal(items[0].domain, 'example.com')
  assert.equal(items[1].kind, 'document')
})

test('buildReferenceList aggregates chunks from the same document', () => {
  const items = buildReferenceList([
    {
      id: 'chunk-1',
      knowledge_id: 'doc-1',
      knowledge_title: 'Policy',
      content: 'refund rules',
    },
    {
      id: 'chunk-2',
      knowledge_id: 'doc-1',
      knowledge_title: 'Policy',
      content: 'shipping rules',
    },
  ])

  assert.equal(items.length, 1)
  assert.equal(items[0].key, 'doc:doc-1')
  assert.deepEqual(items[0].chunkIds, ['chunk-1', 'chunk-2'])
  assert.match(items[0].content || '', /refund rules/)
  assert.match(items[0].content || '', /shipping rules/)
})

test('buildReferenceList opens a document at its first chunk with positions', () => {
  const [item] = buildReferenceList([
    { id: 'summary', knowledge_id: 'k1', knowledge_title: 'Scan', chunk_type: 'summary', content: 'Summary' },
    { id: 'ocr-1', knowledge_id: 'k1', knowledge_title: 'Scan', chunk_type: 'image_ocr', content: 'Page 1', source_locators: [{ type: 'pdf', page: 1 }] },
  ])
  assert.equal(item.chunkId, 'summary')
  assert.equal(item.sourceChunkId, 'ocr-1')
})

test('buildReferenceSections keeps tool results in their own section', () => {
  const sections = buildReferenceSections([
    {
      id: 'mcp-result-1',
      chunk_type: 'tool_result',
      knowledge_title: 'MCP Search',
      content: 'tool output',
      metadata: { source: 'MCP service' },
    },
  ])

  assert.equal(sections.length, 1)
  assert.equal(sections[0].id, 'tools')
  assert.equal(sections[0].items[0].kind, 'tool')
  assert.equal(sections[0].items[0].content, 'tool output')
})

test('resolveReferenceHighlightKey matches web url', () => {
  const refs = [
    {
      id: 'https://news.example.com/post',
      chunk_type: 'web_search',
      metadata: { url: 'https://news.example.com/post/' },
    },
  ]
  const key = resolveReferenceHighlightKey(refs, {
    url: 'https://news.example.com/post',
  })
  assert.equal(key, 'web:https://news.example.com/post')
})

test('resolveReferenceHighlightKey matches any chunk merged into a document item', () => {
  const refs = [
    {
      id: 'chunk-1',
      chunk_ids: ['chunk-1', 'chunk-2'],
      knowledge_id: 'doc-1',
      knowledge_title: 'Policy',
    },
  ]

  assert.equal(
    resolveReferenceHighlightKey(refs, { chunkId: 'chunk-2' }),
    'doc:doc-1',
  )
})

test('resolveReferenceHighlightKey falls back to document title and knowledge base', () => {
  const refs = [
    {
      id: 'available-chunk',
      knowledge_id: 'doc-1',
      knowledge_title: 'Claude Sonnet 5.md',
      knowledge_base_id: 'kb-1',
    },
  ]

  assert.equal(
    resolveReferenceHighlightKey(refs, {
      chunkId: 'cited-chunk-missing-from-legacy-replay',
      documentTitle: 'Claude Sonnet 5.md',
      knowledgeBaseId: 'kb-1',
    }),
    'doc:doc-1',
  )
})

test('normalizeReferenceUrl trims trailing slash', () => {
  assert.equal(
    normalizeReferenceUrl('https://example.com/path/'),
    'https://example.com/path',
  )
})

test('buildReferenceList uses domain instead of raw url title', () => {
  const items = buildReferenceList([
    {
      id: 'http://bj.bendibao.com/xiuxian/202671/384250.shtm',
      chunk_type: 'web_search',
      knowledge_title: 'http://bj.bendibao.com/xiuxian/202671/384250.shtm',
      metadata: {
        url: 'http://bj.bendibao.com/xiuxian/202671/384250.shtm',
        snippet: '根据提供的网页内容...',
      },
      content: '根据提供的网页内容...',
    },
  ])

  assert.equal(items[0].title, 'bj.bendibao.com')
  assert.equal(items[0].domain, 'bj.bendibao.com')
})

test('buildReferenceList prefers metadata title for web references', () => {
  const items = buildReferenceList([
    {
      id: 'https://example.com/post',
      chunk_type: 'web_search',
      knowledge_title: 'https://example.com/post',
      metadata: {
        url: 'https://example.com/post',
        title: 'Example headline',
        snippet: 'snippet text',
      },
    },
  ])

  assert.equal(items[0].title, 'Example headline')
})

test('formatReferenceSnippet strips markdown noise from preview text', async () => {
  const { formatReferenceSnippet } = await import('./referenceSources.ts')
  assert.equal(
    formatReferenceSnippet('... [Free Online Lectures](https://example.com) **Everything I Know**'),
    'Free Online Lectures Everything I Know',
  )
  assert.equal(
    formatReferenceSnippet('![Logo](local://image.jpg) Summary text'),
    'Summary text',
  )
})

test('getDomainFromUrl strips www prefix', () => {
  assert.equal(getDomainFromUrl('https://www.example.com/x'), 'example.com')
})
