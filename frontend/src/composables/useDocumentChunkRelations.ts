import { onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { ChunkDetailContent, ChunkDetailResponse } from '../types/chunkDetail'
import {
  buildDocumentChunksExport, chunkListSignature, loadAllDocumentChunks, mapChunksWithLimit,
  verifyChunkDetail, type ChunkPage,
} from '../utils/documentChunkRelations'

type DocumentRecord = Record<string, any>
export interface DocumentChunksApi {
  document: (id: string, signal: AbortSignal) => Promise<{ data: DocumentRecord }>
  chunks: (id: string, page: number, signal: AbortSignal) => Promise<ChunkPage>
  detail: (id: string, chunkId: string, signal: AbortSignal) => Promise<ChunkDetailResponse>
}

export function useDocumentChunkRelations(knowledgeId: () => string, knowledgeBaseId: () => string, api: DocumentChunksApi) {
  const document = shallowRef<DocumentRecord | null>(null)
  const chunks = shallowRef<ChunkDetailContent[]>([])
  const loading = ref(false), failed = ref(false), exporting = ref(false), exportFailed = ref(false)
  const exportDone = ref(0), exportTotal = ref(0)
  let generation = 0, disposed = false
  let loadController: AbortController | undefined, exportController: AbortController | undefined
  const verifyDocument = (value: DocumentRecord, id: string, kb: string) => {
    if (!value || value.id !== id || value.knowledge_base_id !== kb) throw new Error('Invalid document scope')
    return value
  }

  async function load() {
    const version = ++generation, id = knowledgeId(), kb = knowledgeBaseId()
    loadController?.abort(); exportController?.abort()
    const controller = loadController = new AbortController()
    document.value = null; chunks.value = []; loading.value = true; failed.value = false
    exporting.value = false; exportFailed.value = false
    try {
      if (!id || !kb) throw new Error('Missing document')
      const [response, items] = await Promise.all([
        api.document(id, controller.signal),
        loadAllDocumentChunks(id, kb, page => api.chunks(id, page, controller.signal)),
      ])
      if (disposed || controller.signal.aborted || version !== generation) return
      document.value = verifyDocument(response.data, id, kb)
      chunks.value = items
    } catch {
      controller.abort()
      if (!disposed && version === generation) { failed.value = true; document.value = null; chunks.value = [] }
    } finally {
      if (!disposed && version === generation) loading.value = false
    }
  }

  async function exportAll() {
    if (disposed || exporting.value || !document.value) return null
    const version = generation, id = knowledgeId(), kb = knowledgeBaseId()
    const controller = exportController = new AbortController()
    const current = () => !disposed && !controller.signal.aborted && version === generation
    exporting.value = true; exportFailed.value = false; exportDone.value = 0; exportTotal.value = 0
    try {
      const response = await api.document(id, controller.signal)
      if (!current()) return null
      const source = verifyDocument(response.data, id, kb)
      const initial = await loadAllDocumentChunks(id, kb, page => api.chunks(id, page, controller.signal))
      if (!current()) return null
      exportTotal.value = initial.length
      const details = await mapChunksWithLimit(initial, async chunk => {
        if (!current()) throw new Error('Cancelled export')
        const result = await api.detail(id, chunk.id, controller.signal)
        if (!current() || !result.success) throw new Error('Incomplete export')
        return verifyChunkDetail(chunk, result.data)
      }, done => { if (current()) exportDone.value = done })
      const [finalChunks, finalDocument] = await Promise.all([
        loadAllDocumentChunks(id, kb, page => api.chunks(id, page, controller.signal)),
        api.document(id, controller.signal),
      ])
      if (!current()) return null
      verifyDocument(finalDocument.data, id, kb)
      if (chunkListSignature(initial) !== chunkListSignature(finalChunks) || source.file_hash !== finalDocument.data.file_hash) {
        throw new Error('Document changed during export')
      }
      const metadata: DocumentRecord = {}
      for (const key of ['id', 'knowledge_base_id', 'title', 'file_name', 'file_type', 'file_hash', 'parse_status', 'created_at', 'updated_at']) {
        if (source[key] !== undefined) metadata[key] = source[key]
      }
      return buildDocumentChunksExport(metadata, details)
    } catch {
      const active = current()
      controller.abort()
      if (active) exportFailed.value = true
      return null
    } finally {
      if (!disposed && version === generation) exporting.value = false
    }
  }

  watch(() => [knowledgeId(), knowledgeBaseId()], () => void load(), { immediate: true })
  onScopeDispose(() => { disposed = true; generation++; loadController?.abort(); exportController?.abort() })
  return { document, chunks, loading, failed, exporting, exportFailed, exportDone, exportTotal, load, exportAll }
}
