import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import * as transfer from '../../utils/knowledgeBaseConfigTransfer.ts'
import { buildImageProcessingConfig } from '../../utils/imageProcessingConfig.ts'

const { descriptor } = parse(readFileSync(new URL('./KnowledgeBaseEditorModal.vue', import.meta.url), 'utf8'))
const compiled = ts.transpileModule(compileScript(descriptor, { id: 'config-import' }).content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText

const stub: any = new Proxy(function () {}, {
  get(_, key) {
    if (key === Symbol.toPrimitive) return () => ''
    if (key === Symbol.iterator) return function* () {}
    if (key === 'then') return undefined
    return stub
  },
  apply: () => stub,
})

function setupEditor(t: test.TestContext, file?: transfer.KnowledgeBaseConfigFile, total = 0) {
  const writes: { kind: string; id: string; payload: any }[] = []
  const errors: unknown[] = []
  const current = {
    id: 'target', name: '目标知识库', type: 'document', summary_model_id: 'chat', embedding_model_id: 'target-embed',
    chunking_config: { chunk_size: 512, chunk_overlap: 80, separators: ['\n'] },
    indexing_strategy: { vector_enabled: true, keyword_enabled: true, wiki_enabled: false, graph_enabled: false },
    storage_provider_config: { provider: 'local' },
    image_processing_config: { image_attrs_enabled: false, model_id: 'existing-image-model' },
  }
  const models = ['chat', 'source-embed', 'target-embed'].map(id => ({ id }))
  const exports: any = {}
  runInNewContext(compiled, {
    exports, console, structuredClone, setTimeout() {},
    require(path: string) {
      if (path === 'vue') return { ...vue, onMounted() {}, onBeforeUnmount() {} }
      if (path === 'vue-i18n') return { useI18n: () => ({ t: (key: string) => key, te: () => false }) }
      if (path.endsWith('/knowledgeBaseConfigTransfer')) return transfer
      if (path.endsWith('/imageProcessingConfig')) return { buildImageProcessingConfig }
      if (path.endsWith('/knowledge-base')) return {
        getKnowledgeBaseById: async () => ({ data: current }),
        listKnowledgeFiles: async () => ({ total }), fetchImageAttrSchema: async () => null,
        mergeImageActions: (config: any) => config || { ocr: { on: [{ prop: 'contain.text', is: 'block' }], on_unobserved: true } },
        FALLBACK_IMAGE_ATTR_SCHEMA: { default_actions: { ocr: { on: [] } } },
        updateKnowledgeBase: async (id: string, payload: any) => { writes.push({ kind: 'base', id, payload }) },
      }
      if (path.endsWith('/initialization')) return { updateKBConfig: async (id: string, payload: any) => { writes.push({ kind: 'config', id, payload }) } }
      if (path.endsWith('/chatResources')) return { useChatResourcesStore: () => ({ allModels: models }) }
      if (path.endsWith('/useModalShell')) return {
        useModalShell: (options: any) => {
          let clean = ''
          return { markClean: () => { clean = JSON.stringify(options.snapshot()) }, isDirty: () => clean !== JSON.stringify(options.snapshot()) }
        },
      }
      if (path === 'tdesign-vue-next') return { MessagePlugin: { success() {}, warning() {}, error: (e: unknown) => errors.push(e) }, DialogPlugin: stub }
      return stub
    },
  })
  const scope = vue.effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() => exports.default.setup(vue.shallowReactive({ visible: true, mode: 'edit', kbId: 'target', importedConfiguration: file }), { expose() {}, emit() {} }))
  state.allModels.value = models
  return { state, writes, errors, current }
}

function configFile() {
  return transfer.exportKnowledgeBaseConfig({ type: 'document', embedding_model_id: 'source-embed',
    chunking_config: { chunk_overlap: 0 },
    indexing_strategy: { graph_enabled: true },
    image_processing_config: { image_attrs_enabled: true, image_actions: { ocr: { on: [{ prop: 'custom', is: 'yes' }], on_unobserved: false } } },
    extract_config: { enabled: true, custom_instructions: '提取项目关系' },
  })
}

test('import loads an unsaved draft, then saves nested settings through existing endpoints', async t => {
  const { state, writes, errors } = setupEditor(t, configFile())
  await state.loadKBData()
  assert.deepEqual(errors, [])
  assert.equal(writes.length, 0, 'loading an import must not mutate the server')
  assert.equal(state.formData.value.name, '目标知识库')
  assert.equal(state.formData.value.chunkingConfig.chunkOverlap, 0)
  assert.equal(state.importApplied.value, true)
  assert.equal(state.modalShell.isDirty(), true, 'closing must warn about discarding the imported draft')
  assert.equal(state.validateForm(), true)
  await state.doSubmit()
  assert.deepEqual(errors, [])
  assert.deepEqual(writes.map(write => [write.kind, write.id]), [['base', 'target'], ['config', 'target']])
  assert.equal(writes[0]!.payload.config.image_processing_config.image_attrs_enabled, true)
  assert.equal(writes[0]!.payload.config.image_processing_config.model_id, 'existing-image-model')
  assert.equal(writes[0]!.payload.config.image_processing_config.image_actions.ocr.on_unobserved, false)
  assert.equal(writes[1]!.payload.documentSplitting.chunkOverlap, 0)
  assert.equal(writes[1]!.payload.nodeExtract.customInstructions, '提取项目关系')
})

test('imported drafts obey embedding and index locks when documents exist', async t => {
  const { state, writes, errors } = setupEditor(t, configFile(), 1)
  await state.loadKBData()
  await state.doSubmit()
  assert.deepEqual(errors, [])
  assert.equal(writes[1]!.payload.embeddingModelId, 'target-embed')
  assert.equal(writes[0]!.payload.config.indexing_strategy.graph_enabled, false)
})

test('ordinary editor opens remain clean and do not inherit an imported draft', async t => {
  const { state, errors } = setupEditor(t)
  await state.loadKBData()
  assert.deepEqual(errors, [])
  assert.equal(state.importApplied.value, false)
  assert.equal(state.formData.value.chunkingConfig.chunkOverlap, 80)
  assert.equal(state.modalShell.isDirty(), false)
})

test('unavailable model references block importing before any settings are written', async t => {
  const file = configFile()
  file.configuration.summary_model_id = 'model-in-another-space'
  const { state, writes } = setupEditor(t, file)
  await state.loadKBData()
  await state.handleSubmit()
  assert.equal(writes.length, 0)
  assert.equal(state.currentSection.value, 'models')
})
