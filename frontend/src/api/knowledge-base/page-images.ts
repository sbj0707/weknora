import { get, post } from '@/utils/request'

export interface PageImageAsset {
  id: string; page_number: number; status: string; error_message?: string
  width: number; height: number; size: number; updated_at: string
}
export interface PageImageState {
  enabled: boolean; supported: boolean; source_hash: string; pages: number[]
  manual: boolean; images: PageImageAsset[]
}
export interface PageImageRequest {
  source_hash: string; chunk_id?: string; content_revision?: number
  pages?: number[]; manual?: boolean; force?: boolean
}
const path = (id: string) => `/api/v1/knowledge/${encodeURIComponent(id)}/page-images`
export async function getPageImages(id: string, chunkID?: string, signal?: AbortSignal) {
  const res = await get<{ data: PageImageState }>(path(id), { params: { chunk_id: chunkID }, signal })
  return res.data
}
export async function generatePageImages(id: string, request: PageImageRequest) {
  const res = await post<{ data: PageImageState }>(path(id), request, { timeout: 120000 })
  return res.data
}
export function downloadPageImage(id: string, assetID: string, signal?: AbortSignal) {
  return get<Blob>(`${path(id)}/${encodeURIComponent(assetID)}`, { responseType: 'blob', signal })
}
