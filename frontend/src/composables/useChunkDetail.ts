import { onScopeDispose, ref, watch } from 'vue'
import type { ChunkDetail, ChunkDetailResponse } from '../types/chunkDetail'

export function useChunkDetail(
  visible: () => boolean,
  knowledgeId: () => string,
  chunkId: () => string,
  load: (knowledgeId: string, chunkId: string) => Promise<ChunkDetailResponse>,
) {
  const detail = ref<ChunkDetail | null>(null)
  const loading = ref(false)
  const failed = ref(false)
  let generation = 0
  let request = 0
  let disposed = false
  let timer: ReturnType<typeof setTimeout> | null = null
  function stopPolling() { if (timer !== null) clearTimeout(timer); timer = null }
  async function refresh() {
    if (disposed || !visible() || !knowledgeId() || !chunkId()) return
    stopPolling()
    const currentGeneration = generation
    const currentRequest = ++request
    const docID = knowledgeId(), id = chunkId()
    const isCurrent = () => !disposed && currentGeneration === generation && currentRequest === request &&
      visible() && knowledgeId() === docID && chunkId() === id
    loading.value = true
    try {
      const response = await load(docID, id)
      if (!isCurrent()) return
      if (!response.success || response.data?.chunk.id !== id || response.data.chunk.knowledge_id !== docID) {
        throw new Error('Invalid chunk detail response')
      }
      detail.value = response.data
      failed.value = false
    } catch {
      if (!isCurrent()) return
      detail.value = null
      failed.value = true
    } finally {
      if (isCurrent()) {
        loading.value = false
        if (detail.value?.refreshing) timer = setTimeout(() => void refresh(), 5000)
      }
    }
  }
  watch(() => [visible(), knowledgeId(), chunkId()] as const, () => {
    generation++
    stopPolling()
    detail.value = null
    failed.value = false
    loading.value = false
    void refresh()
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { disposed = true; generation++; stopPolling() })
  return { detail, loading, failed, refresh }
}
