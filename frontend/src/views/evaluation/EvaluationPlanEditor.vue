<template>
  <SettingsModalShell :visible="true"
    :title="t(readonly ? 'evaluationPlan.detail' : mode === 'create' ? 'evaluationPlan.create' : 'evaluationPlan.edit')"
    overlay-class="evaluation-plan-modal" @close="modalShell.requestClose">
    <template #nav>
      <template v-for="group in navGroups" :key="group.key">
        <div class="nav-group-title">{{ group.label }}</div>
        <button v-for="item in group.items" :key="item.key" type="button"
          class="nav-item plan-nav-item" :class="{ active: currentSection === item.key }"
          :aria-current="currentSection === item.key ? 'page' : undefined"
          :aria-controls="`evaluation-plan-${item.key}`" :disabled="saving" @click="currentSection = item.key">
          <t-icon :name="item.icon" class="nav-icon" /><span class="nav-label">{{ item.label }}</span>
        </button>
      </template>
    </template>
    <div ref="editorBody" class="plan-editor">
      <header class="plan-section-header">
        <h3>{{ t(`evaluationPlan.${currentSection}`) }}</h3>
        <p>{{ t(`evaluationPlan.${currentSection}Description`) }}</p>
      </header>
      <t-alert v-if="error" theme="error" :message="error" class="plan-alert" />
      <t-alert v-if="modelsError" theme="warning" :message="t('evaluationPlan.modelsFailed')" class="plan-alert">
        <template #operation><t-button variant="text" @click="loadModels">{{ t('evaluationPlan.retry') }}</t-button></template>
      </t-alert>
      <dl v-if="plan" v-show="currentSection === 'basic'" class="plan-metadata">
        <div class="plan-id-row"><dt>{{ t('evaluationPlan.id') }}</dt><dd><code>{{ plan.id }}</code><t-button variant="text" size="small" :aria-label="t('evaluationPlan.copyId')" @click="copyID"><t-icon name="copy" /></t-button></dd></div>
        <div><dt>{{ t('evaluationPlan.creator') }}</dt><dd>{{ plan.creator_name || plan.created_by }}</dd></div>
        <div><dt>{{ t('evaluationPlan.createdAt') }}</dt><dd>{{ date(plan.created_at) }}</dd></div>
        <div><dt>{{ t('evaluationPlan.updatedAt') }}</dt><dd>{{ date(plan.updated_at) }}</dd></div>
      </dl>
      <t-form label-align="top" :disabled="readonly || saving" @submit="save">
        <section v-show="currentSection === 'basic'" id="evaluation-plan-basic" class="plan-form-section">
          <t-form-item :label="t('evaluationPlan.name')" required><t-input v-model="form.name" :maxlength="PLAN_LIMITS.name" show-limit-number /></t-form-item>
          <t-form-item :label="t('evaluationPlan.description')"><t-textarea v-model="form.description" :maxlength="PLAN_LIMITS.description" :autosize="{ minRows: 2, maxRows: 5 }" /></t-form-item>
        </section>
        <section v-show="currentSection === 'generation'" id="evaluation-plan-generation" class="plan-form-section">
          <t-form-item :label="t('evaluationPlan.generationModel')" required>
            <t-select v-model="form.qa_generation_model_id" :options="modelOptions(form.qa_generation_model_id)" :loading="modelsLoading" filterable :placeholder="t('evaluationPlan.selectModel')" />
          </t-form-item>
          <t-form-item :label="t('evaluationPlan.generationPrompt')" required>
            <div class="plan-prompt">
              <t-textarea v-model="form.qa_generation_prompt" :maxlength="PLAN_LIMITS.prompt" :autosize="{ minRows: 8, maxRows: 18 }" />
              <p>{{ t('evaluationPlan.variables') }} <code>{{ GENERATION_VARIABLES }}</code></p>
              <t-button v-if="!readonly" variant="text" size="small" @click="restore('qa_generation_prompt')">{{ t('evaluationPlan.restorePrompt') }}</t-button>
            </div>
          </t-form-item>
          <div class="plan-form-grid">
            <t-form-item :label="t('evaluationPlan.questionsPerChunk')" required><t-input-number v-model="form.qa_generation_rules.questions_per_chunk" :min="1" :max="PLAN_LIMITS.questions" :decimal-places="0" /></t-form-item>
            <t-form-item :label="t('evaluationPlan.difficulty')" required><t-select v-model="form.qa_generation_rules.difficulty" :options="DIFFICULTIES.map(value => ({ value, label: t(`evaluationPlan.difficulties.${value}`) }))" /></t-form-item>
            <t-form-item :label="t('evaluationPlan.language')" required><t-select v-model="form.qa_generation_rules.language" :options="QA_LANGUAGES.map(value => ({ value, label: t(`evaluationPlan.languages.${value}`) }))" /></t-form-item>
          </div>
          <t-form-item :label="t('evaluationPlan.questionTypes')" required><t-checkbox-group v-model="form.qa_generation_rules.question_types" :options="QUESTION_TYPES.map(value => ({ value, label: t(`evaluationPlan.types.${value}`) }))" /></t-form-item>
        </section>
        <section v-show="currentSection === 'evaluation'" id="evaluation-plan-evaluation" class="plan-form-section">
          <t-form-item :label="t('evaluationPlan.evaluationModel')" required><t-select v-model="form.evaluation_model_id" :options="modelOptions(form.evaluation_model_id)" :loading="modelsLoading" filterable :placeholder="t('evaluationPlan.selectModel')" /></t-form-item>
          <t-form-item :label="t('evaluationPlan.comparisonPrompt')" required>
            <div class="plan-prompt">
              <t-textarea v-model="form.answer_comparison_prompt" :maxlength="PLAN_LIMITS.prompt" :autosize="{ minRows: 8, maxRows: 18 }" />
              <p>{{ t('evaluationPlan.variables') }} <code>{{ COMPARISON_VARIABLES }}</code></p>
              <t-button v-if="!readonly" variant="text" size="small" @click="restore('answer_comparison_prompt')">{{ t('evaluationPlan.restorePrompt') }}</t-button>
            </div>
          </t-form-item>
        </section>
        <section v-show="currentSection === 'dimensions'" id="evaluation-plan-dimensions" class="plan-form-section">
          <div class="plan-dimensions-heading"><span :class="{ 'plan-invalid': weightTotal !== 100 }">{{ t('evaluationPlan.weightTotal', { total: weightTotal }) }}</span></div>
          <p class="plan-field-hint">{{ t('evaluationPlan.dimensionsHint') }}</p>
          <fieldset v-for="(dimension, index) in form.scoring_dimensions" :key="index" class="plan-dimension">
            <legend>{{ t('evaluationPlan.dimensionNumber', { number: index + 1 }) }}</legend>
            <div class="plan-form-grid">
              <t-form-item :label="t('evaluationPlan.dimensionId')" required><t-input v-model="dimension.id" :maxlength="64" /></t-form-item>
              <t-form-item :label="t('evaluationPlan.dimensionName')" required><t-input v-model="dimension.name" :maxlength="100" /></t-form-item>
              <t-form-item :label="t('evaluationPlan.weight')" required><t-input-number v-model="dimension.weight" :min="1" :max="100" :decimal-places="0" suffix="%" /></t-form-item>
            </div>
            <t-form-item :label="t('evaluationPlan.dimensionDescription')"><t-textarea v-model="dimension.description" :maxlength="2000" :autosize="{ minRows: 2, maxRows: 5 }" /></t-form-item>
            <t-button v-if="!readonly" variant="text" theme="danger" :disabled="form.scoring_dimensions.length <= 1" @click="form.scoring_dimensions.splice(index, 1)">{{ t('evaluationPlan.removeDimension') }}</t-button>
          </fieldset>
          <t-button v-if="!readonly" variant="outline" :disabled="form.scoring_dimensions.length >= PLAN_LIMITS.dimensions" @click="addDimension">{{ t('evaluationPlan.addDimension') }}</t-button>
          <t-form-item class="plan-pass-score" :label="t('evaluationPlan.passScore')" required><t-input-number v-model="form.pass_score" :min="0" :max="100" :decimal-places="2" /></t-form-item>
        </section>
      </t-form>
    </div>
    <template #footer>
      <t-button theme="default" variant="outline" :disabled="saving" @click="modalShell.requestClose">
        {{ t(readonly ? 'evaluationPlan.close' : 'evaluationPlan.cancel') }}
      </t-button>
      <t-button v-if="!readonly" theme="primary" :loading="saving" :disabled="modelsLoading || !!modelsError" @click="save">
        {{ t('evaluationPlan.saveAndClose') }}
      </t-button>
    </template>
  </SettingsModalShell>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { DialogPlugin, MessagePlugin } from 'tdesign-vue-next'
