export type EmbedMode = 'inline' | 'widget'

export interface EmbedConfig {
  baseUrl: string
  channelId: string
  token: string
  tokenEndpoint: string
  title: string
  locale: string
  primaryColor: string
  mode: EmbedMode
}

export function normalizeConfig(input: EmbedConfig): EmbedConfig {
  let url: URL
  try {
    url = new URL(input.baseUrl.trim())
  } catch {
    throw new Error('请输入完整的 WeKnora 网页地址，例如 http://localhost:5173。')
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
      url.pathname !== '/' || url.search || url.hash) {
    throw new Error('WeKnora 地址只填写源站（协议、域名和端口），不要填写 /embed 路径。')
  }
  const channelId = input.channelId.trim()
  if (!channelId || !/^[\w-]+$/.test(channelId)) {
    throw new Error('请填写网页嵌入渠道 ID。')
  }
  const token = input.token.trim()
  const tokenEndpoint = input.tokenEndpoint.trim()
  if (tokenEndpoint) {
    const endpoint = new URL(tokenEndpoint, window.location.origin)
    if (!['http:', 'https:'].includes(endpoint.protocol) || endpoint.username || endpoint.password) {
      throw new Error('Token 接口需要使用 HTTP / HTTPS 地址或本站相对路径。')
    }
    if (input.mode !== 'widget') throw new Error('Token 接口接入请选择悬浮聊天模式。')
  } else if (!token.startsWith('em_') || token.startsWith('ems_')) {
    throw new Error('请填写渠道的 em_ 发布 Token；短效 ems_ Token 请通过 Token 接口接入。')
  }
  return { ...input, baseUrl: url.origin, channelId, token: tokenEndpoint ? '' : token, tokenEndpoint }
}

export function embedUrl(config: EmbedConfig): string {
  const url = new URL(`/embed/${encodeURIComponent(config.channelId)}`, config.baseUrl)
  url.searchParams.set('locale', config.locale)
  // Tokens are handed to the embedded page through its official postMessage bridge.
  return url.href
}
