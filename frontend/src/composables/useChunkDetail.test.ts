import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, reactive } from 'vue'
import { useChunkDetail } from './useChunkDetail'
import type { ChunkDetailResponse } from '../types/chunkDetail'

function deferred() {
  let resolve!: (value: ChunkDetailResponse) => void
  const promise = new Promise<ChunkDetailResponse>(res => { resolve = res })
  return { promise, resolve }
}
function response(id: string, refreshing = false, doc = 'doc'): ChunkDetailResponse {
  return { success: true, data: { chunk: { id, knowledge_id: doc, knowledge_base_id: 'kb', content: id,
    chunk_index: 0, chunk_type: 'text', content_revision: 0, is_enabled: true, index_status: 'ready' },
    source_content: id, context_header: '', knowledge_title: doc, refreshing, indexes: [] } }
}
async function flush() { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }

test('a late response cannot show indexes from the previous chunk', async t => {
  const props = reactive({ visible: true, id: 'first' })
  const requests: ReturnType<typeof deferred>[] = []
  const scope = effectScope(); t.after(() => scope.stop())
  const state = scope.run(() => useChunkDetail(() => props.visible, () => 'doc', () => props.id, () => {
    const request = deferred(); requests.push(request); return request.promise
  }))!
  props.id = 'second'
  requests[1]!.resolve(response('second')); await flush()
  requests[0]!.resolve(response('first', true)); await flush()
  assert.equal(state.detail.value?.chunk.id, 'second')
  assert.equal(state.loading.value, false)
})

test('closing clears the full content and rejects unfinished responses', async t => {
  const props = reactive({ visible: true })
  const pending = deferred(), scope = effectScope(); t.after(() => scope.stop())
  const state = scope.run(() => useChunkDetail(() => props.visible, () => 'doc', () => 'chunk', () => pending.promise))!
  props.visible = false; pending.resolve(response('chunk')); await flush()
  assert.equal(state.detail.value, null); assert.equal(state.loading.value, false)
})

test('responses must match both the document and the chunk', async t => {
  const scope = effectScope(); t.after(() => scope.stop())
  const state = scope.run(() => useChunkDetail(() => true, () => 'doc', () => 'chunk', async () => response('chunk', false, 'foreign')))!
  await flush(); assert.equal(state.failed.value, true); assert.equal(state.detail.value, null)
})

test('failed refreshes clear stale index data and allow a manual retry', async t => {
  let calls = 0
  const scope = effectScope(); t.after(() => scope.stop())
  const state = scope.run(() => useChunkDetail(() => true, () => 'doc', () => 'chunk', async () => {
    if (++calls === 2) throw new Error('Unavailable')
    return response('chunk')
  }))!
  await flush(); assert.equal(state.detail.value?.chunk.id, 'chunk')
  await state.refresh(); assert.equal(state.detail.value === null, true); assert.equal(state.failed.value, true)
  await state.refresh(); assert.equal(state.detail.value?.chunk.id, 'chunk'); assert.equal(state.failed.value, false)
})

test('polling ends after the build completes or the view is disposed', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let calls = 0
  const scope = effectScope(); t.after(() => scope.stop())
  const state = scope.run(() => useChunkDetail(() => true, () => 'doc', () => 'chunk', async () => response('chunk', ++calls === 1)))!
  await flush(); t.mock.timers.tick(5000); await flush()
  assert.equal(calls, 2); t.mock.timers.tick(10000); await flush(); assert.equal(calls, 2)
  scope.stop(); await state.refresh(); assert.equal(calls, 2)
})
