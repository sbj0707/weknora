<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { getDocumentChunkDetail } from '@/api/knowledge-base'
import { useChunkDetail } from '@/composables/useChunkDetail'
import { hydrateProtectedFileImages } from '@/utils/security'
import mermaid from 'mermaid'
import type { ChunkIndexDetail } from '@/types/chunkDetail'
import type { KnowledgeIndexBuildState, KnowledgeIndexKind } from '@/types/knowledgeIndexStatus'

const props = defineProps<{
  visible: boolean; knowledgeId: string; chunkId: string; revision?: number
  renderMarkdown: (value: string) => string
}>()
const emit = defineEmits<{ close: []; navigate: [id: string] }>()
const { t, locale } = useI18n()
const router = useRouter()
const { detail, loading, failed, refresh } = useChunkDetail(
  () => props.visible, () => props.knowledgeId, () => props.chunkId, getDocumentChunkDetail,
)
const fullscreen = ref(false)
const activeTab = ref<'content' | 'metadata' | KnowledgeIndexKind>('content')
const rawContent = ref(false)
const contentRoot = ref<HTMLElement | null>(null)
const kinds: KnowledgeIndexKind[] = ['vector', 'keywords', 'graph', 'wiki', 'questions']
const indexes = computed<ChunkIndexDetail[]>(() => kinds.map(kind => detail.value?.indexes.find(row => row.kind === kind) ||
  { kind, available: false, enabled: false, status: 'unknown' }))
const activeIndex = computed(() => indexes.value.find(row => row.kind === activeTab.value))
const metadata = computed(() => {
  if (!detail.value) return []
  return Object.entries(detail.value.chunk).filter(([key]) => key !== 'content')
})
const navigation = computed(() => {
  const chunk = detail.value?.chunk
  if (!chunk) return []
  return ['pre_chunk_id', 'parent_chunk_id', 'next_chunk_id'].flatMap(key =>
    typeof chunk[key] === 'string' && chunk[key] ? [{ key, id: chunk[key] as string }] : [])
})
watch(() => [props.visible, props.knowledgeId, props.chunkId], () => {
  fullscreen.value = false
  activeTab.value = 'content'
  rawContent.value = false
})
watch(() => [props.knowledgeId, props.chunkId, props.revision] as const,
  ([docID, id, revision], [previousDocID, previousID, previousRevision]) => {
    if (docID === previousDocID && id === previousID && revision !== previousRevision) void refresh()
  })
watch([detail, activeTab, rawContent], async () => {
  await nextTick()
  const root = contentRoot.value
  if (props.visible && root) {
    const diagrams = root.querySelectorAll<HTMLElement>('.mermaid:not([data-processed])')
    if (diagrams.length) {
      try { await mermaid.run({ nodes: diagrams }) } catch { /* The source view preserves invalid diagrams. */ }
    }
  }
  if (props.visible && detail.value) await hydrateProtectedFileImages(contentRoot.value,
    { mode: 'knowledgeBase', kbId: detail.value.chunk.knowledge_base_id })
}, { flush: 'post' })

function pretty(value: unknown) {
  if (value == null || value === '') return '—'
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
  }
  return typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)
}
function time(value?: string) {
  if (!value) return '—'
  return new Date(value).toLocaleString(locale.value)
}
function theme(status: KnowledgeIndexBuildState) {
  if (status === 'completed') return 'success'
  if (status === 'failed') return 'danger'
  if (['partial_failed', 'stale', 'cancelled'].includes(status)) return 'warning'
  if (['building', 'waiting'].includes(status)) return 'primary'
  return 'default'
}
function indexedText(value: boolean | null, kind: 'vector' | 'keywords') {
  if (!detail.value?.indexes.find(row => row.kind === kind)?.enabled) return t('knowledgeBase.indexBuild.states.disabled')
  return value == null ? t('knowledgeBase.indexBuild.states.unknown') : t(`knowledgeBase.chunkDetail.${value ? 'indexed' : 'notIndexed'}`)
}
function wikiHref(slug: string) {
  return router.resolve({ path: `/platform/knowledge-bases/${detail.value?.chunk.knowledge_base_id}`, query: { tab: 'wiki', slug } }).href
}
</script>

