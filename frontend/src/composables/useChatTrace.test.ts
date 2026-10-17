import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, nextTick, ref } from 'vue'
import { useChatTrace } from './useChatTrace.ts'
import type { ChatTraceResult } from '../api/chat-trace.ts'

const value = (id: string): ChatTraceResult => ({ state: 'not_found', session_id: 'session', message_id: id, request_id: id, trace: null })
const flush = async () => { await nextTick(); await Promise.resolve(); await nextTick() }

test('changing messages clears previous trace and ignores out-of-order responses', async () => {
  const scope = effectScope(), session = ref('session'), message = ref('first')
  const pending: { signal: AbortSignal; resolve: (value: any) => void }[] = []
  const state = scope.run(() => useChatTrace(session, message, (_s, _m, signal) => new Promise(resolve => pending.push({ signal, resolve }))))!
  try {
    message.value = 'second'; await flush()
    assert.equal(pending[0]!.signal.aborted, true)
    pending[1]!.resolve({ success: true, data: value('second') }); await flush()
    pending[0]!.resolve({ success: true, data: value('first') }); await flush()
    assert.equal(state.result.value?.message_id, 'second')
    const refresh = state.load()
    assert.equal(state.result.value, null)
    pending[2]!.resolve({ success: true, data: value('second') }); await refresh
    assert.equal(state.loading.value, false)
  } finally { scope.stop() }
})

test('distinguishes inaccessible message from tracing outage and can retry', async () => {
  const scope = effectScope(); let status = 404
  const state = scope.run(() => useChatTrace(ref('session'), ref('message'), async () => { throw { $httpStatus: status } }))!
  try {
    await flush(); assert.equal(state.missing.value, true); assert.equal(state.failed.value, false)
    status = 503; await state.load(); assert.equal(state.missing.value, false); assert.equal(state.failed.value, true)
  } finally { scope.stop() }
})
