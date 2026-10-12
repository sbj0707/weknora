import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, reactive } from 'vue'
import { useDocumentIndexStatus } from './useDocumentIndexStatus'
import type { KnowledgeIndexStatusResponse } from '../types/knowledgeIndexStatus'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function response(id: string, refreshing = false): KnowledgeIndexStatusResponse {
  return { success: true, data: { knowledge_id: id, attempt: 1, refreshing, indexes: [] } }
}

async function flush() { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }

test('late responses cannot replace the newly opened document', async t => {
  const props = reactive({ visible: true, id: 'first' })
  const requests: ReturnType<typeof deferred<KnowledgeIndexStatusResponse>>[] = []
  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() => useDocumentIndexStatus(() => props.visible, () => props.id, () => {
    const request = deferred<KnowledgeIndexStatusResponse>()
    requests.push(request)
    return request.promise
  }))!
  props.id = 'second'
  assert.equal(requests.length, 2)
  requests[1]!.resolve(response('second'))
  await flush()
  requests[0]!.resolve(response('first', true))
  await flush()
  assert.equal(state.report.value?.knowledge_id, 'second')
  assert.equal(state.loading.value, false)
})

test('closing a document clears its report and ignores unfinished requests', async t => {
  const props = reactive({ visible: true, id: 'doc' })
  const pending = deferred<KnowledgeIndexStatusResponse>()
  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() => useDocumentIndexStatus(() => props.visible, () => props.id, () => pending.promise))!
  props.visible = false
  pending.resolve(response('doc', true))
  await flush()
  assert.equal(state.report.value, null)
  assert.equal(state.loading.value, false)
})

test('only active builds poll and disposal cancels scheduled requests', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const props = reactive({ visible: true, id: 'doc' })
  let calls = 0
  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() => useDocumentIndexStatus(() => props.visible, () => props.id, async id => {
    calls++
    return response(id, calls === 1)
  }))!
  await flush()
  t.mock.timers.tick(5000)
  await flush()
  assert.equal(calls, 2)
  assert.equal(state.report.value?.refreshing, false)
  t.mock.timers.tick(10000)
  await flush()
  assert.equal(calls, 2)
  scope.stop()
  await state.refresh()
  assert.equal(calls, 2)
})

test('failed statistics do not retain a misleading successful report and can be retried', async t => {
  let calls = 0
  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() => useDocumentIndexStatus(() => true, () => 'doc', async () => {
    calls++
    if (calls === 2) throw new Error('temporarily unavailable')
    return response('doc')
  }))!
  await flush()
  assert.equal(state.report.value?.knowledge_id, 'doc')
  await state.refresh()
  assert.equal(state.failed.value, true)
  assert.equal(state.report.value === null, true)
  await state.refresh()
  assert.equal(state.failed.value, false)
  assert.equal(state.report.value?.knowledge_id, 'doc')
})

test('a response for a different document is treated as unavailable', async t => {
  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() => useDocumentIndexStatus(() => true, () => 'doc', async () => response('other')))!
  await flush()
  assert.equal(state.failed.value, true)
  assert.equal(state.report.value, null)
})
