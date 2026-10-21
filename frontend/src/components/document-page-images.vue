<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { MessagePlugin } from 'tdesign-vue-next'
import DocumentPreview from '@/components/document-preview.vue'
import PageImageThumbnail from '@/components/page-image-thumbnail.vue'
import { downloadPageImage, generatePageImages, getPageImages, type PageImageAsset, type PageImageState } from '@/api/knowledge-base/page-images'
import type { SourceSelection } from '@/utils/documentChunkRelations'

const props = defineProps<{
  knowledgeId: string; chunkId?: string; revision?: number; writable?: boolean; disabled?: boolean
  thumbnails?: boolean; showWhenDisabled?: boolean
}>()
const { t } = useI18n()
const state = ref<PageImageState | null>(null), loading = ref(false), submitting = ref(false), error = ref('')
const choosing = ref(false), selectedPages = ref<number[]>([]), selection = ref<SourceSelection | null>(null)
const previewVisible = ref(false), previewURL = ref(''), previewError = ref(''), previewTitle = ref('')
let timer: ReturnType<typeof setTimeout> | undefined, controller: AbortController | undefined
let generation = 0, imageGeneration = 0
const images = computed(() => (props.chunkId ? (state.value?.images || []).filter(row => state.value?.pages.includes(row.page_number)) : state.value?.images || [])
  .slice().sort((a, b) => a.page_number - b.page_number))
const done = computed(() => images.value.filter(row => row.status === 'completed').length)
const busy = computed(() => images.value.some(row => ['pending', 'processing'].includes(row.status)))
function stop() { clearTimeout(timer); controller?.abort() }
async function refresh() {
  stop()
  if (!props.knowledgeId) return
  const token = ++generation
  controller = new AbortController()
  loading.value = true
  try {
    const result = await getPageImages(props.knowledgeId, props.chunkId, controller.signal)
    if (token !== generation) return
    state.value = result; error.value = ''
    if (busy.value) timer = setTimeout(refresh, 4000)
  } catch (e: any) {
    if (token === generation && !controller?.signal.aborted) error.value = e?.message || t('pageImages.error')
  } finally { if (token === generation) loading.value = false }
}
async function generate(force = false, manual = false) {
  if (!state.value || props.disabled || submitting.value) return
  stop(); generation++; loading.value = false
  const id = props.knowledgeId, chunk = props.chunkId, revision = props.revision
  submitting.value = true; error.value = ''
  try {
    const result = await generatePageImages(id, {
      source_hash: state.value.source_hash,
      ...(chunk ? { chunk_id: chunk, content_revision: props.revision ?? 0 } : {}),
      force, manual, ...(manual ? { pages: selectedPages.value } : {}),
    })
    if (id !== props.knowledgeId || chunk !== props.chunkId || revision !== props.revision) return
    state.value = result; choosing.value = false
    MessagePlugin.success(t('pageImages.submitted'))
    await refresh()
  } catch (e: any) { if (id === props.knowledgeId && chunk === props.chunkId) error.value = e?.message || t('pageImages.error') }
  finally { submitting.value = false }
}
function choose() { selectedPages.value = [...(state.value?.pages || [])]; selection.value = null; choosing.value = true }
function selectPage(value: SourceSelection) {
  if (value.type !== 'pdf' || !value.page) return
  selection.value = value
  selectedPages.value = [...new Set([...selectedPages.value, value.page])].sort((a, b) => a - b)
}
function clearPreview() { if (previewURL.value) URL.revokeObjectURL(previewURL.value); previewURL.value = '' }
async function preview(row: PageImageAsset) {
  const token = ++imageGeneration
  clearPreview(); previewVisible.value = true; previewError.value = ''; previewTitle.value = t('pageImages.page', { page: row.page_number })
  try {
    const blob = await downloadPageImage(props.knowledgeId, row.id)
    if (token === imageGeneration && previewVisible.value) previewURL.value = URL.createObjectURL(blob)
  } catch (e: any) { if (token === imageGeneration) previewError.value = e?.message || t('pageImages.unavailable') }
}
watch(previewVisible, visible => { if (!visible) { imageGeneration++; clearPreview() } })
watch(() => [props.knowledgeId, props.chunkId, props.revision], () => {
  generation++; stop(); state.value = null; choosing.value = false; previewVisible.value = false; void refresh()
}, { immediate: true })
onBeforeUnmount(() => { generation++; imageGeneration++; stop(); clearPreview() })
</script>

