<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ pageCount: number; currentPage: number }>()
const emit = defineEmits<{ navigate: [page: number] }>()
const { t } = useI18n()
const pageInput = ref<number | string>(props.currentPage)
const list = ref<HTMLElement | null>(null)
let resizeObserver: ResizeObserver | undefined

function navigate(value: number | string) {
  const page = Number(value)
  if (Number.isInteger(page) && page >= 1 && page <= props.pageCount) {
    emit('navigate', page)
    if (page === props.currentPage) void centerCurrentPage()
  }
}

async function centerCurrentPage() {
  await nextTick()
  const box = list.value
  const button = box?.querySelector<HTMLElement>(`[data-nav-page="${props.currentPage}"]`)
  if (!box || !button) return
  // Scroll only the page list; keep the original document in place.
  box.scrollLeft = button.offsetLeft + button.offsetWidth / 2 - box.clientWidth / 2
}

watch(() => [props.currentPage, props.pageCount], () => {
  pageInput.value = props.currentPage
  void centerCurrentPage()
}, { immediate: true })

onMounted(() => {
  resizeObserver = new ResizeObserver(() => { void centerCurrentPage() })
  if (list.value) resizeObserver.observe(list.value)
})
onBeforeUnmount(() => resizeObserver?.disconnect())
</script>

<template>
  <nav class="source-page-navigation" :aria-label="t('preview.pageNavigation')">
    <header>
      <strong>{{ t('preview.pageNavigation') }}</strong>
      <span>{{ t('preview.totalPages', { count: pageCount }) }}</span>
    </header>
    <div class="source-page-navigation__arrows">
      <t-button variant="text" size="small" shape="square" :disabled="currentPage <= 1"
        :title="t('preview.previousPage')" :aria-label="t('preview.previousPage')" @click="navigate(currentPage - 1)">
        <template #icon><t-icon name="chevron-left" /></template>
      </t-button>
      <t-button variant="text" size="small" shape="square" :disabled="currentPage >= pageCount"
        :title="t('preview.nextPage')" :aria-label="t('preview.nextPage')" @click="navigate(currentPage + 1)">
        <template #icon><t-icon name="chevron-right" /></template>
      </t-button>
    </div>
    <div ref="list" class="source-page-navigation__list"
      :style="{ '--page-button-width': `max(34px, calc(${String(pageCount).length}ch + 18px))` }">
      <button v-for="page in pageCount" :key="page" type="button" :data-nav-page="page"
        :aria-label="t('knowledgeBase.sourceChunks.page', { page })" :aria-current="page === currentPage ? 'page' : undefined"
        :class="{ 'is-current': page === currentPage }" @click="navigate(page)">{{ page }}</button>
    </div>
    <form class="source-page-navigation__jump" @submit.prevent="navigate(pageInput)">
      <input v-model="pageInput" type="number" min="1" :max="pageCount" step="1" required
        :aria-label="t('preview.goToPage')" :title="t('preview.goToPage')" />
      <button type="submit" :aria-label="t('preview.goToPage')" :title="t('preview.goToPage')">↵</button>
    </form>
  </nav>
</template>

<style scoped lang="less">
.source-page-navigation { flex: 0 0 auto; width: 100%; min-width: 0; display: flex; align-items: center; gap: 8px; padding: 8px; box-sizing: border-box; border-bottom: 1px solid var(--td-component-stroke); background: var(--td-bg-color-container); color: var(--td-text-color-primary); }
header { flex-shrink: 0; max-width: 86px; display: flex; flex-direction: column; gap: 4px; text-align: center; font-size: 12px; }
header span { color: var(--td-text-color-secondary); font-size: 11px; }
.source-page-navigation__arrows { display: flex; flex-shrink: 0; }
.source-page-navigation__jump { display: flex; width: 64px; border: 1px solid var(--td-component-stroke); border-radius: 4px; overflow: hidden; flex-shrink: 0; }
input { min-width: 0; width: 100%; padding: 6px 2px; border: 0; background: var(--td-bg-color-container); color: inherit; text-align: center; font-size: 12px; appearance: textfield; }
input::-webkit-inner-spin-button, input::-webkit-outer-spin-button { appearance: none; margin: 0; }
button { cursor: pointer; color: inherit; font: inherit; }
.source-page-navigation__jump button { border: 0; background: var(--td-bg-color-secondarycontainer); padding: 0 5px; }
.source-page-navigation__list { position: relative; flex: 1; min-width: 0; overflow-x: auto; overflow-y: hidden; display: flex; padding: 2px; font-size: 12px; }
// Edge spacing lets even the first and last page sit in the center.
.source-page-navigation__list::before, .source-page-navigation__list::after { content: ''; flex: 0 0 max(0px, calc(50% - var(--page-button-width) / 2)); }
.source-page-navigation__list button { flex: 0 0 var(--page-button-width); width: var(--page-button-width); box-sizing: border-box; height: 32px; padding: 0 8px; border: 1px solid var(--td-component-stroke); border-radius: 4px; background: var(--td-bg-color-container); font-variant-numeric: tabular-nums; font-size: 12px; }
.source-page-navigation__list button + button { margin-left: 6px; }
.source-page-navigation__list button:hover { border-color: var(--td-brand-color); }
.source-page-navigation__list button.is-current { color: var(--td-brand-color); border-color: var(--td-brand-color); background: var(--td-brand-color-light-1); font-weight: 600; }
button:focus-visible, input:focus-visible { outline: 2px solid var(--td-brand-color); outline-offset: -2px; }
</style>
