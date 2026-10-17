import assert from 'node:assert/strict'
import test from 'node:test'
import { chatTraceLocation, observationDuration, traceRows, traceSummary } from './chatTrace.ts'
import type { ChatTrace, TraceObservation } from '../api/chat-trace.ts'

const node = (id: string, parentObservationId = '', startTime = '2026-10-17T00:00:00Z'): TraceObservation =>
  ({ id, parentObservationId, name: id, type: 'SPAN', startTime, endTime: '2026-10-17T00:00:05Z' })

test('trace tree retains nested parallel calls, orphan nodes and cycles exactly once', () => {
  const rows = traceRows([node('child', 'root'), node('root'), node('other', 'root'), node('orphan', 'missing'), node('cycle-a', 'cycle-b'), node('cycle-b', 'cycle-a')])
  assert.deepEqual(rows.slice(0, 3).map(row => [row.observation.id, row.depth]), [['root', 0], ['child', 1], ['other', 1]])
  assert.equal(rows.length, 6)
  assert.equal(new Set(rows.map(row => row.observation.id)).size, 6)
})

test('tokens sum generations only and never count cached input twice', () => {
  const trace: ChatTrace = { id: 'trace', name: 'chat', sessionId: 'session', timestamp: '', latency: 5, observations: [
    { ...node('span'), usageDetails: { input: 5000 } },
    { ...node('generation'), type: 'GENERATION', usageDetails: { input: 1000, output: 50, cache_read_input_tokens: 600 } },
    { ...node('embedding'), type: 'GENERATION', usageDetails: { input: 25 }, level: 'ERROR' },
  ] }
  assert.deepEqual(traceSummary(trace), { calls: 2, input: 1025, output: 50, cached: 600, errors: 1 })
  assert.equal(observationDuration({ ...node('bad'), endTime: 'invalid' }), 0)
})

test('each message links to its own authenticated trace page', () => {
  assert.deepEqual(chatTraceLocation('session', 'message'), { name: 'chatTrace', params: { sessionId: 'session', messageId: 'message' } })
  assert.equal(chatTraceLocation('', 'message'), null)
  assert.equal(chatTraceLocation('session', 'temp-pending'), null)
})
