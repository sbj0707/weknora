<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import DocumentPreview from '@/components/document-preview.vue'
import ChunkDetailDrawer from '@/components/chunk-detail-drawer.vue'
import { getDocumentChunkDetail, getKnowledgeDetails, listDocumentChunks } from '@/api/knowledge-base'
import { useDocumentChunkRelations } from '@/composables/useDocumentChunkRelations'
import { chunkRelations, type SourceSelection } from '@/utils/documentChunkRelations'
import { parseSourceLocators, type SourceLocateRequest } from '@/utils/sourceLocator'
import { renderDocumentPreviewMarkdown } from '@/utils/documentPreviewMarkdown'
import { knowledgeBaseConfigFilename } from '@/utils/knowledgeBaseConfigTransfer'
import type { ChunkDetailContent } from '@/types/chunkDetail'

const route = useRoute(), router = useRouter(), { t } = useI18n()
const id = computed(() => String(route.params.knowledgeId || ''))
const kbId = computed(() => String(route.params.kbId || ''))
const state = useDocumentChunkRelations(() => id.value, () => kbId.value, {
  document: (id, signal) => getKnowledgeDetails(id, { signal }) as any,
  chunks: listDocumentChunks,
  detail: getDocumentChunkDetail,
})
const { document, chunks, loading, failed, exporting, exportFailed, exportDone, exportTotal } = state
const selection = ref<SourceSelection | null>(null)
const focusedChunkId = ref('')
const locate = ref<SourceLocateRequest | null>(null)
const openChunks = ref(new Set<string>())
const toolbar = ref<HTMLElement | null>(null)
const outline = ref<HTMLElement | null>(null)
const targetMissing = ref(false)
const locateMessage = ref('')
const requestedChunkId = computed(() => typeof route.query.chunk_id === 'string' ? route.query.chunk_id : '')
let locateToken = 0
const title = computed(() => document.value?.title || document.value?.file_name || id.value)
const relations = computed(() => chunkRelations(chunks.value, selection.value, document.value?.file_hash || ''))
const selectedChunks = computed(() => focusedChunkId.value
  ? chunks.value.filter(chunk => chunk.id === focusedChunkId.value)
  : selection.value ? chunks.value.filter(chunk => relations.value.has(chunk.id)) : [])
const unmapped = computed(() => chunks.value.filter(chunk => !parseSourceLocators(chunk.source_locators).some(locator => locator.mapping === 'exact')).length)
const selectionLabel = computed(() => {
  const value = selection.value
  if (!value) return t('knowledgeBase.sourceChunks.selectHint')
  if (value.type === 'pdf' || value.type === 'docx' && value.page) return t('knowledgeBase.sourceChunks.page', { page: value.page })
  if (value.type === 'slide') return t('knowledgeBase.sourceChunks.slide', { slide: value.slide })
  if (value.type === 'sheet') return `${value.sheet}${value.rowStart ? ` · ${value.rowStart}` : ''}`
  if (value.type === 'docx') return t('knowledgeBase.sourceChunks.blocks', { start: value.blockStart, end: value.blockEnd })
  return t('knowledgeBase.sourceChunks.selectedRegion')
})

