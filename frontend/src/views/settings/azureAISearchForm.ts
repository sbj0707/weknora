import type { FieldSchema } from '@/api/vector-store'

export const azureAISearchType = 'azure_ai_search'

export function azureConnectionFields(fields: FieldSchema[], config: Record<string, any>): FieldSchema[] {
  const mode = config.auth_mode || 'api_key'
  return fields.filter(field => field.name !== 'api_key' || mode === 'api_key')
    .filter(field => field.name !== 'managed_identity_client_id' || mode === 'managed_identity')
    .map(field => field.name === 'api_key' ? { ...field, required: true } : field)
}

export function cleanAzureConnection(config: Record<string, any>): Record<string, any> {
  const mode = config.auth_mode || 'api_key'
  const result: Record<string, any> = { ...config, auth_mode: mode }
  if (mode === 'managed_identity') delete result.api_key
  else delete result.managed_identity_client_id
  return result
}

export function validateAzureIndex(config: Record<string, any>): 'dimensions' | 'prefix' | null {
  const dimension = Number(config.vector_dimensions)
  if (!Number.isInteger(dimension) || dimension < 1 || dimension > 4096) return 'dimensions'
  const prefix = config.index_name || 'weknora'
  if (prefix.length > 92 || !/^[a-z](?:[a-z0-9-]*[a-z0-9])?$/.test(prefix) || prefix.includes('--')) return 'prefix'
  return null
}
