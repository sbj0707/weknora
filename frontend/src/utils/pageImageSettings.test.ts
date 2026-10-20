import assert from 'node:assert/strict'
import test from 'node:test'
import { readPageImageSettings, savePageImageSettings } from './pageImageSettings'

test('legacy knowledge bases default both page image switches to off', () => {
  assert.deepEqual(readPageImageSettings(), { usePageImagesForAnswer: false, autoSavePageImages: false })
})

test('confirming unchanged switches does not update the knowledge base', async () => {
  const settings = readPageImageSettings()
  await savePageImageSettings('kb', settings, settings, {
    load: async () => { throw new Error('must not load') },
    save: async () => { throw new Error('must not save') },
  })
})

test('confirmation saves both switches and preserves the latest unrelated settings', async () => {
  const current = {
    name: 'Renamed knowledge base', description: 'Latest description',
    image_processing_config: { model_id: 'current-model', image_attrs_enabled: true, future_field: 'retain' },
  }
  let saved: unknown
  const updated = await savePageImageSettings('kb', readPageImageSettings(), {
    usePageImagesForAnswer: true, autoSavePageImages: true,
  }, {
    load: async (id) => { assert.equal(id, 'kb'); return current },
    save: async (id, data) => { assert.equal(id, 'kb'); saved = data },
  })
  assert.deepEqual(saved, {
    name: current.name, description: current.description,
    config: { image_processing_config: {
      ...current.image_processing_config, use_page_images_for_answer: true, auto_save_page_images: true,
    } },
  })
  assert.equal(updated?.image_processing_config?.use_page_images_for_answer, true)
  assert.equal('use_page_images_for_answer' in current.image_processing_config, false, 'does not mutate the loaded object')
})

test('only changed switches overwrite current values when another editor updates the knowledge base', async () => {
  let config: unknown
  await savePageImageSettings('kb', readPageImageSettings(), {
    usePageImagesForAnswer: true, autoSavePageImages: false,
  }, {
    load: async () => ({ name: 'KB', image_processing_config: { auto_save_page_images: true } }),
    save: async (_, data) => { config = data.config.image_processing_config },
  })
  assert.deepEqual(config, { use_page_images_for_answer: true, auto_save_page_images: true })
})

test('disabling answer images retains the hidden automatic-save preference', async () => {
  let config: unknown
  const initial = { usePageImagesForAnswer: true, autoSavePageImages: true }
  await savePageImageSettings('kb', initial, { ...initial, usePageImagesForAnswer: false }, {
    load: async () => ({ name: 'KB', image_processing_config: { use_page_images_for_answer: true, auto_save_page_images: true } }),
    save: async (_, data) => { config = data.config.image_processing_config },
  })
  assert.deepEqual(config, { use_page_images_for_answer: false, auto_save_page_images: true })
})

test('load and save failures reject confirmation instead of silently dropping the settings', async () => {
  const edited = { usePageImagesForAnswer: true, autoSavePageImages: true }
  await assert.rejects(savePageImageSettings('kb', readPageImageSettings(), edited, {
    load: async () => { throw new Error('load failed') },
    save: async () => { assert.fail('must not save after load failure') },
  }), /load failed/)
  await assert.rejects(savePageImageSettings('kb', readPageImageSettings(), edited, {
    load: async () => ({ name: 'KB' }),
    save: async () => { throw new Error('permission denied') },
  }), /permission denied/)
})
