import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { parse } from '@vue/compiler-sfc'
import { useDocumentChunkRelations } from '../../composables/useDocumentChunkRelations.ts'
import { chunkRelations } from '../../utils/documentChunkRelations.ts'
import { parseSourceLocators } from '../../utils/sourceLocator.ts'

// Execute the page's setup with the real async loader and reactive route.
const source = readFileSync(new URL('./DocumentChunkRelations.vue', import.meta.url), 'utf8')
const script = parse(source).descriptor.scriptSetup!.content
const ast = ts.createSourceFile('page.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const code = ts.transpileModule(ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getFullText(ast)).join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText
const chunk = (id: string, doc = 'doc', page = 8) => ({ id, knowledge_id: doc, knowledge_base_id: 'kb', chunk_index: page,
  chunk_type: 'text', content: `Content ${id}`, content_revision: 0, is_enabled: true, index_status: 'ready',
  source_locators: [{ type: 'pdf', mapping: 'exact', source_hash: 'hash', page }] })
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); await nextTick(); await nextTick() }
function setup(t: test.TestContext, query: Record<string, any> = { chunk_id: 'cited' }) {
  const route = reactive({ params: { knowledgeId: 'doc', kbId: 'kb' }, query })
  const scope = effectScope(); t.after(() => scope.stop())
  const pending: { id: string; resolve: (response: any) => void }[] = []
  const api = {
    document: async (id: string) => ({ data: { id, knowledge_base_id: 'kb', file_hash: 'hash' } }),
    chunks: (id: string) => new Promise(resolve => pending.push({ id, resolve })),
    detail: async () => { throw new Error('Unexpected detail read') },
  }
  const state = scope.run(() => vm.runInNewContext(`${code}; ({ focusedChunkId, locate, selection, selectedChunks, targetMissing, openChunks, selectSource, focusChunk, refresh, outline, onLocated, locateMessage })`, {
    computed, ref, watch, nextTick, useRoute: () => route, useRouter: () => ({}), useI18n: () => ({ t: (key: string) => key }),
    useDocumentChunkRelations, getKnowledgeDetails: api.document, listDocumentChunks: api.chunks, getDocumentChunkDetail: api.detail,
    chunkRelations, parseSourceLocators,
  }))!
  const complete = (rows = [chunk('first', 'doc', 1), chunk('cited')], index = pending.length - 1) => pending[index]!.resolve({ success: true, total: rows.length, data: rows })
  return { route, state, complete, pending }
}

test('a reference waits for all chunks, focuses its exact chunk and locates the original page', async t => {
  const { state, complete } = setup(t)
  const scrolls: number[] = []
  state.outline.value = {
    scrollTop: 20,
    clientHeight: 400,
    getBoundingClientRect: () => ({ top: 100 }),
    querySelector: () => ({ getBoundingClientRect: () => ({ top: 400, height: 100 }) }),
    scrollTo: ({ top }: { top: number }) => scrolls.push(top),
  }
  assert.equal(state.focusedChunkId.value, '')
  complete(); await flush()
  assert.equal(state.focusedChunkId.value, 'cited')
  assert.equal(state.selectedChunks.value.length, 1)
  assert.equal(state.selectedChunks.value[0].id, 'cited')
  assert.equal(state.selection.value.page, 8)
  assert.equal(state.locate.value.locators[0].page, 8)
  assert.equal(state.openChunks.value.has('cited'), true)
  assert.deepEqual(scrolls, [170], 'the selected card is centered within the chunk list')
  state.onLocated({ found: true, precise: false, page: 8, granularity: 'page' })
  assert.equal(state.focusedChunkId.value, 'cited', 'source positioning keeps the cited chunk selected')
  assert.equal(state.locateMessage.value, 'chat.referenceSourceFoundPage')
  state.selectSource({ type: 'pdf', page: 1 }); await flush()
  assert.equal(state.focusedChunkId.value, '', 'manual page selection is preserved')
  assert.equal(state.selectedChunks.value[0].id, 'first')
})

test('deleted or foreign chunk IDs show unavailable instead of focusing the first chunk', async t => {
  const { state, route, complete } = setup(t, { chunk_id: 'deleted' })
  complete(); await flush()
  assert.equal(state.targetMissing.value, true)
  assert.equal(state.focusedChunkId.value, '')
  assert.equal(state.locate.value, null)
  route.query.chunk_id = 'cited'; await flush()
  assert.equal(state.focusedChunkId.value, 'cited')
  route.query = {}; await flush()
  assert.equal(state.focusedChunkId.value, '')
  assert.equal(state.targetMissing.value, false)
})

test('changing document discards old loads and applies only the new reference', async t => {
  const { state, route, complete } = setup(t)
  route.params.knowledgeId = 'new'; route.query.chunk_id = 'new-chunk'; await flush()
  complete([chunk('new-chunk', 'new', 3)]); await flush()
  complete([chunk('cited')], 0); await flush()
  assert.equal(state.focusedChunkId.value, 'new-chunk')
  assert.equal(state.locate.value.locators[0].page, 3)
  state.refresh(); await flush()
  complete([chunk('new-chunk', 'new', 4)]); await flush()
  assert.equal(state.locate.value.locators[0].page, 4, 'refresh reapplies the reference to current chunks')
})

test('stale source locators keep the selected chunk but never scroll to a stale page', async t => {
  const { state, complete } = setup(t)
  const row = chunk('cited'); row.source_locators[0]!.source_hash = 'old-hash'
  complete([row]); await flush()
  assert.equal(state.focusedChunkId.value, 'cited')
  assert.equal(state.locate.value.unavailable, true)
  assert.equal(state.locate.value.locators.length, 0)
  assert.equal(state.selection.value, null)
  state.onLocated({ found: false, precise: false, reason: 'unavailable' })
  assert.equal(state.locateMessage.value, 'chat.referenceSourceStale')
})

test('a document without a requested chunk retains the normal source selection view', async t => {
  const { state, complete } = setup(t, {})
  complete(); await flush()
  assert.equal(state.targetMissing.value, false)
  assert.equal(state.focusedChunkId.value, '')
  assert.equal(state.locate.value, null)
})
