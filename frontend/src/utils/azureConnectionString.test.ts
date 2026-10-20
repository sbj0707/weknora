import assert from 'node:assert/strict'
import test from 'node:test'
import { applyAzureConnectionString, AzureConnectionStringError, parseAzureConnectionString } from './azureConnectionString.ts'
import { exportKnowledgeBaseConfig } from './knowledgeBaseConfigTransfer.ts'
import { buildProtectedFileRequest, isProviderFileURL } from './protectedFileAccess.ts'

const example = 'DefaultEndpointsProtocol=https;AccountName=giostorageblob;AccountKey=a+/b==;EndpointSuffix=core.windows.net'

test('parses the supplied format without changing Base64 padding or special characters', () => {
  assert.deepEqual(parseAzureConnectionString(example).config, { account_name: 'giostorageblob', account_key: 'a+/b==', endpoint: 'https://giostorageblob.blob.core.windows.net', auth_mode: 'account_key', managed_identity_client_id: '' })
})
test('supports whitespace, casing, duplicate equal values and sovereign cloud suffixes', () => {
  const parsed = parseAzureConnectionString(' \n ACCOUNTKEY = A+/==; accountname=giostorageblob; AccountName=giostorageblob; EndpointSuffix=core.chinacloudapi.cn; ')
  assert.equal(parsed.config.account_key, 'A+/==')
  assert.equal(parsed.config.endpoint, 'https://giostorageblob.blob.core.chinacloudapi.cn')
  assert.equal(parseAzureConnectionString('AccountName=giostorageblob;AccountKey=k').config.endpoint, 'https://giostorageblob.blob.core.windows.net')
})
test('explicit endpoint wins, unrelated service endpoints are ignored, unknown fields do not pass through', () => {
  const parsed = parseAzureConnectionString(example + ';BlobEndpoint=https://private.example.com/;QueueEndpoint=https://queue.example.com;NewField=private-value')
  assert.equal(parsed.config.endpoint, 'https://private.example.com')
  assert.equal(parseAzureConnectionString(example.replace('Protocol=https', 'Protocol=http') + ';BlobEndpoint=https://private.example.com').config.endpoint, 'https://private.example.com')
  assert.equal(parsed.hasUnusedFields, true)
  assert.equal(JSON.stringify(parsed.config).includes('private-value'), false)
})
test('atomic fill preserves container and prefix and never mutates the source', () => {
  const current = { container_name: 'documents', path_prefix: 'prod/', account_name: 'old', account_key: 'old-key', auth_mode: 'managed_identity' as const, managed_identity_client_id: 'old-id' }
  const before = structuredClone(current)
  const { config } = applyAzureConnectionString(example, current)
  assert.equal(config.container_name, 'documents'); assert.equal(config.path_prefix, 'prod/')
  assert.equal(config.managed_identity_client_id, '')
  assert.deepEqual(current, before)
  assert.throws(() => applyAzureConnectionString('AccountKey=secret', current), AzureConnectionStringError)
  assert.deepEqual(current, before)
})
test('existing storage location cannot move while rotating credentials', () => {
  const current = { account_name: 'giostorageblob', endpoint: 'https://giostorageblob.blob.core.windows.net', account_key: '***' }
  assert.equal(applyAzureConnectionString(example, current, true).config.account_key, 'a+/b==')
  const explicitPort = { ...current, endpoint: current.endpoint + ':443' }
  assert.equal(applyAzureConnectionString(example, explicitPort, true).config.endpoint, explicitPort.endpoint)
  assert.throws(() => applyAzureConnectionString(example.replace('giostorageblob', 'different'), current, true), (err: any) => err.code === 'location')
})
for (const [name, value] of Object.entries({
  missingAccount: 'AccountKey=secret', missingKey: 'AccountName=giostorageblob', malformed: example + ';broken',
  conflicting: example + ';AccountKey=secret', sas: example + ';SharedAccessSignature=secret', emulator: 'UseDevelopmentStorage=true', mixed: example + ';ClientSecret=secret',
  http: example + ';BlobEndpoint=http://example.com', query: example + ';BlobEndpoint=https://example.com?sig=secret', user: example + ';BlobEndpoint=https://user:secret@example.com',
  path: example + ';BlobEndpoint=https://example.com/container', normalizedPath: example + ';BlobEndpoint=https://example.com/foo/..', invalidSuffix: 'AccountName=giostorageblob;AccountKey=secret;EndpointSuffix=evil.com/path',
})) test(`rejects ${name} with no secret in its error`, () => {
  assert.throws(() => parseAzureConnectionString(value), (err: any) => err instanceof AzureConnectionStringError && !err.message.includes('secret') && !err.message.includes('a+/b=='))
})
test('Azure resources use the authenticated existing file proxy', () => {
  const path = 'storage://backend-1/azure://documents/prod/tenants/42/assets/photo.png'
  assert.equal(isProviderFileURL('azure://documents/prod/tenants/42/assets/photo.png'), true)
  assert.equal(buildProtectedFileRequest(path, { mode: 'tenant' })?.url, `/files?file_path=${encodeURIComponent(path)}`)
})

test('knowledge-base export includes the binding without Azure credentials or connection strings', () => {
  const exported = exportKnowledgeBaseConfig({ type: 'document', name: 'Azure documents', storage_backend_id: 'azure-instance', storage_provider_config: { provider: 'azure', account_key: 'hidden-account-key' }, storage_config: { account_key: 'hidden-account-key', connection_string: 'hidden-connection-string' }, storage_engine_config: { azure: { account_key: 'hidden-account-key' } } })
  const json = JSON.stringify(exported)
  assert.equal(exported.configuration.storage_backend_id, 'azure-instance')
  assert.equal(json.includes('hidden-account-key'), false)
  assert.equal(json.includes('hidden-connection-string'), false)
})