<template>
  <t-drawer :visible="visible" :size="fullscreen ? '100vw' : 'min(960px, 100vw)'" :z-index="2200"
    attach="body" :footer="false" :destroy-on-close="true" class="chunk-detail-drawer" @close="emit('close')">
    <template #header>
      <div class="detail-header">
        <div class="detail-heading">
          <strong>{{ t('knowledgeBase.chunkDetail.title') }}</strong>
          <span>{{ detail?.knowledge_title || knowledgeId }} · {{ chunkId }}</span>
        </div>
        <t-button variant="text" shape="square" :loading="loading" :title="t('common.refresh')"
          :aria-label="t('common.refresh')" @click="refresh"><template #icon><t-icon name="refresh" /></template></t-button>
        <t-button variant="text" shape="square" :title="t(fullscreen ? 'common.exitFullscreen' : 'common.fullscreen')"
          :aria-label="t(fullscreen ? 'common.exitFullscreen' : 'common.fullscreen')" :aria-pressed="fullscreen"
          @click="fullscreen = !fullscreen"><template #icon><t-icon :name="fullscreen ? 'fullscreen-exit' : 'fullscreen'" /></template></t-button>
      </div>
    </template>
    <div class="chunk-detail-body" :aria-busy="loading">
      <div v-if="loading && !detail" class="detail-state"><t-loading size="small" />{{ t('common.loading') }}</div>
      <div v-else-if="failed" class="detail-state" role="alert">
        {{ t('knowledgeBase.chunkDetail.loadFailed') }}
        <t-button size="small" @click="refresh">{{ t('common.refresh') }}</t-button>
      </div>
      <template v-else-if="detail">
        <nav class="detail-tabs" :aria-label="t('knowledgeBase.chunkDetail.title')">
          <button v-for="tab in ['content', 'metadata', ...kinds] as const" :key="tab" type="button"
            :class="{ active: activeTab === tab }" :aria-pressed="activeTab === tab" @click="activeTab = tab">
            {{ t(tab === 'content' || tab === 'metadata' ? `knowledgeBase.chunkDetail.${tab}` : `knowledgeBase.indexBuild.kinds.${tab}`) }}
            <t-icon v-if="indexes.find(row => row.kind === tab)?.status === 'failed'" name="error-circle" size="13px" />
          </button>
        </nav>
        <div ref="contentRoot" class="detail-panel">
          <template v-if="activeTab === 'content'">
            <div class="section-head">
              <h4>{{ t('knowledgeBase.chunkDetail.content') }}</h4>
              <t-button size="small" variant="outline" @click="rawContent = !rawContent">
                {{ t(`knowledgeBase.chunkDetail.${rawContent ? 'rendered' : 'raw'}`) }}
              </t-button>
            </div>
            <pre v-if="rawContent" class="detail-text">{{ detail.chunk.content }}</pre>
            <div v-else class="md-content" v-html="renderMarkdown(detail.chunk.content)"></div>
            <details v-if="detail.context_header" class="detail-card">
              <summary>{{ t('knowledgeBase.chunkDetail.contextHeader') }}</summary>
              <pre class="detail-text">{{ detail.context_header }}</pre>
            </details>
            <details class="detail-card">
              <summary>{{ t('knowledgeBase.chunkDetail.sourceContent') }}</summary>
              <pre v-if="detail.source_content" class="detail-text">{{ detail.source_content }}</pre>
              <p v-else class="detail-muted">{{ t('knowledgeBase.chunkDetail.sourceMissing') }}</p>
            </details>
            <div v-if="navigation.length" class="chunk-navigation">
              <t-button v-for="link in navigation" :key="link.key" size="small" variant="outline" @click="emit('navigate', link.id)">
                {{ t(`knowledgeBase.chunkDetail.fields.${link.key}`) }}
              </t-button>
            </div>
            <template v-if="detail.children?.length">
              <h5>{{ t('knowledgeBase.chunkDetail.children') }} · {{ detail.children.length }}</h5>
              <p class="detail-muted">{{ t('knowledgeBase.chunkDetail.childrenHint') }}</p>
              <article v-for="child in detail.children" :key="child.id" class="detail-card">
                <div class="record-heading"><strong>{{ child.chunk_type }}</strong>
                  <t-button size="small" variant="outline" @click="emit('navigate', child.id)">{{ t('knowledgeBase.chunkDetail.open') }}</t-button>
                </div>
                <small class="detail-muted">{{ child.id }}</small>
                <pre class="detail-text">{{ child.content }}</pre>
              </article>
            </template>
            <p v-else-if="detail.children_available === false" class="detail-muted">{{ t('knowledgeBase.chunkDetail.childrenUnavailable') }}</p>
          </template>
          <template v-else-if="activeTab === 'metadata'">
            <h4>{{ t('knowledgeBase.chunkDetail.metadata') }}</h4>
            <dl class="detail-metadata">
              <template v-for="[key, value] in metadata" :key="key">
                <dt>{{ key }}</dt><dd><pre>{{ pretty(value) }}</pre></dd>
              </template>
            </dl>
          </template>
          <template v-else-if="activeIndex">
            <div class="section-head">
              <h4>{{ t(`knowledgeBase.indexBuild.kinds.${activeIndex.kind}`) }}</h4>
              <t-tag :theme="theme(activeIndex.status)" variant="light">{{ t(`knowledgeBase.indexBuild.states.${activeIndex.status}`) }}</t-tag>
              <span v-if="!activeIndex.enabled && activeIndex.status !== 'disabled'" class="detail-muted">{{ t('knowledgeBase.indexBuild.currentlyDisabled') }}</span>
            </div>
            <p v-if="activeIndex.reason" class="detail-muted">
              {{ t(activeIndex.reason === 'context_only' ? 'knowledgeBase.chunkDetail.contextOnly' : `knowledgeBase.indexBuild.reasons.${activeIndex.reason}`) }}
            </p>
            <template v-if="activeIndex.kind === 'vector' || activeIndex.kind === 'keywords'">
              <p v-if="activeIndex.kind === 'vector'">{{ t('knowledgeBase.indexBuild.embeddingModel') }}：{{ detail.model_name || detail.model_id || '—' }}</p>
              <p v-if="activeIndex.stores?.length" class="detail-muted">{{ t('knowledgeBase.chunkDetail.stores') }}：{{ activeIndex.stores.join(', ') }}</p>
              <p class="detail-muted">{{ t('knowledgeBase.chunkDetail.retrievalHint') }}</p>
              <p v-if="activeIndex.available && !activeIndex.records?.length" class="detail-muted">{{ t('knowledgeBase.chunkDetail.empty') }}</p>
              <article v-for="record in activeIndex.records" :key="`${record.store_type}:${record.id}`" class="detail-card">
                <div class="record-heading"><strong>{{ record.source_id === detail.chunk.id ? t('knowledgeBase.chunkDetail.primaryRecord') : t('knowledgeBase.chunkDetail.questionRecord') }}</strong>
                  <t-tag size="small" variant="light">{{ record.store_type }}</t-tag>
                </div>
                <dl class="record-metadata">
                  <dt>{{ t('knowledgeBase.chunkDetail.recordId') }}</dt><dd>{{ record.id }}</dd>
                  <dt>Source ID</dt><dd>{{ record.source_id }}</dd>
                  <dt>Chunk ID</dt><dd>{{ record.chunk_id }}</dd>
                  <dt>{{ t('knowledgeBase.chunkDetail.searchable') }}</dt><dd>{{ t(record.is_enabled ? 'knowledgeBase.enabledStatus' : 'knowledgeBase.disabledStatus') }}</dd>
                  <dt>{{ t('knowledgeBase.chunkDetail.updatedAt') }}</dt><dd>{{ time(record.updated_at) }}</dd>
                  <template v-if="activeIndex.kind === 'vector'"><dt>{{ t('knowledgeBase.chunkDetail.dimension') }}</dt><dd>{{ record.dimension }}</dd></template>
                </dl>
                <h5>{{ t('knowledgeBase.chunkDetail.indexedContent') }}</h5>
                <pre class="detail-text">{{ record.content }}</pre>
                <details v-if="record.embedding" class="vector-values">
                  <summary>{{ t('knowledgeBase.chunkDetail.vectorValues') }} · {{ record.embedding.length }}</summary>
                  <pre class="detail-json">{{ pretty(record.embedding) }}</pre>
                </details>
              </article>
            </template>
            <template v-else-if="activeIndex.kind === 'graph'">
              <p class="detail-muted">{{ t('knowledgeBase.chunkDetail.graphHint') }}</p>
              <p v-if="activeIndex.available && !activeIndex.graph?.nodes.length" class="detail-muted">{{ t('knowledgeBase.chunkDetail.empty') }}</p>
              <h5>{{ t('knowledgeBase.chunkDetail.entities') }} · {{ activeIndex.graph?.nodes.length ?? '—' }}</h5>
              <article v-for="node in activeIndex.graph?.nodes" :key="node.id" class="detail-card">
                <strong>{{ node.name }}</strong><pre class="detail-json">{{ pretty(node) }}</pre>
              </article>
              <h5>{{ t('knowledgeBase.chunkDetail.relations') }} · {{ activeIndex.graph?.relations.length ?? '—' }}</h5>
              <article v-for="relation in activeIndex.graph?.relations" :key="relation.id" class="detail-card">
                <strong>{{ relation.source }} → {{ relation.type }} → {{ relation.target }}</strong>
                <pre v-if="Object.keys(relation.attributes).length" class="detail-json">{{ pretty(relation.attributes) }}</pre>
                <small class="detail-muted">{{ relation.id }}</small>
              </article>
            </template>
            <template v-else-if="activeIndex.kind === 'wiki'">
              <p class="detail-muted">{{ t('knowledgeBase.chunkDetail.wikiHint') }}</p>
              <p v-if="activeIndex.available && !activeIndex.pages?.length" class="detail-muted">{{ t('knowledgeBase.chunkDetail.empty') }}</p>
              <article v-for="entry in activeIndex.pages" :key="entry.page.id" class="detail-card">
                <div class="record-heading">
                  <a :href="wikiHref(entry.page.slug)" target="_blank" rel="noopener">{{ entry.page.title || entry.page.slug }} <t-icon name="jump" size="13px" /></a>
                  <t-tag size="small" variant="light">{{ t(`knowledgeBase.chunkDetail.${entry.scope === 'chunk' ? 'chunkCitation' : 'documentCitation'}`) }}</t-tag>
                </div>
                <p class="detail-muted">{{ entry.page.slug }} · {{ entry.page.status }}</p>
                <details><summary>{{ t('knowledgeBase.chunkDetail.pageContent') }}</summary>
                  <pre class="detail-text">{{ entry.page.content }}</pre>
                </details>
                <details><summary>{{ t('knowledgeBase.chunkDetail.metadata') }}</summary>
                  <pre class="detail-json">{{ pretty(entry.page) }}</pre>
                </details>
              </article>
            </template>
            <template v-else-if="activeIndex.kind === 'questions'">
              <p v-if="activeIndex.available && !activeIndex.questions?.length" class="detail-muted">{{ t('knowledgeBase.noGeneratedQuestions') }}</p>
              <article v-for="question in activeIndex.questions" :key="question.id" class="detail-card">
                <p class="question-content">{{ question.question }}</p>
                <dl class="record-metadata">
                  <dt>ID</dt><dd>{{ question.id }}</dd><dt>Source ID</dt><dd>{{ question.source_id }}</dd>
                  <dt>{{ t('knowledgeBase.chunkDetail.version') }}</dt><dd>{{ t(`knowledgeBase.chunkDetail.${question.current ? 'current' : 'outdated'}`) }}</dd>
                  <dt>{{ t('knowledgeBase.indexBuild.kinds.vector') }}</dt><dd>{{ indexedText(question.vector_indexed, 'vector') }}</dd>
                  <dt>{{ t('knowledgeBase.indexBuild.kinds.keywords') }}</dt><dd>{{ indexedText(question.keyword_indexed, 'keywords') }}</dd>
                </dl>
              </article>
            </template>
          </template>
        </div>
      </template>
    </div>
  </t-drawer>
