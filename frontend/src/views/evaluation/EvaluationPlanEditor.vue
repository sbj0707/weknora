<template>
  <t-dialog :visible="true" :width="'min(1000px, calc(100vw - 32px))'" :header="t(readonly ? 'evaluationPlan.detail' : mode === 'create' ? 'evaluationPlan.create' : 'evaluationPlan.edit')"
    :confirm-btn="readonly ? null : { content: t('evaluationPlan.save'), loading: saving, disabled: modelsLoading || !!modelsError }"
    :cancel-btn="{ content: t(readonly ? 'evaluationPlan.close' : 'evaluationPlan.cancel'), disabled: saving }"
    :close-btn="!saving" :close-on-overlay-click="!saving" :close-on-esc-keydown="!saving"
    @confirm="save" @close="close">
    <div class="plan-editor">
      <t-alert v-if="error" theme="error" :message="error" />
          <dl v-if="plan" class="plan-metadata">
            <div><dt>{{ t('evaluationPlan.id') }}</dt><dd><code>{{ plan.id }}</code><t-button variant="text" size="small" :aria-label="t('evaluationPlan.copyId')" @click="copyID"><t-icon name="copy" /></t-button></dd></div>
            <div><dt>{{ t('evaluationPlan.creator') }}</dt><dd>{{ plan.creator_name || plan.created_by }}</dd></div>
            <div><dt>{{ t('evaluationPlan.createdAt') }}</dt><dd>{{ date(plan.created_at) }}</dd></div>
            <div><dt>{{ t('evaluationPlan.updatedAt') }}</dt><dd>{{ date(plan.updated_at) }}</dd></div>
          </dl>
      <t-form label-align="top" :disabled="readonly || saving" @submit="save">
        <section class="plan-form-section">
          <h3>{{ t('evaluationPlan.basic') }}</h3>

          <t-form-item :label="t('evaluationPlan.name')" required><t-input v-model="form.name" :maxlength="PLAN_LIMITS.name" show-limit-number /></t-form-item>
          <t-form-item :label="t('evaluationPlan.description')"><t-textarea v-model="form.description" :maxlength="PLAN_LIMITS.description" :autosize="{ minRows: 2, maxRows: 5 }" /></t-form-item>
        </section>
        <t-alert v-if="modelsError" theme="warning" :message="t('evaluationPlan.modelsFailed')">
          <template #operation><t-button variant="text" @click="loadModels">{{ t('evaluationPlan.retry') }}</t-button></template>
        </t-alert>
        <section class="plan-form-section">
          <h3>{{ t('evaluationPlan.generation') }}</h3>
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
        <section class="plan-form-section">
          <h3>{{ t('evaluationPlan.evaluation') }}</h3>
          <t-form-item :label="t('evaluationPlan.evaluationModel')" required><t-select v-model="form.evaluation_model_id" :options="modelOptions(form.evaluation_model_id)" :loading="modelsLoading" filterable :placeholder="t('evaluationPlan.selectModel')" /></t-form-item>
          <t-form-item :label="t('evaluationPlan.comparisonPrompt')" required>
            <div class="plan-prompt">
              <t-textarea v-model="form.answer_comparison_prompt" :maxlength="PLAN_LIMITS.prompt" :autosize="{ minRows: 8, maxRows: 18 }" />
              <p>{{ t('evaluationPlan.variables') }} <code>{{ COMPARISON_VARIABLES }}</code></p>
              <t-button v-if="!readonly" variant="text" size="small" @click="restore('answer_comparison_prompt')">{{ t('evaluationPlan.restorePrompt') }}</t-button>
            </div>
          </t-form-item>
          <div class="plan-dimensions-heading"><h4>{{ t('evaluationPlan.dimensions') }}</h4><span :class="{ 'plan-invalid': weightTotal !== 100 }">{{ t('evaluationPlan.weightTotal', { total: weightTotal }) }}</span></div>
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
  </t-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { DialogPlugin, MessagePlugin } from 'tdesign-vue-next'
