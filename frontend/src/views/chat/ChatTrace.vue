<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getChatTrace } from '@/api/chat-trace'
import { useChatTrace } from '@/composables/useChatTrace'
import { formatTraceValue, observationDuration, traceRows, traceSummary, traceTime } from '@/utils/chatTrace'

const route = useRoute(), router = useRouter(), { t } = useI18n()
const sessionId = computed(() => String(route.params.sessionId || ''))
const messageId = computed(() => String(route.params.messageId || ''))
const { result, loading, failed, missing, load } = useChatTrace(sessionId, messageId, getChatTrace)
const trace = computed(() => result.value?.trace)
const rows = computed(() => traceRows(trace.value?.observations || []))
const summary = computed(() => trace.value ? traceSummary(trace.value) : null)
const selectedId = ref('')
const tab = ref('input')
const selected = computed(() => rows.value.find(row => row.observation.id === selectedId.value)?.observation)
watch(trace, () => {
  selectedId.value = rows.value.find(row => row.observation.name === 'agent.execute')?.observation.id || rows.value[0]?.observation.id || ''
  tab.value = 'input'
})
const agent = computed(() => trace.value?.observations.find(item => item.name === 'agent.execute'))
const question = computed(() => (agent.value?.input as { query?: string } | undefined)?.query || '')
const answer = computed(() => {
  // The model output retains source handles; the agent output may contain expanded private source tags.
  const rounds = rows.value.filter(row => row.observation.type === 'GENERATION' && row.observation.name.startsWith('chat.'))
  const final = [...rounds].reverse().find(row => (row.observation.output as { finish_reason?: string })?.finish_reason === 'stop' && row.observation.parentObservationId !== rows.value[0]?.observation.id)
  return (final?.observation.output as { content?: string } | undefined)?.content || ''
})
const start = computed(() => Math.min(...rows.value.map(row => traceTime(row.observation.startTime)).filter(Boolean)))
const end = computed(() => Math.max(...rows.value.map(row => traceTime(row.observation.endTime) || traceTime(row.observation.startTime))))
function bar(item: typeof selected.value) {
  if (!item || !Number.isFinite(start.value)) return {}
  const span = Math.max(1, end.value - start.value)
  const left = Math.max(0, Math.min(100, (traceTime(item.startTime) - start.value) / span * 100))
  return { left: `${left}%`, width: `${Math.max(.5, Math.min(100 - left, observationDuration(item) / span * 100))}%` }
}
const duration = (ms: number) => ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(3)} s`
const detail = computed(() => {
  if (!selected.value) return '—'
  return formatTraceValue(tab.value === 'input' ? selected.value.input : tab.value === 'output' ? selected.value.output : selected.value.metadata)
})
const emptyMessage = computed(() => missing.value ? t('chat.trace.messageMissing') : failed.value ? t('chat.trace.loadFailed')
  : result.value?.state === 'disabled' ? t('chat.trace.disabled') : t('chat.trace.notFound'))
function back() { void router.push(`/platform/chat/${encodeURIComponent(sessionId.value)}`) }
</script>

<template>
  <main class="chat-trace">
    <header class="chat-trace__header">
      <router-link class="chat-trace__home" :to="{ name: 'knowledgeBaseList' }"><t-icon name="home" />{{ t('knowledgeBase.sourceChunks.backHome') }}</router-link>
      <t-button variant="text" shape="square" :title="t('common.back')" :aria-label="t('common.back')" @click="back"><t-icon name="chevron-left" /></t-button>
      <div class="chat-trace__heading"><h1>{{ t('chat.trace.title') }}</h1><p>{{ result?.request_id || messageId }}</p></div>
      <t-button variant="outline" :loading="loading" @click="load"><t-icon name="refresh" />{{ t('common.refresh') }}</t-button>
    </header>
    <div v-if="loading" class="chat-trace__empty"><t-loading />{{ t('common.loading') }}</div>
    <div v-else-if="!trace || result?.state !== 'ready'" class="chat-trace__empty" role="status">{{ emptyMessage }}</div>
    <template v-else>
      <section class="chat-trace__overview">
        <div class="chat-trace__ids">
          <span>{{ t('chat.requestInfoSessionId') }} <code>{{ result.session_id }}</code></span>
          <span>{{ t('chat.requestInfoMessageId') }} <code>{{ result.message_id }}</code></span>
          <span>Trace ID <code>{{ trace.id }}</code></span>
          <span>{{ t('chat.requestInfoSentAt') }} {{ new Date(trace.timestamp).toLocaleString() }}</span>
        </div>
        <div v-if="summary" class="chat-trace__stats">
          <div><span>{{ t('chat.trace.duration') }}</span><strong>{{ duration(trace.latency * 1000) }}</strong></div>
          <div><span>{{ t('chat.trace.modelCalls') }}</span><strong>{{ summary.calls }}</strong></div>
          <div><span>{{ t('chat.trace.inputTokens') }}</span><strong>{{ summary.input.toLocaleString() }}</strong></div>
          <div><span>{{ t('chat.trace.outputTokens') }}</span><strong>{{ summary.output.toLocaleString() }}</strong></div>
          <div><span>{{ t('chat.trace.cachedTokens') }}</span><strong>{{ summary.cached.toLocaleString() }}</strong></div>
          <div><span>{{ t('chat.trace.errors') }}</span><strong>{{ summary.errors }}</strong></div>
        </div>
        <p class="chat-trace__hint">{{ t('chat.trace.timingHint') }}</p>
        <details v-if="question || answer" class="chat-trace__conversation" open>
          <summary>{{ t('chat.trace.conversation') }}</summary>
          <p v-if="question">{{ question }}</p><pre v-if="answer">{{ answer }}</pre>
        </details>
      </section>
      <div v-if="!rows.length" class="chat-trace__empty">{{ t('chat.trace.noObservations') }}</div>
      <div v-else class="chat-trace__layout">
        <section class="chat-trace__tree" :aria-label="t('chat.trace.steps')">
          <div class="chat-trace__tree-heading"><h2>{{ t('chat.trace.steps') }}</h2><span>{{ t('chat.trace.duration') }}</span></div>
          <button v-for="row in rows" :key="row.observation.id" type="button" class="chat-trace__row"
            :class="{ 'is-selected': selectedId === row.observation.id, 'is-error': row.observation.level === 'ERROR' }"
            :aria-pressed="selectedId === row.observation.id" @click="selectedId = row.observation.id">
            <div class="chat-trace__node" :style="{ paddingLeft: `${Math.min(row.depth, 8) * 16}px` }">
              <span class="chat-trace__dot" :class="{ 'is-generation': row.observation.type === 'GENERATION' }" />
              <div><strong>{{ row.observation.name }}</strong><small>{{ row.observation.model || row.observation.type }}</small></div>
            </div>
            <div class="chat-trace__timeline"><i :style="bar(row.observation)" /></div>
            <span class="chat-trace__duration">{{ row.observation.endTime ? duration(observationDuration(row.observation)) : t('chat.trace.inProgress') }}</span>
          </button>
        </section>
        <section v-if="selected" class="chat-trace__detail">
          <h2>{{ selected.name }}</h2>
          <div class="chat-trace__node-info"><span>{{ selected.type }}</span><span v-if="selected.model">{{ selected.model }}</span><span>{{ selected.level }}</span></div>
          <p v-if="selected.statusMessage" class="chat-trace__error">{{ selected.statusMessage }}</p>
          <dl>
            <dt>{{ t('chat.trace.duration') }}</dt><dd>{{ selected.endTime ? duration(observationDuration(selected)) : t('chat.trace.inProgress') }}</dd>
            <template v-if="selected.completionStartTime"><dt>{{ t('chat.trace.firstToken') }}</dt><dd>{{ duration(Math.max(0, traceTime(selected.completionStartTime) - traceTime(selected.startTime))) }}</dd></template>
            <template v-if="selected.type === 'GENERATION'">
              <dt>{{ t('chat.trace.inputTokens') }}</dt><dd>{{ selected.usageDetails?.input ?? '—' }}</dd>
              <dt>{{ t('chat.trace.outputTokens') }}</dt><dd>{{ selected.usageDetails?.output ?? '—' }}</dd>
              <dt>{{ t('chat.trace.cachedTokens') }}</dt><dd>{{ selected.usageDetails?.cache_read_input_tokens ?? '—' }}</dd>
            </template>
          </dl>
          <t-tabs v-model="tab"><t-tab-panel value="input" :label="t('chat.trace.input')" /><t-tab-panel value="output" :label="t('chat.trace.output')" /><t-tab-panel value="metadata" :label="t('chat.trace.metadata')" /></t-tabs>
          <pre class="chat-trace__payload" tabindex="0">{{ detail }}</pre>
        </section>
      </div>
    </template>
  </main>
</template>

<style scoped lang="less">
.chat-trace { height: 100%; min-height: 0; box-sizing: border-box; overflow: auto; padding: 24px; color: var(--td-text-color-primary); background: var(--td-bg-color-page); }
.chat-trace__header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.chat-trace__home { display: inline-flex; align-items: center; gap: 6px; color: var(--td-brand-color); text-decoration: none; white-space: nowrap; &:hover { text-decoration: underline; } &:focus-visible { outline: 2px solid var(--td-brand-color); outline-offset: 4px; border-radius: calc(var(--app-radius-xs) / 2); } }
.chat-trace__heading { flex: 1; min-width: 0; h1 { margin: 0; font-size: var(--app-text-3xl); } p { margin: 5px 0 0; color: var(--td-text-color-secondary); overflow-wrap: anywhere; } }
.chat-trace__overview, .chat-trace__tree, .chat-trace__detail { border: 1px solid var(--td-component-stroke); background: var(--td-bg-color-container); border-radius: var(--app-radius-md, 10px); }
.chat-trace__overview { padding: 18px; margin-bottom: 18px; }
.chat-trace__ids { display: flex; flex-wrap: wrap; gap: 10px 24px; font-size: var(--app-text-sm); color: var(--td-text-color-secondary); code { margin-left: 5px; overflow-wrap: anywhere; } }
.chat-trace__stats { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 16px; margin-top: 22px; > div { display: flex; flex-direction: column; gap: 8px; } span { color: var(--td-text-color-secondary); font-size: var(--app-text-sm); } strong { font-size: var(--app-text-4xl); } }
.chat-trace__hint { color: var(--td-text-color-secondary); font-size: var(--app-text-sm); margin: 14px 0 0; }
.chat-trace__conversation { margin-top: 18px; border-top: 1px solid var(--td-component-stroke); padding-top: 14px; summary { cursor: pointer; font-weight: 600; } p, pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.7; } pre { color: var(--td-text-color-secondary); } }
.chat-trace__layout { display: grid; grid-template-columns: minmax(420px, 1fr) minmax(320px, 1fr); gap: 18px; align-items: start; }
.chat-trace__tree { padding: 12px 0; overflow: hidden; }
.chat-trace__tree-heading { display: flex; align-items: center; justify-content: space-between; padding: 0 16px 12px; h2 { margin: 0; font-size: var(--app-text-base); } span { color: var(--td-text-color-secondary); font-size: var(--app-text-sm); } }
.chat-trace__row { width: 100%; display: grid; grid-template-columns: minmax(150px, 1fr) 80px 75px; align-items: center; gap: 8px; text-align: left; padding: 12px 14px; background: transparent; border: 0; color: inherit; cursor: pointer; &:hover { background: var(--td-bg-color-container-hover); } &.is-selected { background: var(--td-brand-color-light); } &.is-error .chat-trace__dot { background: var(--td-error-color); } &:focus-visible { outline: 2px solid var(--td-brand-color); outline-offset: -2px; } }
.chat-trace__node { display: flex; align-items: center; gap: 8px; min-width: 0; strong { font-size: var(--app-text-sm); overflow-wrap: anywhere; } small { display: block; font-size: var(--app-text-xs); color: var(--td-text-color-secondary); margin-top: 4px; overflow-wrap: anywhere; } }
.chat-trace__dot { width: 7px; height: 7px; border-radius: 50%; background: var(--td-text-color-placeholder); flex-shrink: 0; &.is-generation { background: var(--td-brand-color); } }
.chat-trace__timeline { position: relative; height: 6px; border-radius: var(--app-radius-pill); background: var(--td-bg-color-secondarycontainer); i { position: absolute; height: 6px; border-radius: var(--app-radius-pill); background: var(--td-brand-color); } }
.chat-trace__duration { text-align: right; font-size: var(--app-text-xs); font-variant-numeric: tabular-nums; }
.chat-trace__detail { min-width: 0; padding: 18px; h2 { font-size: var(--app-text-xl); margin: 0 0 12px; overflow-wrap: anywhere; } dl { display: grid; grid-template-columns: auto 1fr; gap: 8px 20px; font-size: var(--app-text-sm); } dt { color: var(--td-text-color-secondary); } dd { margin: 0; } }
.chat-trace__node-info { display: flex; flex-wrap: wrap; gap: 10px; color: var(--td-text-color-secondary); font-size: var(--app-text-sm); }
.chat-trace__payload { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 65vh; overflow: auto; padding: 14px; background: var(--td-bg-color-page); border-radius: var(--app-radius-sm); font-size: var(--app-text-sm); line-height: 1.7; tab-size: 2; }
.chat-trace__error { color: var(--td-error-color); overflow-wrap: anywhere; }
.chat-trace__empty { min-height: 220px; display: flex; align-items: center; justify-content: center; gap: 12px; color: var(--td-text-color-secondary); text-align: center; padding: 24px; }
@media (max-width: 1100px) { .chat-trace__layout { grid-template-columns: 1fr; } .chat-trace__stats { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 600px) { .chat-trace { padding: 12px; } .chat-trace__row { grid-template-columns: minmax(120px, 1fr) 50px 65px; } .chat-trace__stats strong { font-size: var(--app-text-2xl); } }
</style>