</template>

<style scoped lang="less">
@import './css/markdown.less';
.detail-header { display: flex; align-items: center; gap: 8px; width: 100%; min-width: 0; padding-right: 28px; }
.detail-heading { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.detail-heading strong { font-size: var(--app-text-base); }
.detail-heading span { font-size: var(--app-text-xs); color: var(--td-text-color-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chunk-detail-body { color: var(--td-text-color-primary); }
.detail-state { display: flex; gap: 12px; align-items: center; justify-content: center; min-height: 180px; }
.detail-tabs { display: flex; gap: 6px; flex-wrap: wrap; padding-bottom: 14px; border-bottom: 1px solid var(--td-component-stroke); }
.detail-tabs button { display: inline-flex; align-items: center; gap: 4px; border: 0; border-radius: var(--app-radius-sm); background: var(--td-bg-color-secondarycontainer); padding: 8px 12px; color: var(--td-text-color-secondary); cursor: pointer; }
.detail-tabs button.active { background: var(--td-brand-color-light); color: var(--td-brand-color); }
.detail-panel { padding-top: 16px; }
.section-head, .record-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.section-head h4 { flex: 1; margin: 0; }
.detail-panel h4 { font-size: var(--app-text-base); }
.detail-panel h5 { font-size: var(--app-text-sm); margin: 14px 0 8px; }
.detail-card { padding: 16px; margin-top: 12px; border: 1px solid var(--td-component-stroke); border-radius: var(--app-radius-md); overflow-wrap: anywhere; }
.detail-text, .detail-json { white-space: pre-wrap; overflow-wrap: anywhere; margin: 10px 0; font-size: var(--app-text-sm); font-family: var(--td-font-family); line-height: 1.7; }
.detail-json { font-family: monospace; padding: 12px; background: var(--td-bg-color-secondarycontainer); border-radius: var(--app-radius-sm); }
.vector-values .detail-json { max-height: 420px; overflow-y: auto; }
.detail-muted { font-size: var(--app-text-xs); color: var(--td-text-color-secondary); line-height: 1.7; }
.detail-metadata, .record-metadata { display: grid; grid-template-columns: minmax(100px, 190px) minmax(0, 1fr); gap: 10px 16px; font-size: var(--app-text-sm); }
.detail-metadata dt, .record-metadata dt { color: var(--td-text-color-secondary); overflow-wrap: anywhere; }
.detail-metadata dd, .record-metadata dd { margin: 0; overflow-wrap: anywhere; }
.detail-metadata pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
.chunk-navigation { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 16px; }
.question-content { white-space: pre-wrap; margin-top: 0; line-height: 1.7; }
summary { cursor: pointer; font-size: var(--app-text-sm); }
a { color: var(--td-brand-color); }
:deep(.t-drawer__body) { overflow-y: auto; }
@media (max-width: 600px) { .detail-metadata, .record-metadata { grid-template-columns: 105px minmax(0, 1fr); gap: 8px; } }
</style>
