import { describe, expect, it } from 'vitest'
import { embedUrl, normalizeConfig, type EmbedConfig } from './config'

const config: EmbedConfig = {
  baseUrl: 'https://weknora.example.com/', channelId: 'test-channel', token: 'em_example',
  tokenEndpoint: '', title: '知识助手', locale: 'zh-CN', primaryColor: '#16845b', mode: 'inline',
}

describe('embed configuration', () => {
  it('normalizes the origin and keeps the token out of the iframe URL', () => {
    const normalized = normalizeConfig(config)
    expect(normalized.baseUrl).toBe('https://weknora.example.com')
    expect(embedUrl(normalized)).toBe('https://weknora.example.com/embed/test-channel?locale=zh-CN')
    expect(embedUrl(normalized)).not.toContain(config.token)
  })

  it.each(['javascript:alert(1)', 'https://user:pass@example.com', 'https://example.com/embed/id',
    'https://example.com?token=secret'])('rejects unsuitable service URLs: %s', (baseUrl) => {
    expect(() => normalizeConfig({ ...config, baseUrl })).toThrow()
  })

  it('does not use an expiring session token as a permanent configuration', () => {
    expect(() => normalizeConfig({ ...config, token: 'ems_temporary' })).toThrow('短效')
  })

  it('gives the business token endpoint precedence over a static token', () => {
    const normalized = normalizeConfig({ ...config, mode: 'widget', tokenEndpoint: '/api/embed-token' })
    expect(normalized.token).toBe('')
    expect(normalized.tokenEndpoint).toBe('/api/embed-token')
    expect(() => normalizeConfig({ ...config, tokenEndpoint: '/api/embed-token' })).toThrow('悬浮')
  })
})
