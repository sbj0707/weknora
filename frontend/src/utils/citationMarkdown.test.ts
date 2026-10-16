import assert from 'node:assert/strict'
import test from 'node:test'
import { preprocessCitationTags, resolveCitationChunkId } from './citationMarkdown.ts'
import { resolveReferenceSource } from './referenceSources.ts'

const refs = [
  { id: 'opening', knowledge_id: 'doc', knowledge_base_id: 'kb', knowledge_title: '规范.pdf', content: '开篇', source_locators: [{ type: 'pdf', page: 1 }] },
  { id: 'later', knowledge_id: 'doc', knowledge_base_id: 'kb', knowledge_title: '规范.pdf', content: '引用片段', source_locators: [{ type: 'pdf', page: 57 }] },
]

test('same-document numbered citations resolve to the cited chunk and its page', () => {
  for (const alias of ['2', 'DOC-2']) {
    const id = resolveCitationChunkId(alias, { doc: '规范.pdf', kbId: 'kb' }, refs)
    assert.equal(id, 'later')
    const source = resolveReferenceSource(refs, { chunkId: id })
    assert.equal(source?.content, '引用片段')
    assert.equal(source?.locators?.[0]?.page, 57)
    assert.match(preprocessCitationTags(`<kb doc="规范.pdf" chunk_id="${alias}" kb_id="kb" />`, refs), /data-chunk-id="later"/)
  }
})

test('real non-UUID chunk IDs survive repeated rendering and clicking', () => {
  assert.equal(resolveCitationChunkId('later', { doc: '规范.pdf' }, refs), 'later')
})

test('FAQ numbering is resolved within FAQ references before title fallback', () => {
  const faqRefs = refs.map(ref => ({ ...ref, chunk_type: 'faq' }))
  assert.equal(resolveCitationChunkId('FAQ-2', { doc: '规范.pdf' }, faqRefs), 'later')
})

test('numbered contexts retain their global position across knowledge bases', () => {
  const scoped = [{ ...refs[0]!, knowledge_base_id: 'other' }, refs[1]!]
  assert.equal(resolveCitationChunkId('2', { doc: '规范.pdf', kbId: 'kb' }, scoped), 'later')
})

test('title fallback respects knowledge base and refuses ambiguous chunks', () => {
  const scoped = [{ ...refs[0]!, knowledge_base_id: 'other' }, refs[1]!]
  assert.equal(resolveCitationChunkId('unknown', { doc: '规范.pdf', kbId: 'kb' }, scoped), 'later')
  assert.equal(resolveCitationChunkId('unknown', { doc: '规范.pdf' }, refs), 'unknown')
  assert.equal(resolveCitationChunkId('99', { doc: '规范.pdf' }, refs), '99')
})

test('a unique exact title is preferred to overlapping titles', () => {
  const overlap = [{ ...refs[0]!, knowledge_title: '规范.pdf 补充' }, refs[1]!]
  assert.equal(resolveCitationChunkId('unknown', { doc: '规范.pdf' }, overlap), 'later')
})

test('mixed FAQ and document context aliases use their own type order', () => {
  const mixed = [{ id: 'faq', chunk_type: 'faq' }, refs[0]!, refs[1]!]
  assert.equal(resolveCitationChunkId('DOC-2', { doc: '规范.pdf' }, mixed), 'later')
  assert.equal(resolveCitationChunkId('3', { doc: '规范.pdf' }, mixed), 'later')
})