import { createEvaluationPlan, updateEvaluationPlan, type EvaluationPlan } from '@/api/evaluation-plan'
import { listModels, type ModelConfig } from '@/api/model'
import { copyToClipboard } from '@/utils/clipboard'
import SettingsModalShell from '@/components/SettingsModalShell.vue'
import { useModalShell } from '@/composables/useModalShell'
import { defaultPlan, editablePlan, validatePlan, PLAN_LIMITS, QUESTION_TYPES, DIFFICULTIES, QA_LANGUAGES, GENERATION_VARIABLES, COMPARISON_VARIABLES } from './evaluationPlanConfig'
const props = defineProps<{ plan?: EvaluationPlan; mode: 'create' | 'view' | 'edit' }>()
const emit = defineEmits<{ close: []; saved: [plan: EvaluationPlan] }>()
const { t, locale } = useI18n()
const readonly = computed(() => props.mode === 'view')
const form = ref(props.plan ? editablePlan(props.plan) : defaultPlan(locale.value))
const original = JSON.stringify(form.value)
type PlanSection = 'basic' | 'generation' | 'evaluation' | 'dimensions'
const currentSection = ref<PlanSection>('basic')
const editorBody = ref<HTMLElement>()
const navGroups = computed(() => [
  { key: 'basic', label: t('evaluationPlan.basicGroup'), items: [
    { key: 'basic' as const, label: t('evaluationPlan.basic'), icon: 'info-circle' },
  ] },
  { key: 'rules', label: t('evaluationPlan.rulesGroup'), items: [
    { key: 'generation' as const, label: t('evaluationPlan.generation'), icon: 'chat' },
    { key: 'evaluation' as const, label: t('evaluationPlan.evaluation'), icon: 'chart-radar' },
    { key: 'dimensions' as const, label: t('evaluationPlan.dimensions'), icon: 'chart-pie' },
  ] },
])
watch(currentSection, () => { if (editorBody.value) editorBody.value.scrollTop = 0 }, { flush: 'post' })
const saving = ref(false), error = ref(''), modelsLoading = ref(true), modelsError = ref(false)
const models = ref<ModelConfig[]>([])
const weightTotal = computed(() => form.value.scoring_dimensions.reduce((sum, d) => sum + (Number(d.weight) || 0), 0))
let alive = true, modelRequest = 0
const confirmations = new Set<ReturnType<typeof DialogPlugin.confirm>>()
const modalShell = useModalShell({
  visible: () => true,
  close,
  ignoreEscape: () => saving.value || confirmations.size > 0,
})
onBeforeUnmount(() => { alive = false; modelRequest++; for (const d of confirmations) d.destroy() })
function confirm(message: string, callback: () => void) {
  const dialog = DialogPlugin.confirm({ header: t('evaluationPlan.confirm'), body: message,
    confirmBtn: t('evaluationPlan.confirm'), cancelBtn: t('evaluationPlan.cancel'),
    onConfirm: () => { dialog.destroy(); confirmations.delete(dialog); if (alive) callback() },
    onClose: () => { dialog.destroy(); confirmations.delete(dialog) },
  }); confirmations.add(dialog)
}
function close() {
  if (saving.value || confirmations.size > 0) return
  if (!readonly.value && JSON.stringify(form.value) !== original) confirm(t('evaluationPlan.unsaved'), () => emit('close'))
  else emit('close')
}
async function copyID() {
  if (!props.plan) return
  const ok = await copyToClipboard(props.plan.id)
  if (alive) void (ok ? MessagePlugin.success(t('evaluationPlan.copied')) : MessagePlugin.error(t('evaluationPlan.copyFailed')))
}
const date = (value: string) => new Date(value).toLocaleString()
async function loadModels() {
  const request = ++modelRequest
  modelsLoading.value = true; modelsError.value = false
  try { const list = await listModels('KnowledgeQA'); if (alive && request === modelRequest) models.value = list.filter(m => m.id && m.status === 'active') }
  catch { if (alive && request === modelRequest) modelsError.value = true }
  finally { if (alive && request === modelRequest) modelsLoading.value = false }
}
void loadModels()
function modelOptions(selected: string) {
  const options = models.value.map(m => ({ value: m.id!, label: m.display_name || m.name, disabled: false }))
  if (selected && !options.some(m => m.value === selected)) options.unshift({ value: selected, label: `${t('evaluationPlan.modelUnavailable')} (${selected})`, disabled: true })
  return options
}
function restore(field: 'qa_generation_prompt' | 'answer_comparison_prompt') {
  const apply = () => { form.value[field] = defaultPlan(locale.value)[field] }
  if (form.value[field]) confirm(t('evaluationPlan.restoreConfirm'), apply); else apply()
}
function addDimension() {
  let number = form.value.scoring_dimensions.length + 1
  while (form.value.scoring_dimensions.some(d => d.id === `dimension_${number}`)) number++
  form.value.scoring_dimensions.push({ id: `dimension_${number}`, name: '', description: '', weight: 1 })
}
async function save() {
  if (readonly.value || saving.value || modelsLoading.value || modelsError.value) return
  const input = editablePlan(form.value)
  const invalid = validatePlan(input, new Set(models.value.map(m => m.id!)))
  if (invalid) {
    // Validation covers every section, including fields hidden by the navigation.
    if (invalid === 'basic') currentSection.value = 'basic'
    else if (invalid === 'rules') currentSection.value = 'generation'
    else if (invalid === 'models') currentSection.value = models.value.some(m => m.id === input.qa_generation_model_id) ? 'evaluation' : 'generation'
    else if (invalid === 'prompts') currentSection.value = !input.qa_generation_prompt.trim() || [...input.qa_generation_prompt].length > PLAN_LIMITS.prompt ? 'generation' : 'evaluation'
    else currentSection.value = 'dimensions'
    error.value = t(`evaluationPlan.validation.${invalid}`)
    await nextTick()
    if (editorBody.value) editorBody.value.scrollTop = 0
    return
  }
  saving.value = true; error.value = ''
  try {
    const saved = props.mode === 'create' ? await createEvaluationPlan(input) : await updateEvaluationPlan(props.plan!.id, input)
    if (!alive) return
    void MessagePlugin.success(t('evaluationPlan.saved')); emit('saved', saved)
  } catch {
    if (alive) {
      error.value = t('evaluationPlan.saveFailed')
      await nextTick()
      if (editorBody.value) editorBody.value.scrollTop = 0
    }
  }
  finally { if (alive) saving.value = false }
}
</script>

