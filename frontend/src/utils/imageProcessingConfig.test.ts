import assert from 'node:assert/strict'
import test from 'node:test'

import { buildImageProcessingConfig } from './imageProcessingConfig.ts'

const DEFAULT_ON = [
  { prop: 'contain.text', is: 'block' },
  { prop: 'contain.data_visual', is: 'true' },
]

test('saving keeps OCR conditions customised through the API', () => {
  // Regression: the editor always wrote the registry default into `on`, so an
  // unrelated edit replaced a KB's custom OCR conditions with the default table.
  const custom = [{ prop: 'contain.text', is: 'sparse' }]
  const built = buildImageProcessingConfig(
    { model_id: 'vlm-1', image_attrs_enabled: true, image_actions: { ocr: { on: custom, on_unobserved: true } } },
    { imageAttrsEnabled: true, onUnobserved: false, defaultOn: DEFAULT_ON },
  )

  assert.deepEqual(built, {
    model_id: 'vlm-1',
    image_attrs_enabled: true,
    image_actions: { ocr: { on: custom, on_unobserved: false } },
  })
})

test('a KB without custom conditions gets the registry default alongside on_unobserved', () => {
  const built = buildImageProcessingConfig(
    { model_id: 'vlm-1' },
    { imageAttrsEnabled: true, onUnobserved: false, defaultOn: DEFAULT_ON },
  )

  assert.deepEqual(built, {
    model_id: 'vlm-1',
    image_attrs_enabled: true,
    image_actions: { ocr: { on: DEFAULT_ON, on_unobserved: false } },
  })
})

test('an unchanged configuration is not sent', () => {
  const custom = [{ prop: 'contain.text', is: 'sparse' }]
  const snapshot = { image_attrs_enabled: true, image_actions: { ocr: { on: custom, on_unobserved: true } } }

  assert.equal(
    buildImageProcessingConfig(snapshot, { imageAttrsEnabled: true, onUnobserved: true, defaultOn: DEFAULT_ON }),
    null,
  )
})

test('page evidence switches are independent of image observation and preserve other configuration', () => {
  const snapshot = { model_id: 'keep-model', vendor_setting: { custom: true }, image_attrs_enabled: false }
  const built = buildImageProcessingConfig(snapshot, {
    imageAttrsEnabled: false, onUnobserved: true, defaultOn: DEFAULT_ON,
    usePageImagesForAnswer: true, autoSavePageImages: true,
  })
  assert.equal(built?.use_page_images_for_answer, true)
  assert.equal(built?.auto_save_page_images, true)
  assert.equal(built?.image_attrs_enabled, false)
  assert.equal(built?.model_id, 'keep-model')
  assert.deepEqual(built?.vendor_setting, { custom: true })
  const disabled = buildImageProcessingConfig(built, {
    imageAttrsEnabled: false, onUnobserved: true, defaultOn: DEFAULT_ON,
    usePageImagesForAnswer: false, autoSavePageImages: true,
  })
  assert.equal(disabled?.use_page_images_for_answer, false)
  assert.equal(disabled?.auto_save_page_images, true, 'retain the hidden preference; backend gates its effect')
})
