import { onScopeDispose, ref, watch, type Ref } from 'vue'
import type { ChatTraceResult } from '../api/chat-trace'

type Loader = (sessionId: string, messageId: string, signal: AbortSignal) => Promise<{ success: boolean; data: ChatTraceResult }>

export function useChatTrace(sessionId: Ref<string>, messageId: Ref<string>, loader: Loader) {
  const result = ref<ChatTraceResult | null>(null)
  const loading = ref(false)
  const failed = ref(false)
  const missing = ref(false)
  let controller: AbortController | undefined
  let revision = 0
  async function load() {
    const current = ++revision
    controller?.abort()
    controller = new AbortController()
    result.value = null
    loading.value = false
    failed.value = false
    missing.value = false
    if (!sessionId.value || !messageId.value) return
    loading.value = true
    try {
      const response = await loader(sessionId.value, messageId.value, controller.signal)
      if (revision !== current) return
      if (!response.success) throw new Error('trace load failed')
      result.value = response.data
    } catch (error: any) {
      if (revision !== current) return
      missing.value = (error?.$httpStatus ?? error?.status ?? error?.response?.status) === 404
      failed.value = !missing.value
    } finally {
      if (revision === current) loading.value = false
    }
  }
  watch([sessionId, messageId], load, { immediate: true })
  onScopeDispose(() => { revision++; controller?.abort() })
  return { result, loading, failed, missing, load }
}
