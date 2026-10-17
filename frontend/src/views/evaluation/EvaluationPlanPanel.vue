<template>
  <div class="plan-panel">
    <div class="plan-toolbar">
      <t-input v-model="keyword" clearable :maxlength="100" :placeholder="t('evaluationPlan.search')" :aria-label="t('evaluationPlan.search')">
        <template #prefix-icon><t-icon name="search" /></template>
      </t-input>
      <span class="plan-total">{{ t('evaluationPlan.total', { count: total }) }}</span>
      <t-button v-if="canCreate" @click="create"><template #icon><t-icon name="add" /></template>{{ t('evaluationPlan.create') }}</t-button>
    </div>
    <t-alert v-if="operationError" theme="error" :message="operationError" close @close="operationError = ''" />
    <div class="plan-results" :aria-busy="loading">
      <div v-if="loading" class="plan-loading"><t-loading :text="t('evaluationPlan.loading')" /></div>
      <EmptyState v-else-if="error" icon="error-circle" :title="t('evaluationPlan.loadFailed')">
        <t-button variant="outline" @click="load">{{ t('evaluationPlan.retry') }}</t-button>
      </EmptyState>
      <EmptyState v-else-if="!items.length" :icon="searchTerm ? 'search' : 'task'"
        :title="t(searchTerm ? 'evaluationPlan.noResults' : 'evaluationPlan.empty')"
        :description="t(searchTerm ? 'evaluationPlan.searchHint' : 'evaluationPlan.emptyHint')">
        <t-button v-if="searchTerm" variant="outline" @click="keyword = ''">{{ t('evaluationPlan.clearSearch') }}</t-button>
        <t-button v-else-if="canCreate" @click="create">{{ t('evaluationPlan.create') }}</t-button>
      </EmptyState>
      <div v-else class="plan-grid">
        <article v-for="plan in items" :key="plan.id" class="plan-card" role="button" tabindex="0"
          :aria-label="t('evaluationPlan.viewNamed', { name: plan.name })" @click="open(plan, 'view')"
          @keydown.enter.self.prevent="open(plan, 'view')" @keydown.space.self.prevent="open(plan, 'view')">
          <div class="card-header">
            <span class="card-title" :title="plan.name">{{ plan.name }}</span>
            <t-dropdown trigger="click" :options="actions(plan)" @click="(option: { value?: string | number }) => action(String(option.value), plan)">
              <button type="button" class="more-wrap" :aria-label="t('evaluationPlan.more')" @click.stop @keydown.stop>
                <t-icon name="more" />
              </button>
            </t-dropdown>
          </div>
          <button type="button" class="plan-id" :title="t('evaluationPlan.copyId')" @click.stop="copyID(plan.id)" @keydown.stop>
            <span>{{ plan.id }}</span><t-icon name="copy" size="14px" />
          </button>
          <p class="card-description" :title="plan.description">{{ plan.description || t('evaluationPlan.noDescription') }}</p>
          <dl class="plan-models">
            <div><dt>{{ t('evaluationPlan.generationModel') }}</dt><dd>{{ modelName(plan.qa_generation_model_id) }}</dd></div>
            <div><dt>{{ t('evaluationPlan.evaluationModel') }}</dt><dd>{{ modelName(plan.evaluation_model_id) }}</dd></div>
          </dl>
          <div class="card-bottom"><span>{{ plan.creator_name || plan.created_by }}</span><time :title="plan.updated_at">{{ date(plan.updated_at) }}</time></div>
        </article>
      </div>
    </div>
    <t-pagination v-if="!error && total > 0" :current="page" :page-size="pageSize" :total="total"
      :page-size-options="[12, 24, 48]" @change="pagination" />
    <EvaluationPlanEditor v-if="editor" :key="editorKey" :plan="editor.plan" :mode="editor.mode"
      @close="editor = null" @saved="saved" />
    <t-dialog :visible="!!deletingPlan" :header="t('evaluationPlan.deleteTitle')" :confirm-btn="{ content: t('evaluationPlan.delete'), theme: 'danger', loading: deleting }"
      :cancel-btn="{ content: t('evaluationPlan.cancel'), disabled: deleting }" :close-btn="!deleting"
      :close-on-overlay-click="!deleting" :close-on-esc-keydown="!deleting"
      @close="!deleting && (deletingPlan = null)" @confirm="remove">
      <t-alert v-if="deleteError" theme="error" :message="deleteError" />
      <p>{{ t('evaluationPlan.deleteConfirm', { name: deletingPlan?.name }) }}</p>
      <code class="plan-delete-id">{{ deletingPlan?.id }}</code>
    </t-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { MessagePlugin } from 'tdesign-vue-next'
