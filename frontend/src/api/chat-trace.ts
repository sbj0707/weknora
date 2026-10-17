import { get } from '@/utils/request'

export interface TraceObservation {
  id: string
  parentObservationId?: string
  name: string
  type: string
  startTime?: string
  endTime?: string
  completionStartTime?: string
  model?: string
  level?: string
  statusMessage?: string
  input?: unknown
  output?: unknown
  metadata?: Record<string, unknown>
  usageDetails?: Record<string, number>
}

export interface ChatTrace {
  id: string
  name: string
  sessionId: string
  timestamp: string
  latency: number
  input?: unknown
  output?: unknown
  metadata?: Record<string, unknown>
  observations: TraceObservation[]
}

export interface ChatTraceResult {
  state: 'ready' | 'disabled' | 'not_found'
  session_id: string
  message_id: string
  request_id: string
  trace: ChatTrace | null
}

export function getChatTrace(sessionId: string, messageId: string, signal?: AbortSignal) {
  return get<{ success: boolean; data: ChatTraceResult }>(
    `/api/v1/messages/${encodeURIComponent(sessionId)}/${encodeURIComponent(messageId)}/trace`, { signal },
  )
}