import { createEvaluationPlan, updateEvaluationPlan, type EvaluationPlan } from '@/api/evaluation-plan'
import { listModels, type ModelConfig } from '@/api/model'
import { copyToClipboard } from '@/utils/clipboard'
import { defaultPlan, editablePlan, validatePlan, PLAN_LIMITS, QUESTION_TYPES, DIFFICULTIES, QA_LANGUAGES, GENERATION_VARIABLES, COMPARISON_VARIABLES } from './evaluationPlanConfig'
const props = defineProps<{ plan?: EvaluationPlan; mode: 'create' | 'view' | 'edit' }>()
const emit = defineEmits<{ close: []; saved: [plan: EvaluationPlan] }>()
const { t, locale } = useI18n()
const readonly = computed(() => props.mode === 'view')
const form = ref(props.plan ? editablePlan(props.plan) : defaultPlan(locale.value))
const original = JSON.stringify(form.value)
const saving = ref(false), error = ref(''), modelsLoading = ref(true), modelsError = ref(false)
const models = ref<ModelConfig[]>([])
const weightTotal = computed(() => form.value.scoring_dimensions.reduce((sum, d) => sum + (Number(d.weight) || 0), 0))
let alive = true, modelRequest = 0
const confirmations = new Set<ReturnType<typeof DialogPlugin.confirm>>()
onBeforeUnmount(() => { alive = false; modelRequest++; for (const d of confirmations) d.destroy() })
function confirm(message: string, callback: () => void) {
  const dialog = DialogPlugin.confirm({ header: t('evaluationPlan.confirm'), body: message,
    confirmBtn: t('evaluationPlan.confirm'), cancelBtn: t('evaluationPlan.cancel'),
    onConfirm: () => { dialog.destroy(); confirmations.delete(dialog); if (alive) callback() },
    onClose: () => { dialog.destroy(); confirmations.delete(dialog) },
  }); confirmations.add(dialog)
}
function close() {
  if (saving.value) return
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
  if (invalid) { error.value = t(`evaluationPlan.validation.${invalid}`); return }
  saving.value = true; error.value = ''
  try {
    const saved = props.mode === 'create' ? await createEvaluationPlan(input) : await updateEvaluationPlan(props.plan!.id, input)
    if (!alive) return
    void MessagePlugin.success(t('evaluationPlan.saved')); emit('saved', saved)
  } catch { if (alive) error.value = t('evaluationPlan.saveFailed') }
  finally { if (alive) saving.value = false }
}
</script>

<style scoped lang="less">
.plan-editor { max-height: 70vh; overflow-y: auto; padding: 0 8px; }
.plan-form-section { padding: 16px 0; border-bottom: 1px solid var(--td-component-stroke); h3 { margin: 0 0 20px; font-size: var(--app-text-xl); font-weight: 500; } }
.plan-form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.plan-prompt { width: 100%; p { color: var(--td-text-color-secondary); font-size: var(--app-text-sm); overflow-wrap: anywhere; line-height: 20px; } }
.plan-metadata { margin: 0 0 20px; font-size: var(--app-text-sm); div { display: flex; gap: 12px; margin-bottom: 6px; } dt { flex: 0 0 80px; color: var(--td-text-color-secondary); } dd { margin: 0; min-width: 0; overflow-wrap: anywhere; } }
.plan-dimensions-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; h4 { margin: 0; font-size: var(--app-text-lg); font-weight: 500; } }
.plan-invalid { color: var(--td-error-color); }
.plan-field-hint { color: var(--td-text-color-secondary); font-size: var(--app-text-sm); }
.plan-dimension { border: 1px solid var(--td-component-stroke); border-radius: var(--app-radius-sm); padding: 16px; margin: 16px 0; min-width: 0; legend { font-size: var(--app-text-base); color: var(--td-text-color-secondary); padding: 0 6px; } }
.plan-pass-score { margin-top: 24px; }
@media (max-width: 768px) { .plan-form-grid { grid-template-columns: 1fr; gap: 0; } .plan-metadata div { flex-wrap: wrap; } }
</style>
