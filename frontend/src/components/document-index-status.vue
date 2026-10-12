<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { getKnowledgeIndexStatus } from '@/api/knowledge-base'
import { useDocumentIndexStatus } from '@/composables/useDocumentIndexStatus'
import type { KnowledgeIndexBuildState, KnowledgeIndexKind, KnowledgeIndexStatusRow } from '@/types/knowledgeIndexStatus'

const props = defineProps<{ visible: boolean; knowledgeId: string; kbId: string; parseStatus?: string }>()
const { t, locale } = useI18n()
const router = useRouter()
const { report, loading, failed, refresh } = useDocumentIndexStatus(
  () => props.visible, () => props.knowledgeId, getKnowledgeIndexStatus,
)
const kinds: KnowledgeIndexKind[] = ['vector', 'keywords', 'graph', 'wiki', 'questions']
const rows = computed<KnowledgeIndexStatusRow[]>(() => kinds.map(kind =>
  report.value?.indexes.find(row => row.kind === kind) || { kind, enabled: false, status: 'unknown', count: null },
))

watch(() => props.parseStatus, () => { void refresh() })

function statusTheme(status: KnowledgeIndexBuildState) {
  if (status === 'completed') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'partial_failed' || status === 'stale' || status === 'cancelled') return 'warning'
  if (status === 'building' || status === 'waiting') return 'primary'
  return 'default'
}

function countText(row: KnowledgeIndexStatusRow) {
  const count = row.count == null ? '—' : row.count.toLocaleString(locale.value)
  if (row.kind === 'graph') {
    const relations = row.relation_count == null ? '—' : row.relation_count.toLocaleString(locale.value)
    return t('knowledgeBase.indexBuild.graphCount', { count, relations })
  }
  if (row.kind === 'wiki') return t('knowledgeBase.indexBuild.pageCount', { count })
  if (row.kind === 'questions') {
    const indexed = row.indexed_count == null ? '—' : row.indexed_count.toLocaleString(locale.value)
    return t('knowledgeBase.indexBuild.questionCount', { count, indexed })
  }
  return t('knowledgeBase.indexBuild.chunkCount', { count })
}

function formatTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(locale.value)
}

function wikiLocation(slug: string) {
  return router.resolve({
    path: `/platform/knowledge-bases/${props.kbId}`,
    query: { tab: 'wiki', slug },
  }).href
}
</script>

<template>
  <div class="document-index-status" :aria-busy="loading">
    <div class="index-build-header">
      <h5>{{ t('knowledgeBase.indexBuild.title') }}</h5>
      <t-button class="index-refresh" size="small" variant="text" shape="square" :loading="loading"
        :title="t('common.refresh')" :aria-label="t('common.refresh')" @click="refresh">
        <template #icon><t-icon name="refresh" size="14px" /></template>
      </t-button>
    </div>
    <p v-if="failed" class="index-fetch-error" role="status">{{ t('knowledgeBase.indexBuild.loadFailed') }}</p>
    <div v-for="row in rows" :key="row.kind" class="index-build-row">
      <div class="index-build-line">
        <span class="index-kind">{{ t(`knowledgeBase.indexBuild.kinds.${row.kind}`) }}</span>
        <t-tag :theme="statusTheme(row.status)" variant="light" size="small">
          {{ t(`knowledgeBase.indexBuild.states.${row.status}`) }}
        </t-tag>
        <span v-if="report && !row.enabled && row.status !== 'disabled'" class="index-count">
          {{ t('knowledgeBase.indexBuild.currentlyDisabled') }}
        </span>
        <t-popup v-if="row.kind === 'wiki' && row.pages?.length && kbId" trigger="click" placement="bottom-right"
          :overlay-inner-style="{ maxWidth: '360px' }">
          <button type="button" class="index-count index-pages-button">{{ countText(row) }}<t-icon name="jump" size="12px" /></button>
          <template #content>
            <div class="index-page-links">
              <a v-for="page in row.pages" :key="page.slug" :href="wikiLocation(page.slug)" target="_blank" rel="noopener">
                {{ page.title || page.slug }}
              </a>
              <span v-if="row.count != null && row.count > row.pages.length" class="index-page-limit">
                {{ t('knowledgeBase.indexBuild.pagePreviewLimit', { count: row.pages.length }) }}
              </span>
            </div>
          </template>
        </t-popup>
        <span v-else class="index-count">{{ countText(row) }}</span>
      </div>
      <div v-if="row.kind === 'vector' && row.model_id" class="index-model" :title="row.model_id">
        {{ t('knowledgeBase.indexBuild.embeddingModel') }}：{{ row.model_name || row.model_id }}
      </div>
      <details v-if="row.reason || row.error_message" class="index-details">
        <summary>{{ t('knowledgeBase.indexBuild.details') }}</summary>
        <p v-if="row.reason">{{ t(`knowledgeBase.indexBuild.reasons.${row.reason}`) }}</p>
        <p v-if="row.error_message" class="index-error-message">{{ row.error_message }}</p>
      </details>
    </div>
    <div class="index-built-at">{{ t('knowledgeBase.indexBuild.lastBuiltAt') }}：{{ formatTime(report?.last_built_at) }}</div>
  </div>
</template>

<style scoped lang="less">
.document-index-status {
  border-top: 1px solid var(--td-component-stroke);
  padding-top: 10px;
}
.index-build-header, .index-build-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.index-build-header { justify-content: space-between; margin-bottom: 4px; }
.index-build-header h5 { margin: 0; font-size: var(--app-text-sm); font-weight: 600; color: var(--td-text-color-primary); }
.index-refresh { color: var(--td-text-color-secondary); }
.index-build-row { padding: 7px 0; }
.index-build-line { flex-wrap: wrap; }
.index-kind { min-width: 112px; font-size: var(--app-text-sm); color: var(--td-text-color-primary); }
.index-count { font-size: var(--app-text-sm); color: var(--td-text-color-secondary); }
.index-pages-button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--td-brand-color);
  cursor: pointer;
  &:hover { text-decoration: underline; }
}
.index-model, .index-details, .index-built-at, .index-fetch-error {
  font-size: var(--app-text-xs);
  color: var(--td-text-color-secondary);
  overflow-wrap: anywhere;
}
.index-model { margin-top: 4px; }
.index-details { margin-top: 4px; }
.index-details summary { cursor: pointer; width: fit-content; }
.index-details p { margin: 4px 0 0; }
.index-fetch-error, .index-error-message { color: var(--td-error-color); }
.index-built-at { margin-top: 8px; }
.index-page-links { display: flex; flex-direction: column; gap: 8px; max-height: 280px; overflow-y: auto; }
.index-page-links a { color: var(--td-brand-color); font-size: var(--app-text-sm); overflow-wrap: anywhere; }
.index-page-limit { font-size: var(--app-text-xs); color: var(--td-text-color-secondary); }
</style>
