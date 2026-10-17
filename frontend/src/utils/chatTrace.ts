import type { ChatTrace, TraceObservation } from '../api/chat-trace'

export function chatTraceLocation(sessionId?: string, messageId?: string) {
  if (!sessionId || !messageId || messageId === 'streaming' || messageId.startsWith('temp-')) return null
  return { name: 'chatTrace', params: { sessionId, messageId } }
}

export function traceTime(value?: string) {
  const time = Date.parse(value || '')
  return Number.isFinite(time) ? time : 0
}

export function observationDuration(observation: TraceObservation) {
  const start = traceTime(observation.startTime), end = traceTime(observation.endTime)
  return start && end ? Math.max(0, end - start) : 0
}

export function traceRows(observations: TraceObservation[]) {
  const ordered = [...observations].sort((a, b) => traceTime(a.startTime) - traceTime(b.startTime))
  const byId = new Map(ordered.map(item => [item.id, item]))
  const children = new Map<string, TraceObservation[]>()
  for (const item of byId.values()) {
    const parent = item.parentObservationId || ''
    children.set(parent, [...(children.get(parent) || []), item])
  }
  const visited = new Set<string>()
  const rows: { observation: TraceObservation; depth: number }[] = []
  function visit(item: TraceObservation, depth: number) {
    if (visited.has(item.id)) return
    visited.add(item.id)
    rows.push({ observation: item, depth })
    for (const child of children.get(item.id) || []) visit(child, depth + 1)
  }
  for (const item of byId.values()) {
    if (!item.parentObservationId || !byId.has(item.parentObservationId)) visit(item, 0)
  }
  // Partial ingestion may leave orphan nodes or invalid cycles. Keep them visible.
  for (const item of byId.values()) visit(item, 0)
  return rows
}

export function traceSummary(trace: ChatTrace) {
  const generations = trace.observations.filter(item => item.type === 'GENERATION')
  const number = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? value : 0
  return {
    calls: generations.length,
    input: generations.reduce((sum, item) => sum + number(item.usageDetails?.input), 0),
    output: generations.reduce((sum, item) => sum + number(item.usageDetails?.output), 0),
    cached: generations.reduce((sum, item) => sum + number(item.usageDetails?.cache_read_input_tokens), 0),
    errors: trace.observations.filter(item => item.level === 'ERROR').length,
  }
}

export function formatTraceValue(value: unknown) {
  if (value == null) return '—'
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}
