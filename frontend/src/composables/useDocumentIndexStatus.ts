import { onScopeDispose, ref, watch } from 'vue'
import type { KnowledgeIndexStatus, KnowledgeIndexStatusResponse } from '@/types/knowledgeIndexStatus'

export function useDocumentIndexStatus(
  visible: () => boolean,
  knowledgeId: () => string,
  load: (id: string) => Promise<KnowledgeIndexStatusResponse>,
) {
  const report = ref<KnowledgeIndexStatus | null>(null)
  const loading = ref(false)
  const failed = ref(false)
  let generation = 0
  let request = 0
  let disposed = false
  let timer: ReturnType<typeof setTimeout> | null = null

  function stopPolling() {
    if (timer !== null) clearTimeout(timer)
    timer = null
  }

  async function refresh() {
    if (disposed || !visible() || !knowledgeId()) return
    stopPolling()
    const currentGeneration = generation
    const currentRequest = ++request
    const id = knowledgeId()
    loading.value = true
    const isCurrent = () => !disposed && currentGeneration === generation &&
      currentRequest === request && visible() && knowledgeId() === id
    try {
      const response = await load(id)
      if (!isCurrent()) return
      if (!response.success || response.data?.knowledge_id !== id) throw new Error('Invalid index status response')
      report.value = response.data
      failed.value = false
    } catch {
      if (!isCurrent()) return
      report.value = null
      failed.value = true
    } finally {
      if (isCurrent()) {
        loading.value = false
        if (report.value?.refreshing) timer = setTimeout(() => void refresh(), 5000)
      }
    }
  }

  watch(() => [visible(), knowledgeId()] as const, () => {
    generation++
    stopPolling()
    report.value = null
    loading.value = false
    failed.value = false
    void refresh()
  }, { immediate: true, flush: 'sync' })

  onScopeDispose(() => {
    disposed = true
    generation++
    stopPolling()
  })

  return { report, loading, failed, refresh }
}