import { useAuthStore } from '@/stores/auth'
import { listModels, type ModelConfig } from '@/api/model'
import { listEvaluationPlans, getEvaluationPlan, copyEvaluationPlan, deleteEvaluationPlan, type EvaluationPlan } from '@/api/evaluation-plan'
import { copyToClipboard } from '@/utils/clipboard'
import EmptyState from '@/components/EmptyState.vue'
import EvaluationPlanEditor from './EvaluationPlanEditor.vue'

const { t } = useI18n()
const auth = useAuthStore()
const canCreate = computed(() => auth.hasRole('contributor') || auth.canAccessAllTenants)
const items = ref<EvaluationPlan[]>([])
const models = ref<ModelConfig[]>([])
const total = ref(0), page = ref(1), pageSize = ref(12)
const keyword = ref(''), searchTerm = ref(''), loading = ref(false), error = ref(false), operationError = ref('')
const editor = ref<{ mode: 'create' | 'view' | 'edit'; plan?: EvaluationPlan } | null>(null)
const editorKey = ref(0)
const deletingPlan = ref<EvaluationPlan | null>(null), deleting = ref(false), deleteError = ref('')
let sequence = 0, detailSequence = 0, scope = 0, alive = true
let searchTimer: ReturnType<typeof setTimeout> | undefined
const busy = new Set<string>()
const modelName = (id: string) => {
  const m = models.value.find(m => m.id === id && m.status === 'active' && m.type === 'KnowledgeQA')
  return m ? (m.display_name || m.name) : t('evaluationPlan.modelUnavailable')
}
const date = (value: string) => new Date(value).toLocaleString()
async function copyID(id: string) {
  const ok = await copyToClipboard(id)
  if (alive) void (ok ? MessagePlugin.success(t('evaluationPlan.copied')) : MessagePlugin.error(t('evaluationPlan.copyFailed')))
}
async function load() {
  const request = ++sequence, currentScope = scope
  loading.value = true; error.value = false
  try {
    const result = await listEvaluationPlans(searchTerm.value, page.value, pageSize.value)
    if (!alive || request !== sequence || currentScope !== scope) return
    total.value = result.total
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize.value))
    if (page.value > lastPage) { page.value = lastPage; return }
    items.value = result.items
  } catch { if (alive && request === sequence && currentScope === scope) { error.value = true; items.value = [] } }
  finally { if (alive && request === sequence && currentScope === scope) loading.value = false }
}
watch(keyword, value => {
  clearTimeout(searchTimer)
  // Invalidate an in-flight search immediately, before the debounce fires.
  sequence++
  searchTimer = setTimeout(() => {
    searchTerm.value = value.trim()
    if (page.value === 1) void load(); else page.value = 1
  }, 300)
})
watch([page, pageSize], () => void load())
watch(() => auth.effectiveTenantId, async () => {
  const currentScope = ++scope
  sequence++; detailSequence++; clearTimeout(searchTimer)
  items.value = []; models.value = []; total.value = 0; editor.value = null; deletingPlan.value = null
  deleting.value = false; operationError.value = ''; keyword.value = ''; searchTerm.value = ''; page.value = 1; busy.clear()
  void load()
  try { const result = await listModels('KnowledgeQA'); if (alive && currentScope === scope) models.value = result }
  catch { /* Model names remain explicitly unavailable; editing retries the model request. */ }
}, { immediate: true })
onBeforeUnmount(() => { alive = false; scope++; sequence++; detailSequence++; clearTimeout(searchTimer) })
function pagination(value: { current: number; pageSize: number }) {
  page.value = value.pageSize === pageSize.value ? value.current : 1
  pageSize.value = value.pageSize
}
function create() { detailSequence++; editorKey.value++; editor.value = { mode: 'create' } }
async function open(plan: EvaluationPlan, mode: 'view' | 'edit') {
  const request = ++detailSequence, currentScope = scope
  try {
    const fresh = await getEvaluationPlan(plan.id)
    if (!alive || request !== detailSequence || currentScope !== scope) return
    editorKey.value++; editor.value = { plan: fresh, mode: mode === 'edit' && !fresh.can_manage ? 'view' : mode }
  } catch { if (alive && request === detailSequence && currentScope === scope) operationError.value = t('evaluationPlan.operationFailed') }
}
function actions(plan: EvaluationPlan) {
  return [
    { content: t('evaluationPlan.view'), value: 'view' },
    ...(plan.can_manage ? [{ content: t('evaluationPlan.edit'), value: 'edit' }] : []),
    ...(canCreate.value ? [{ content: t('evaluationPlan.duplicate'), value: 'copy' }] : []),
    ...(plan.can_manage ? [{ content: t('evaluationPlan.delete'), value: 'delete', theme: 'error' as const }] : []),
  ]
}
async function action(value: string, plan: EvaluationPlan) {
  if (value === 'view' || value === 'edit') { await open(plan, value); return }
  if (value === 'delete') { deleteError.value = ''; deletingPlan.value = plan; return }
  if (value !== 'copy' || busy.has(plan.id)) return
  busy.add(plan.id)
  const currentScope = scope
  const request = ++detailSequence
  try {
    const copy = await copyEvaluationPlan(plan.id)
    if (!alive || currentScope !== scope) return
    void load()
    if (request === detailSequence) { editorKey.value++; editor.value = { mode: 'edit', plan: copy } }
    void MessagePlugin.success(t('evaluationPlan.duplicated'))
  } catch { if (alive && currentScope === scope) operationError.value = t('evaluationPlan.operationFailed') }
  finally { if (currentScope === scope) busy.delete(plan.id) }
}
function saved() {
  editor.value = null
  void load()
}
async function remove() {
  if (!deletingPlan.value || deleting.value) return
  const plan = deletingPlan.value, currentScope = scope
  deleting.value = true; deleteError.value = ''
  try {
    await deleteEvaluationPlan(plan.id)
    if (!alive || currentScope !== scope) return
    deletingPlan.value = null
    if (editor.value?.plan?.id === plan.id) editor.value = null
    void load(); void MessagePlugin.success(t('evaluationPlan.deleted'))
  } catch { if (alive && currentScope === scope) deleteError.value = t('evaluationPlan.operationFailed') }
  finally { if (alive && currentScope === scope) deleting.value = false }
}
</script>