watch([id, kbId], () => { selection.value = null; focusedChunkId.value = ''; locate.value = null; openChunks.value = new Set(); locateMessage.value = '' })
watch(requestedChunkId, () => { selection.value = null; focusedChunkId.value = ''; locate.value = null; locateMessage.value = '' })
watch([requestedChunkId, chunks, loading, document], () => {
  targetMissing.value = false
  if (loading.value || failed.value || !document.value || !requestedChunkId.value) return
  // Only use the verified list for this document; never fall back to a neighbour.
  if (!chunks.value.some(chunk => chunk.id === requestedChunkId.value)) {
    targetMissing.value = true
    selection.value = null; focusedChunkId.value = ''; locate.value = null; locateMessage.value = ''
    return
  }
  focusChunk(requestedChunkId.value)
}, { immediate: true, flush: 'post' })
watch(() => selectedChunks.value.map(chunk => chunk.id).join(','), () => {
  openChunks.value = new Set(selectedChunks.value.map(chunk => chunk.id))
})
function selectSource(value: SourceSelection) {
  selection.value = value; focusedChunkId.value = ''; locate.value = null; locateMessage.value = ''
}
function focusChunk(chunkId: string) {
  const chunk = chunks.value.find(chunk => chunk.id === chunkId)
  if (!chunk) return
  focusedChunkId.value = chunkId
  selection.value = null
  const locators = parseSourceLocators(chunk.source_locators)
  const unavailable = locators.some(loc => loc.source_hash && document.value?.file_hash && loc.source_hash !== document.value.file_hash) ||
    chunk.content_revision > 0 && !locators.length
  locate.value = { token: ++locateToken, locators: unavailable ? [] : locators, quotes: unavailable ? [] : [chunk.content],
    scope: unavailable ? '' : chunk.content, sourceMarkdown: chunk.content, unavailable }
  locateMessage.value = unavailable ? t('chat.referenceSourceStale') : t('chat.referenceSourceLocating')
  const locator = locators.find(loc => loc.mapping === 'exact')
  if (!unavailable && locator?.type === 'pdf') selection.value = { type: 'pdf', page: locator.page }
  void nextTick(() => {
    if (focusedChunkId.value !== chunkId) return
    const container = outline.value
    const active = container?.querySelector<HTMLElement>('.source-chunks__chunk-link.is-active')
    if (!container || !active) return
    const top = active.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop
    container.scrollTo({ top: Math.max(0, top - 12), behavior: 'smooth' })
  })
}
function onLocated(result: { found: boolean; precise: boolean; page?: number; granularity?: string; reason?: string }) {
  if (!focusedChunkId.value || !locate.value) return
  if (locate.value.unavailable) { locateMessage.value = t('chat.referenceSourceStale'); return }
  if (result.found && result.page) selection.value = { type: 'pdf', page: result.page }
  locateMessage.value = result.reason === 'ambiguous' ? t('chat.referenceSourceAmbiguous')
    : result.found && result.reason === 'partial' ? t('chat.referenceSourcePartial')
    : !result.found || result.reason ? t('chat.referenceSourceNotFound')
    : result.granularity === 'block' ? t('chat.referenceSourceBlock')
    : !result.precise && result.page ? t('chat.referenceSourceFoundPage', { page: result.page })
    : t('chat.referenceSourceExact')
}
function toggleChunk(chunkId: string, event: Event) {
  const open = new Set(openChunks.value)
  if ((event.target as HTMLDetailsElement).open) open.add(chunkId)
  else open.delete(chunkId)
  openChunks.value = open
}
function position(chunk: ChunkDetailContent) {
  const pages = [...new Set(parseSourceLocators(chunk.source_locators).flatMap(loc => loc.type === 'pdf' && loc.page ? [loc.page] : []))]
  return pages.length ? t('knowledgeBase.sourceChunks.pages', { pages: pages.join(', ') }) : ''
}
async function exportJson() {
  const result = await state.exportAll()
  if (!result) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2) + '\n'], { type: 'application/json' }))
  const link = window.document.createElement('a')
  link.href = url
  link.download = knowledgeBaseConfigFilename(title.value).replace(/\.config\.json$/, '.chunks.json')
  window.document.body.appendChild(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function back() {
  await router.push({ name: 'knowledgeBaseDetail', params: { kbId: kbId.value }, query: { knowledge_id: id.value } })
}
function refresh() { selection.value = null; focusedChunkId.value = ''; locate.value = null; locateMessage.value = ''; void state.load() }
</script>

<template>
  <main class="source-chunks">
    <header class="source-chunks__header">
      <t-button variant="text" shape="square" :title="t('common.back')" :aria-label="t('common.back')" @click="back"><t-icon name="chevron-left" /></t-button>
      <div class="source-chunks__heading"><h1>{{ title }}</h1><p>{{ t('knowledgeBase.sourceChunks.title') }}</p></div>
      <t-button variant="outline" :disabled="loading || exporting" @click="refresh"><t-icon name="refresh" />{{ t('common.refresh') }}</t-button>
      <t-button theme="primary" :loading="exporting" :disabled="loading || failed || !document" @click="exportJson">
        <t-icon name="download" />{{ t('knowledgeBase.sourceChunks.export') }}
        <span v-if="exporting">{{ exportDone }}/{{ exportTotal }}</span>
      </t-button>
    </header>
    <t-alert v-if="exportFailed" theme="error" :message="t('knowledgeBase.sourceChunks.exportFailed')" />
    <t-alert v-if="targetMissing" theme="warning" :message="t('knowledgeBase.sourceChunks.targetMissing')" />
    <div v-if="loading" class="source-chunks__state"><t-loading />{{ t('common.loading') }}</div>
    <div v-else-if="failed" class="source-chunks__state" role="alert">
      {{ t('knowledgeBase.sourceChunks.loadFailed') }}<t-button @click="refresh">{{ t('common.refresh') }}</t-button>
    </div>
    <div v-else-if="document" class="source-chunks__layout">
      <aside ref="outline" class="source-chunks__outline">
        <h2>{{ t('knowledgeBase.sourceChunks.allChunks', { count: chunks.length }) }}</h2>
        <p class="source-chunks__muted">{{ t('knowledgeBase.sourceChunks.selectHint') }}</p>
        <p v-if="unmapped" class="source-chunks__muted">{{ t('knowledgeBase.sourceChunks.unmapped', { count: unmapped }) }}</p>
        <div class="source-chunks__chunk-list">
          <button v-for="chunk in chunks" :key="chunk.id" class="source-chunks__chunk-link"
            :class="{ 'is-active': selectedChunks.some(item => item.id === chunk.id) }" @click="focusChunk(chunk.id)">
            <strong>#{{ chunk.chunk_index }} · {{ chunk.chunk_type }}</strong>
            <span>{{ position(chunk) || chunk.id }}</span>
            <span class="source-chunks__excerpt">{{ chunk.content }}</span>
          </button>
        </div>
      </aside>
      <section class="source-chunks__source">
        <header class="source-chunks__source-head"><h2>{{ t('knowledgeBase.sourceChunks.original') }}</h2><div ref="toolbar" /></header>
        <p v-if="locateMessage" role="status">{{ locateMessage }}</p>
        <DocumentPreview :knowledge-id="id" :file-type="document.file_type || ''" :file-name="document.file_name || title"
          active source-mode selectable page-navigation fill-height :toolbar-target="toolbar" :source-selection="selection" :locate="locate" @source-select="selectSource" @located="onLocated" />
      </section>
      <aside class="source-chunks__details" :aria-label="t('knowledgeBase.sourceChunks.related')">
        <header class="source-chunks__detail-head"><h2>{{ t('knowledgeBase.sourceChunks.related') }} · {{ selectedChunks.length }}</h2><p>{{ focusedChunkId ? t('knowledgeBase.sourceChunks.selectedChunk') : selectionLabel }}</p></header>
        <p v-if="!selection && !focusedChunkId" class="source-chunks__empty">{{ t('knowledgeBase.sourceChunks.selectHint') }}</p>
        <p v-else-if="!selectedChunks.length" class="source-chunks__empty">{{ t('knowledgeBase.sourceChunks.noMatches') }}</p>
        <details v-for="chunk in selectedChunks" :key="chunk.id" class="source-chunks__card" :open="openChunks.has(chunk.id)" @toggle="toggleChunk(chunk.id, $event)">
          <summary><strong>#{{ chunk.chunk_index }} · {{ chunk.chunk_type }}</strong>
            <t-tag v-if="relations.get(chunk.id)" size="small" variant="light">{{ t(`knowledgeBase.sourceChunks.match.${relations.get(chunk.id)}`) }}</t-tag>
          </summary>
          <ChunkDetailDrawer v-if="openChunks.has(chunk.id)" inline visible :knowledge-id="id" :chunk-id="chunk.id"
            :render-markdown="renderDocumentPreviewMarkdown" @navigate="focusChunk" />
        </details>
      </aside>
    </div>
  </main>
</template>

<style scoped lang="less">
.source-chunks { height: 100%; min-height: 0; display: flex; flex-direction: column; background: var(--td-bg-color-page); }
.source-chunks__header { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--td-component-stroke); background: var(--td-bg-color-container); }
.source-chunks__heading { flex: 1; min-width: 0; }
h1 { font-size: 18px; margin: 0 0 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
h2 { font-size: 14px; margin: 0; }
p { margin: 6px 0; font-size: 12px; color: var(--td-text-color-secondary); line-height: 1.6; }
.source-chunks__layout { flex: 1; min-height: 0; display: grid; grid-template-columns: 210px minmax(360px, 1fr) minmax(390px, 40%); }
.source-chunks__outline { min-height: 0; overflow: auto; padding: 16px 12px; border-right: 1px solid var(--td-component-stroke); }
.source-chunks__chunk-list { display: flex; flex-direction: column; gap: 8px; margin-top: 16px; }
.source-chunks__chunk-link { text-align: left; cursor: pointer; padding: 12px; display: flex; flex-direction: column; gap: 7px; border: 1px solid var(--td-component-stroke); border-radius: 8px; background: var(--td-bg-color-container); color: var(--td-text-color-primary); min-width: 0; }
.source-chunks__chunk-link.is-active { border-color: var(--td-brand-color); background: var(--td-brand-color-light); }
.source-chunks__chunk-link span { font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; color: var(--td-text-color-secondary); }
.source-chunks__source { display: flex; flex-direction: column; min-width: 0; min-height: 0; padding: 12px; }
.source-chunks__source-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 0 0 10px; }
.source-chunks__source > :deep(.document-preview) { flex: 1; min-height: 0; }
.source-chunks__details { overflow: auto; min-width: 0; border-left: 1px solid var(--td-component-stroke); padding: 16px; }
.source-chunks__detail-head { position: sticky; top: -16px; z-index: 1; background: var(--td-bg-color-page); padding: 0 0 10px; }
.source-chunks__card { padding: 14px; border: 1px solid var(--td-component-stroke); background: var(--td-bg-color-container); border-radius: 10px; margin: 0 0 12px; overflow-wrap: anywhere; }
.source-chunks__card > summary { cursor: pointer; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 13px; padding-bottom: 12px; }
.source-chunks__state { display: flex; align-items: center; justify-content: center; gap: 12px; flex: 1; }
.source-chunks__empty { padding: 30px 10px; text-align: center; }
@media (max-width: 1400px) { .source-chunks__layout { grid-template-columns: 170px minmax(260px, 1fr) minmax(320px, 40%); overflow-x: auto; } }
@media (max-width: 850px) { .source-chunks__layout { display: flex; flex-direction: column; overflow: auto; } .source-chunks__outline { flex: 0 0 180px; border-right: 0; border-bottom: 1px solid var(--td-component-stroke); } .source-chunks__source { flex: 0 0 60vh; } .source-chunks__details { flex-shrink: 0; overflow: visible; border-left: 0; border-top: 1px solid var(--td-component-stroke); } .source-chunks__header { flex-wrap: wrap; padding: 12px; } .source-chunks__heading { min-width: 120px; } }
</style>
