/** One OCR trigger condition: attribute `prop` equals `is`. */
export interface ImageAttrConditionLike {
  prop: string
  is: string
}

/** The fields of the KB editor that feed image_processing_config. */
export interface ImageProcessingEdits {
  usePageImagesForAnswer?: boolean
  autoSavePageImages?: boolean
  imageAttrsEnabled: boolean
  onUnobserved: boolean
  /** The registry's current default OCR conditions (GET /image-attrs/schema). */
  defaultOn: ImageAttrConditionLike[]
}

/**
 * Builds the image_processing_config the KB editor saves, or null when nothing
 * changed. The backend replaces the whole object, so every field the editor
 * does not own (model_id, ...) is carried over from the snapshot.
 *
 * The editor only edits the observation switch and on_unobserved, but
 * on_unobserved must travel with a non-empty `on`: the backend adopts a custom
 * OCR clause only when `on` is set. A knowledge base whose `on` was customised
 * through the API keeps it; one without a custom list gets the registry's
 * current default, so it still follows the default as it evolves.
 */
export function buildImageProcessingConfig(
  snapshot: Record<string, unknown> | null | undefined,
  edits: ImageProcessingEdits,
): Record<string, unknown> | null {
  const snap = snapshot || {}
  const storedOn = (snap.image_actions as { ocr?: { on?: unknown } } | undefined)?.ocr?.on
  const on = Array.isArray(storedOn) && storedOn.length > 0 ? storedOn : edits.defaultOn
  const built = {
    ...snap,
    ...(edits.usePageImagesForAnswer !== undefined ? { use_page_images_for_answer: edits.usePageImagesForAnswer } : {}),
    ...(edits.autoSavePageImages !== undefined ? { auto_save_page_images: edits.autoSavePageImages } : {}),
    image_attrs_enabled: edits.imageAttrsEnabled,
    image_actions: {
      ocr: {
        on,
        on_unobserved: edits.onUnobserved,
      },
    },
  }
  return JSON.stringify(built) === JSON.stringify(snap) ? null : built
}
