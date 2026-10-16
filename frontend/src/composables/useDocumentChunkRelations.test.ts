import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, reactive, nextTick } from 'vue'
import { useDocumentChunkRelations, type DocumentChunksApi } from './useDocumentChunkRelations.ts'
import type { ChunkDetailContent } from '../types/chunkDetail'

const row = (id: string, doc = 'doc'): ChunkDetailContent => ({ id, knowledge_id: doc, knowledge_base_id: 'kb', content: id,
  chunk_type: 'text', chunk_index: 0, is_enabled: true, content_revision: 0, index_status: 'ready' })
function api(): DocumentChunksApi {
  return {
    document: async id => ({ data: { id, knowledge_base_id: 'kb', file_hash: 'hash', title: '文档' } }),
    chunks: async id => ({ success: true, total: 2, data: [row('a', id), row('b', id)] }),
    detail: async (id, chunkId) => ({ success: true, data: { chunk: row(chunkId, id), source_content: 'original', context_header: '', knowledge_title: '文档', refreshing: false, indexes: [] } }),
  }
}
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); await nextTick() }
function setup(t: test.TestContext, client = api()) {
  const props = reactive({ id: 'doc' }), scope = effectScope(); t.after(() => scope.stop())
  const state = scope.run(() => useDocumentChunkRelations(() => props.id, () => 'kb', client))!
  return { props, state, scope }
}

test('export reads every detail and includes the complete document chunk count', async t => {
  const { state } = setup(t)
  await flush()
  const result = await state.exportAll()
  assert.equal(result?.chunk_count, 2)
  assert.equal(result?.chunks[0]?.source_content, 'original')
  assert.equal(state.exportDone.value, 2); assert.equal(state.exportFailed.value, false)
})

test('one unavailable detail prevents downloading a partial export', async t => {
  const client = api(); client.detail = async () => { throw new Error('unavailable') }
  const { state } = setup(t, client)
  await flush()
  assert.equal(await state.exportAll(), null)
  assert.equal(state.exportFailed.value, true)
})

test('changed content or source file during export rejects the resulting JSON', async t => {
  for (const change of ['chunk', 'file']) {
    const client = api(); let lists = 0, documents = 0
    client.chunks = async id => ({ success: true, total: 2, data: [row('a', id), { ...row('b', id), content: ++lists > 2 && change === 'chunk' ? 'changed' : 'b' }] })
    client.document = async id => ({ data: { id, knowledge_base_id: 'kb', file_hash: ++documents > 2 && change === 'file' ? 'changed' : 'hash' } })
    const { state } = setup(t, client); await flush()
    assert.equal(await state.exportAll(), null, change)
    assert.equal(state.exportFailed.value, true, change)
  }
})

test('changing documents aborts an export and discards late results', async t => {
  const client = api(); const pending: ((value: any) => void)[] = []; let signal: AbortSignal | undefined
  client.detail = async (_id, _chunk, requestSignal) => { signal = requestSignal; return new Promise(done => { pending.push(done) }) }
  const { state, props } = setup(t, client); await flush()
  const exporting = state.exportAll(); await flush()
  props.id = 'new'; await flush()
  assert.equal(signal?.aborted, true)
  pending.forEach(resolve => resolve({ success: false })); await flush()
  assert.equal(await exporting, null)
  assert.equal(state.document.value?.id, 'new')
  assert.equal(state.exportFailed.value, false)
})