<template>
  <section v-if="state?.enabled || error || showWhenDisabled" class="page-images">
    <div class="page-image-heading"><strong>{{ t(thumbnails ? 'pageImages.previewTitle' : 'pageImages.title') }}</strong>
      <t-button size="small" variant="text" :loading="loading" @click="refresh">{{ t('pageImages.refresh') }}</t-button>
    </div>
    <p v-if="error" role="alert" class="page-image-error">{{ error }}</p>
    <p v-if="state && !state.supported">{{ t('pageImages.unsupported') }}</p>
    <p v-if="loading && !state">{{ t('common.loading') }}</p>
    <template v-else-if="state?.supported && (state.enabled || showWhenDisabled)">
      <p v-if="!state.enabled">{{ t('pageImages.evidenceDisabled') }}</p>
      <p v-if="chunkId">{{ state.pages.length ? t(state.manual ? 'pageImages.manual' : 'pageImages.automatic') + ' · ' + state.pages.join(', ') : t('pageImages.unmapped') }}</p>
      <p v-if="disabled">{{ t('pageImages.unsaved') }}</p>
      <div v-if="writable && state.enabled" class="page-image-actions">
        <t-button size="small" variant="outline" :disabled="disabled || !!chunkId && !state.pages.length" :loading="submitting" @click="generate()">{{ t(chunkId ? 'pageImages.save' : 'pageImages.backfill') }}</t-button>
        <t-button v-if="images.length" size="small" variant="outline" :disabled="disabled || busy" :loading="submitting" @click="generate(true)">{{ t('pageImages.regenerate') }}</t-button>
        <t-button v-if="chunkId" size="small" variant="text" :disabled="disabled || submitting" @click="choose">{{ t('pageImages.select') }}</t-button>
      </div>
      <p>{{ images.length ? t('pageImages.progress', { done, total: images.length }) : t('pageImages.empty') }}</p>
      <div class="page-image-list" :class="{ 'page-image-gallery': thumbnails }">
        <div v-for="row in images" :key="row.id" :class="{ 'page-image-card': thumbnails }">
          <PageImageThumbnail v-if="thumbnails && row.status === 'completed'" :knowledge-id="knowledgeId" :image="row" @preview="preview(row)" />
          <div v-else-if="thumbnails" class="page-image-placeholder"><t-icon name="image" size="28px" /></div>
          <div class="page-image-row">
            <span>{{ t('pageImages.page', { page: row.page_number }) }}</span>
            <span :class="{ 'page-image-error': row.status === 'failed' }">{{ t(`pageImages.${row.status}`) }}</span>
            <small v-if="row.error_message">{{ row.error_message }}</small>
            <t-button v-if="row.status === 'completed'" size="small" variant="text" @click="preview(row)">{{ t('pageImages.preview') }}</t-button>
          </div>
        </div>
      </div>
    </template>
    <t-dialog v-model:visible="choosing" :header="t('pageImages.select')" width="min(1100px, 95vw)" :z-index="2500"
      :confirm-btn="{ content: t('pageImages.confirm'), loading: submitting, disabled: !selectedPages.length || disabled }"
      :cancel-btn="t('pageImages.cancel')" :close-on-overlay-click="false" @confirm="generate(false, true)">
      <p>{{ t('pageImages.selectHint') }}</p>
      <p v-if="error" class="page-image-error" role="alert">{{ error }}</p>
      <div class="page-image-actions"><span>{{ t('pageImages.selected') }}:</span>
        <t-tag v-for="page in selectedPages" :key="page" closable @close="selectedPages = selectedPages.filter(p => p !== page)">{{ page }}</t-tag>
      </div>
      <div v-if="choosing" class="page-image-source">
        <DocumentPreview :knowledge-id="knowledgeId" file-type="pdf" file-name="source.pdf" :active="choosing"
          source-mode selectable page-navigation fill-height :source-selection="selection" @source-select="selectPage" />
      </div>
    </t-dialog>
    <t-dialog v-model:visible="previewVisible" :header="previewTitle" width="min(1100px, 96vw)" :z-index="2600" :footer="false">
      <p v-if="previewError" role="alert">{{ previewError }}</p>
      <img v-else-if="previewURL" class="page-image-preview" :src="previewURL" :alt="previewTitle" />
      <t-loading v-else />
    </t-dialog>
  </section>
</template>

<style scoped>
.page-images { border: 1px solid var(--td-component-border, #e7e7e7); border-radius: 6px; padding: 12px 16px; margin: 12px 0; font-size: 13px; }
.page-image-heading, .page-image-actions, .page-image-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.page-image-heading { justify-content: space-between; }
.page-images p { color: var(--td-text-color-secondary); margin: 8px 0; }
.page-image-error { color: var(--td-error-color) !important; }
.page-image-list { max-height: 220px; overflow: auto; }
.page-image-gallery { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr)); gap: 12px; max-height: none; overflow: visible; }
.page-image-card { min-width: 0; }
.page-image-placeholder { height: 220px; display: flex; justify-content: center; align-items: center; border: 1px dashed var(--td-component-stroke); border-radius: var(--app-radius-md); color: var(--td-text-color-placeholder); background: var(--td-bg-color-page); box-sizing: border-box; }
.page-image-row { min-height: 30px; }
.page-image-row small { color: var(--td-text-color-secondary); }
.page-image-source { height: 62vh; margin-top: 12px; }
.page-image-preview { display: block; max-width: 100%; max-height: 78vh; object-fit: contain; margin: auto; }
</style>
