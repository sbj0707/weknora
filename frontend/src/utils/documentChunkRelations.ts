import type { ChunkDetail, ChunkDetailContent } from '../types/chunkDetail'
import { normalizeForMatch, parseSourceLocators, sourceQuoteText, type SourceLocator } from './sourceLocator'

export type SourceSelection = {
  type: 'pdf' | 'docx' | 'slide' | 'sheet' | 'section' | 'text' | 'image' | 'time'
  page?: number
  slide?: number
  blockStart?: number
  blockEnd?: number
  sheet?: string
  rowStart?: number
  rowEnd?: number
  section?: number
  timeMs?: number
  quote?: string
}
export type ChunkRelation = 'locator' | 'text' | 'parent'
export type ChunkPage = { success: boolean; data: ChunkDetailContent[]; total: number }

function usableLocator(locator: SourceLocator, hash: string) {
  return locator.mapping === 'exact' && (!locator.source_hash || !hash || locator.source_hash === hash)
}
function locatorMatches(locator: SourceLocator, selection: SourceSelection) {
  if (locator.type !== selection.type) return false
  switch (selection.type) {
    case 'pdf': return locator.page === selection.page && Number.isInteger(locator.page) && locator.page! > 0
    case 'slide': return locator.slide === selection.slide
    case 'docx': {
      const withinBlocks = !!locator.block && locator.block >= (selection.blockStart || 1) && locator.block <= (selection.blockEnd || selection.blockStart || 0)
      // Word pagination can split a body paragraph into multiple rendered
      // blocks. Validate its text before trusting the rendered ordinal.
      return withinBlocks && (!selection.quote || !!locator.quote &&
        normalizeForMatch(selection.quote).includes(normalizeForMatch(sourceQuoteText(locator.quote))))
    }
    case 'sheet': return locator.sheet === selection.sheet && (!selection.rowStart ||
      !!locator.row_start && locator.row_start <= (selection.rowEnd || selection.rowStart) && (locator.row_end || locator.row_start) >= selection.rowStart)
    case 'section': return locator.section === selection.section
    case 'time': return selection.timeMs !== undefined && locator.start_ms !== undefined && locator.end_ms !== undefined && locator.start_ms <= selection.timeMs && locator.end_ms >= selection.timeMs
    default: return false
  }
}

/** Stored positions take precedence; text-only fallback is labelled as a candidate. */
export function chunkRelations(chunks: ChunkDetailContent[], selection: SourceSelection | null, hash = '') {
  const matches = new Map<string, ChunkRelation>()
  if (!selection) return matches
  const selectedText = normalizeForMatch(selection.quote || '')
  for (const chunk of chunks) {
    const locators = parseSourceLocators(chunk.source_locators)
    if (locators.some(locator => usableLocator(locator, hash) && locatorMatches(locator, selection))) {
      matches.set(chunk.id, 'locator')
      continue
    }
    // An edited chunk without an updated mapping must not match an old file.
    if (chunk.content_revision > 0 && !locators.some(locator => usableLocator(locator, hash))) continue
    if (locators.some(locator => locator.source_hash && hash && locator.source_hash !== hash)) continue
    if (selection.type === 'docx' && selectedText && locators.some(locator => {
      const quote = normalizeForMatch(sourceQuoteText(locator.quote || ''))
      return usableLocator(locator, hash) && locator.type === 'docx' && quote.length >= 12 && selectedText.includes(quote)
    })) { matches.set(chunk.id, 'text'); continue }
    // Plain-text previews may render Markdown/HTML, so their DOM offsets do
    // not equal source-file offsets. Keep quote matches explicitly labelled
    // as text candidates even when source text offsets were stored.
    if (locators.some(locator => usableLocator(locator, hash)) &&
      !(selection.type === 'text' && locators.some(locator => usableLocator(locator, hash) && locator.type === 'text'))) continue
    if (selection.type === 'image') { matches.set(chunk.id, 'text'); continue }
    const content = normalizeForMatch(sourceQuoteText(chunk.content || ''))
    if (selectedText.length >= 12 && content.length >= 12 &&
      (selectedText.includes(content) || selection.type === 'text' && content.includes(selectedText))) matches.set(chunk.id, 'text')
  }
  // Image children may carry only their parent's location. Mark this as a
  // relationship, rather than claiming that the child has its own page map.
  let changed = true
  while (changed) {
    changed = false
    for (const chunk of chunks) {
      if (matches.has(chunk.id) || parseSourceLocators(chunk.source_locators).length || chunk.content_revision > 0) continue
      if (typeof chunk.parent_chunk_id === 'string' && matches.has(chunk.parent_chunk_id)) {
        matches.set(chunk.id, 'parent'); changed = true
      }
    }
  }
  return matches
}

export async function loadAllDocumentChunks(
  knowledgeId: string, knowledgeBaseId: string,
  list: (page: number) => Promise<ChunkPage>,
): Promise<ChunkDetailContent[]> {
  const chunks: ChunkDetailContent[] = [], seen = new Set<string>()
  let total: number | undefined
  for (let page = 1; ; page++) {
    const response = await list(page)
    if (!response.success || !Array.isArray(response.data) || !Number.isSafeInteger(response.total) || response.total < 0) throw new Error('Invalid chunk page')
    if (total !== undefined && total !== response.total) throw new Error('Document chunks changed while loading')
    total = response.total
    for (const chunk of response.data) {
      if (!chunk.id || seen.has(chunk.id) || chunk.knowledge_id !== knowledgeId || chunk.knowledge_base_id !== knowledgeBaseId) throw new Error('Invalid chunk scope or duplicate page')
      seen.add(chunk.id); chunks.push(chunk)
    }
    if (chunks.length === total) return chunks
    if (!response.data.length || chunks.length > total) throw new Error('Incomplete chunk list')
  }
}

export function verifyChunkDetail(base: ChunkDetailContent, detail: ChunkDetail): ChunkDetail {
  const chunk = detail?.chunk
  if (!chunk || chunk.id !== base.id || chunk.knowledge_id !== base.knowledge_id || chunk.knowledge_base_id !== base.knowledge_base_id ||
    chunk.content_revision !== base.content_revision || chunk.content !== base.content || chunk.is_enabled !== base.is_enabled || chunk.updated_at !== base.updated_at || chunk.index_status !== base.index_status) {
    throw new Error('Chunk changed while reading details')
  }
  return detail
}

export async function mapChunksWithLimit<T>(chunks: ChunkDetailContent[], read: (chunk: ChunkDetailContent) => Promise<T>, progress?: (done: number) => void) {
  const result: T[] = new Array(chunks.length)
  let next = 0, done = 0, failed = false
  await Promise.all(Array.from({ length: Math.min(4, chunks.length) }, async () => {
    while (!failed && next < chunks.length) {
      const index = next++
      try { result[index] = await read(chunks[index]!); progress?.(++done) }
      catch (error) { failed = true; throw error }
    }
  }))
  return result
}

export function chunkListSignature(chunks: ChunkDetailContent[]) {
  return JSON.stringify(chunks.map(chunk => [chunk.id, chunk.content_revision, chunk.updated_at, chunk.is_enabled, chunk.index_status, chunk.content]))
}

export function buildDocumentChunksExport(document: Record<string, unknown>, chunks: ChunkDetail[]) {
  for (const detail of chunks) {
    if (detail.chunk.knowledge_id !== document.id || detail.chunk.knowledge_base_id !== document.knowledge_base_id) throw new Error('Invalid exported chunk scope')
  }
  return { format: 'weknora.document-chunks', version: 1, exported_at: new Date().toISOString(), document, chunk_count: chunks.length, chunks }
}