<style scoped lang="less">
@import '@/components/css/resource-card.less';
.plan-panel { display: flex; flex-direction: column; flex: 1; min-height: 0; gap: 16px; }
.plan-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; .t-input__wrap { flex: 1; min-width: 180px; max-width: 380px; } }
.plan-total { color: var(--td-text-color-secondary); font-size: var(--app-text-base); margin-right: auto; }
.plan-results { flex: 1; display: flex; flex-direction: column; }
.plan-loading { display: flex; justify-content: center; padding: 48px; }
.plan-grid { .resource-card-grid(); }
.plan-card { .resource-card(); gap: 8px; .card-header { margin-bottom: 0; } .card-description { min-height: 36px; margin: 0; } }
.plan-id { display: flex; gap: 6px; align-items: center; width: 100%; padding: 0; background: transparent; border: 0; color: var(--td-text-color-secondary); cursor: pointer; text-align: left; font-size: var(--app-text-xs); span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .t-icon { flex-shrink: 0; } }
.plan-models { margin: 0; font-size: var(--app-text-sm); div { display: flex; gap: 8px; margin-bottom: 4px; } dt { flex-shrink: 0; color: var(--td-text-color-secondary); } dd { margin: 0; overflow-wrap: anywhere; } }
.card-bottom { color: var(--td-text-color-secondary); font-size: var(--app-text-xs); span { overflow-wrap: anywhere; } }
.plan-delete-id { overflow-wrap: anywhere; }
</style>
