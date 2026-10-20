import { test } from 'node:test'
import assert from 'node:assert/strict'
import { azureConnectionFields, cleanAzureConnection, validateAzureIndex } from './azureAISearchForm'
import type { FieldSchema } from '@/api/vector-store'

const fields: FieldSchema[] = [
  { name: 'addr', type: 'string', required: true },
  { name: 'auth_mode', type: 'string', required: true },
  { name: 'api_key', type: 'string', required: false, sensitive: true },
  { name: 'managed_identity_client_id', type: 'string', required: false },
]
test('Azure auth selection exposes only relevant credentials and enforces the key', () => {
  const keyFields = azureConnectionFields(fields, {})
  assert.equal(keyFields.find(f => f.name === 'api_key')?.required, true)
  assert.equal(keyFields.some(f => f.name === 'managed_identity_client_id'), false)
  const identityFields = azureConnectionFields(fields, { auth_mode: 'managed_identity' })
  assert.equal(identityFields.some(f => f.name === 'api_key'), false)
  assert.equal(identityFields.find(f => f.name === 'managed_identity_client_id')?.required, false)
  assert.equal(fields.find(f => f.name === 'api_key')?.required, false)
})
test('switching authentication excludes the previous credential from submitted data', () => {
  const original = { addr: 'https://example.search.windows.net', auth_mode: 'managed_identity', api_key: 'secret', managed_identity_client_id: 'client' }
  assert.equal('api_key' in cleanAzureConnection(original), false)
  assert.equal(original.api_key, 'secret')
  assert.equal('managed_identity_client_id' in cleanAzureConnection({ ...original, auth_mode: 'api_key' }), false)
})
test('Azure requires a fixed integer dimension and Azure-compatible index names', () => {
  assert.equal(validateAzureIndex({ vector_dimensions: 1536 }), null)
  for (const vector_dimensions of [undefined, '', 0, -1, 1.5, 4097, Infinity, 'abc']) assert.equal(validateAzureIndex({ vector_dimensions }), 'dimensions')
  for (const index_name of ['Upper', 'a_b', 'a--b', '-a', 'a-', 'a'.repeat(93)]) assert.equal(validateAzureIndex({ vector_dimensions: 768, index_name }), 'prefix')
  assert.equal(validateAzureIndex({ vector_dimensions: 768, index_name: 'a'.repeat(92) }), null)
})
