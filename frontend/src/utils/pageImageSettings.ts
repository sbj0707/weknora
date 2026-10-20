import type { ImageProcessingConfig } from '../api/knowledge-base'

export interface PageImageSettings {
  usePageImagesForAnswer: boolean
  autoSavePageImages: boolean
}

interface KnowledgeBaseSettings {
  name: string
  description?: string
  image_processing_config?: ImageProcessingConfig
}

interface PageImageSettingsAPI {
  load: (id: string) => Promise<KnowledgeBaseSettings>
  save: (id: string, data: {
    name: string
    description?: string
    config: { image_processing_config: ImageProcessingConfig }
  }) => Promise<void>
}

export function readPageImageSettings(config?: ImageProcessingConfig): PageImageSettings {
  return {
    usePageImagesForAnswer: !!config?.use_page_images_for_answer,
    autoSavePageImages: !!config?.auto_save_page_images,
  }
}

// These are KB-wide settings, not per-document processing overrides. Load the
// current object before saving so unrelated image settings survive confirmation.
export async function savePageImageSettings(
  id: string,
  initial: PageImageSettings,
  edited: PageImageSettings,
  api: PageImageSettingsAPI,
): Promise<KnowledgeBaseSettings | undefined> {
  const patch: Partial<ImageProcessingConfig> = {}
  if (initial.usePageImagesForAnswer !== edited.usePageImagesForAnswer) {
    patch.use_page_images_for_answer = edited.usePageImagesForAnswer
  }
  if (initial.autoSavePageImages !== edited.autoSavePageImages) {
    patch.auto_save_page_images = edited.autoSavePageImages
  }
  if (!Object.keys(patch).length) return
  const current = await api.load(id)
  const config = { ...current.image_processing_config, ...patch }
  await api.save(id, {
    name: current.name,
    description: current.description,
    config: { image_processing_config: config },
  })
  return { ...current, image_processing_config: config }
}
