import type { StorageBackendConfig } from '../api/storage-backend'

export type AzureConnectionErrorCode = 'format' | 'conflict' | 'unsupported' | 'account' | 'key' | 'endpoint' | 'location'
export class AzureConnectionStringError extends Error {
  constructor(public code: AzureConnectionErrorCode) { super(code) }
}
const fail = (code: AzureConnectionErrorCode): never => { throw new AzureConnectionStringError(code) }

export function validateAzureEndpoint(endpoint: string): string {
  let url: URL
  try { url = new URL(endpoint) } catch { return fail('endpoint') }
  if (!/^https:\/\/[^/?#]+\/?$/.test(endpoint) || url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.search || url.hash || url.pathname !== '/' || endpoint.includes('?') || endpoint.includes('#')) fail('endpoint')
  return url.origin
}

// Pure, local parsing. Errors contain codes only, never connection strings or key values.
export function parseAzureConnectionString(input: string) {
  const fields = new Map<string, string>()
  if (!input.trim()) fail('format')
  for (const entry of input.split(';')) {
    if (!entry.trim()) continue
    const separator = entry.indexOf('=')
    if (separator <= 0) fail('format')
    const key = entry.slice(0, separator).trim().toLowerCase()
    const value = entry.slice(separator + 1).trim()
    if (!/^[a-z][a-z0-9]*$/.test(key)) fail('format')
    if (fields.has(key) && fields.get(key) !== value) fail('conflict')
    fields.set(key, value)
  }
  if (['sharedaccesssignature', 'usedevelopmentstorage', 'developmentstorageproxyuri', 'authentication', 'credential', 'clientid', 'clientsecret', 'tenantid', 'authmode'].some(key => fields.has(key))) fail('unsupported')
  const account = fields.get('accountname') || ''
  const accountKey = fields.get('accountkey') || ''
  if (!/^[a-z0-9]{3,24}$/.test(account)) fail('account')
  if (!accountKey) fail('key')
  const protocol = fields.get('defaultendpointsprotocol') ?? 'https'
  const explicit = fields.get('blobendpoint')
  if (!explicit && protocol !== 'https') fail('endpoint')
  const suffix = fields.get('endpointsuffix') ?? 'core.windows.net'
  if (!explicit && !/^[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z0-9]+$/i.test(suffix)) fail('endpoint')
  const endpoint = validateAzureEndpoint(explicit ?? `https://${account}.blob.${suffix}`)
  const known = ['accountname', 'accountkey', 'blobendpoint', 'defaultendpointsprotocol', 'endpointsuffix', 'queueendpoint', 'tableendpoint', 'fileendpoint']
  return { config: { account_name: account, account_key: accountKey, endpoint, auth_mode: 'account_key' as const, managed_identity_client_id: '' }, hasUnusedFields: [...fields.keys()].some(key => !known.includes(key)) }
}

// Return a new config only after every check passes. Existing object location is immutable.
export function applyAzureConnectionString(input: string, current: StorageBackendConfig, editing = false) {
  const parsed = parseAzureConnectionString(input)
  if (editing && (parsed.config.account_name !== current.account_name || parsed.config.endpoint !== validateAzureEndpoint(current.endpoint || `https://${current.account_name}.blob.core.windows.net`))) fail('location')
  const config = { ...current, ...parsed.config }
  if (editing) config.endpoint = current.endpoint || parsed.config.endpoint
  return { config, hasUnusedFields: parsed.hasUnusedFields }
}
