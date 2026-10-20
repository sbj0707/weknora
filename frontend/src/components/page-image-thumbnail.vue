<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { downloadPageImage, type PageImageAsset } from '@/api/knowledge-base/page-images'

const props = defineProps<{ knowledgeId: string; image: PageImageAsset }>()
const emit = defineEmits<{ preview: [] }>()
const { t } = useI18n()
const root = ref<HTMLElement | null>(null)
const url = ref(''), loading = ref(false), failed = ref(false)
let observer: IntersectionObserver | undefined
let controller: AbortController | undefined
let generation = 0

function clear() {
  generation++
  observer?.disconnect()
  controller?.abort()
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''; loading.value = false; failed.value = false
}
async function load() {
  if (url.value || loading.value) return
  const token = ++generation
  controller = new AbortController()
  loading.value = true; failed.value = false
  try {
    const blob = await downloadPageImage(props.knowledgeId, props.image.id, controller.signal)
    if (token === generation) url.value = URL.createObjectURL(blob)
  } catch {
    if (token === generation) failed.value = true
  } finally {
    if (token === generation) loading.value = false
  }
}
watch(() => [props.knowledgeId, props.image.id, props.image.updated_at, root.value], () => {
  clear()
  if (!root.value) return
  if (typeof IntersectionObserver === 'undefined') { void load(); return }
  observer = new IntersectionObserver(entries => {
    if (!entries.some(entry => entry.isIntersecting)) return
    observer?.disconnect()
    void load()
  }, { rootMargin: '200px' })
  observer.observe(root.value)
}, { immediate: true, flush: 'post' })
onBeforeUnmount(clear)
</script>

<template>
  <button ref="root" type="button" class="page-image-thumbnail"
    :aria-label="t(failed ? 'pageImages.retryPreview' : 'pageImages.previewPage', { page: image.page_number })"
    :aria-busy="loading" @click="failed ? load() : emit('preview')">
    <img v-if="url" :src="url" :alt="t('pageImages.page', { page: image.page_number })" decoding="async" />
    <span v-else-if="failed" class="thumbnail-error">{{ t('pageImages.retryPreview') }}</span>
    <t-loading v-else size="small" />
    <span class="thumbnail-caption">{{ t('pageImages.page', { page: image.page_number }) }} · {{ t('pageImages.preview') }}</span>
  </button>
</template>

<style scoped>
.page-image-thumbnail { position: relative; display: flex; justify-content: center; align-items: center; width: 100%; height: 220px; padding: 10px 10px 34px; border: 1px solid var(--td-component-stroke); border-radius: var(--app-radius-md); background: var(--td-bg-color-page); color: var(--td-text-color-secondary); cursor: zoom-in; overflow: hidden; }
.page-image-thumbnail:hover { border-color: var(--td-brand-color); }
.page-image-thumbnail:focus-visible { outline: 2px solid var(--td-brand-color); outline-offset: 2px; }
.page-image-thumbnail img { display: block; max-width: 100%; max-height: 100%; object-fit: contain; }
.thumbnail-caption { position: absolute; bottom: 0; left: 0; right: 0; padding: 5px 8px; background: var(--td-bg-color-container); font-size: 12px; text-align: center; }
.thumbnail-error { color: var(--td-error-color); font-size: 12px; }
</style>