<style scoped lang="less">
.plan-nav-item {
  width: 100%;
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
  font-weight: 400;
  &.active { font-weight: 400; }
  &:focus-visible { outline: 2px solid var(--td-brand-color); outline-offset: -2px; }
  &:disabled { cursor: default; }
}
.plan-editor { flex: 1; min-height: 0; overflow-y: auto; padding: 24px 32px; }
.plan-section-header {
  margin-bottom: 20px;
  padding-right: 24px;
  h3 { margin: 0 0 6px; font-size: var(--app-text-3xl); font-weight: 600; color: var(--td-text-color-primary); }
  p { margin: 0; font-size: var(--app-text-base); color: var(--td-text-color-placeholder); line-height: 22px; }
}
.plan-alert { margin-bottom: 20px; }
.plan-form-section { min-width: 0; }
.plan-editor :deep(.t-form__label) { font-weight: 500; }
.plan-editor :deep(.t-form__item) { margin-bottom: 20px; }
.plan-form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.plan-prompt { width: 100%; p { color: var(--td-text-color-secondary); font-size: var(--app-text-sm); overflow-wrap: anywhere; line-height: 20px; } }
.plan-metadata {
  margin: 0 0 24px;
  font-size: var(--app-text-sm);
  div { display: flex; gap: 12px; margin-bottom: 8px; }
  dt { flex: 0 0 80px; color: var(--td-text-color-secondary); }
  dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
  .plan-id-row {
    display: block;
    margin-bottom: 20px;
    dt { margin-bottom: 8px; font-size: var(--app-text-lg); font-weight: 500; color: var(--td-text-color-primary); }
    dd { display: flex; align-items: center; justify-content: space-between; gap: 8px; max-width: 480px; padding: 6px 8px 6px 12px; background: var(--td-bg-color-secondarycontainer); border: 1px solid var(--td-component-stroke); border-radius: var(--app-radius-sm); }
    code { min-width: 0; overflow-wrap: anywhere; }
  }
}
.plan-dimensions-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.plan-invalid { color: var(--td-error-color); }
.plan-field-hint { color: var(--td-text-color-secondary); font-size: var(--app-text-sm); line-height: 20px; }
.plan-dimension { border: 1px solid var(--td-component-stroke); border-radius: var(--app-radius-sm); padding: 16px; margin: 16px 0; min-width: 0; legend { font-size: var(--app-text-base); color: var(--td-text-color-secondary); padding: 0 6px; } }
.plan-pass-score { margin-top: 24px; }
</style>

<style lang="less">
.settings-modal-shell.evaluation-plan-modal {
  .nav-group-title { font-weight: 400; }
  @media (max-width: 768px) {
    padding: 12px;
    .settings-modal { max-height: calc(100dvh - 24px); }
    .settings-sidebar { width: 164px; }
    .sidebar-title { font-size: var(--app-text-lg); }
    .plan-nav-item { padding: 8px; }
    .plan-editor { padding: 24px 20px; }
    .plan-form-grid { grid-template-columns: 1fr; gap: 0; }
    .plan-metadata div { flex-wrap: wrap; }
    .settings-footer { padding: 12px 20px; }
  }
  @media (max-width: 560px) {
    .settings-container { flex-direction: column; }
    .settings-sidebar { width: 100%; border-right: 0; border-bottom: 1px solid var(--td-component-stroke); }
    .sidebar-header { padding-right: 48px; }
    .settings-nav { display: flex; flex: none; gap: 4px; overflow-x: auto; padding: 8px; }
    .nav-group-title { display: none; }
    .plan-nav-item { flex: 0 0 auto; width: auto; white-space: nowrap; margin-bottom: 0; }
    .plan-editor { padding: 20px 16px; }
    .settings-footer { padding: 12px 16px; }
  }
}
</style>
