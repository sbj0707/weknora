import { readFileSync } from 'node:fs'
import { createRef, StrictMode } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { WeKnoraChatbot, type ChatbotHandle } from './WeKnoraChatbot'
import { loadWidgetSDK } from './widget'
import type { EmbedConfig } from './config'

vi.mock('./widget', () => ({ loadWidgetSDK: vi.fn() }))

const config: EmbedConfig = {
  baseUrl: 'https://weknora.example.com', channelId: 'test-channel', token: 'em_example',
  tokenEndpoint: '', title: '知识助手', locale: 'zh-CN', primaryColor: '#16845b', mode: 'inline',
}

afterEach(() => { cleanup(); vi.clearAllMocks(); delete window.WeKnora })

function message(frame: HTMLIFrameElement, type: string, overrides: MessageEventInit = {}) {
  act(() => window.dispatchEvent(new MessageEvent('message', {
    source: frame.contentWindow, origin: config.baseUrl,
    data: { source: 'weknora-embed', type, channel_id: config.channelId }, ...overrides,
  })))
}

describe('inline embed bridge', () => {
  it('hands credentials only to its own frame and trusted origin, and waits for real readiness', () => {
    const onState = vi.fn()
    const onEvent = vi.fn()
    const ref = createRef<ChatbotHandle>()
    const view = render(<WeKnoraChatbot ref={ref} config={config} onState={onState} onEvent={onEvent} />)
    const frame = screen.getByTitle('知识助手') as HTMLIFrameElement
    const post = vi.spyOn(frame.contentWindow!, 'postMessage')

    message(frame, 'bootstrap_request', { origin: 'https://untrusted.example.com' })
    message(frame, 'bootstrap_request', { source: window })
    expect(post).not.toHaveBeenCalled()
    message(frame, 'bootstrap_request')
    expect(post).toHaveBeenCalledWith(expect.objectContaining({
      type: 'provide_token', token: 'em_example',
    }), config.baseUrl)
    ref.current?.ask('not ready')
    expect(post).toHaveBeenCalledTimes(1)
    message(frame, 'ready', { data: { source: 'weknora-embed', type: 'ready', channel_id: 'other' } })
    expect(onState).not.toHaveBeenCalledWith('ready')
    message(frame, 'ready')
    expect(onState).toHaveBeenCalledWith('ready')
    ref.current?.ask('文档解析的流程是什么？')
    expect(post).toHaveBeenLastCalledWith({ source: 'weknora-host', type: 'open_with_query',
      payload: { query: '文档解析的流程是什么？' } }, config.baseUrl)
    expect(frame.src).not.toContain('em_example')

    view.unmount()
    const eventCount = onEvent.mock.calls.length
    message(frame, 'ready')
    expect(onEvent).toHaveBeenCalledTimes(eventCount)
  })
})

describe('official widget SDK integration', () => {
  it('survives StrictMode, relays real bridge events and removes the widget on unmount', async () => {
    // Execute the repository's actual SDK rather than simulating its widget behavior.
    const source = readFileSync('../../frontend/public/weknora-widget.js', 'utf8')
    new Function('window', 'document', source)(window, document)
    vi.mocked(loadWidgetSDK).mockResolvedValue(window.WeKnora!)
    const onState = vi.fn()
    const onEvent = vi.fn()
    const ref = createRef<ChatbotHandle>()
    const view = render(<StrictMode><WeKnoraChatbot ref={ref} config={{ ...config, mode: 'widget' }}
      onState={onState} onEvent={onEvent} /></StrictMode>)
    await waitFor(() => expect(document.querySelectorAll('iframe')).toHaveLength(1))
    const frame = screen.getByTitle('知识助手') as HTMLIFrameElement
    const post = vi.spyOn(frame.contentWindow!, 'postMessage')
    message(frame, 'ready')
    expect(onState).toHaveBeenCalledWith('ready')
    expect(post).toHaveBeenCalledWith(expect.objectContaining({ type: 'set_locale' }), config.baseUrl)
    ref.current?.ask('你好')
    expect(post).toHaveBeenCalledWith(expect.objectContaining({
      type: 'open_with_query', payload: { query: '你好' },
    }), config.baseUrl)
    message(frame, 'message_received')
    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'message_received' }))
    view.unmount()
    expect(document.querySelectorAll('iframe')).toHaveLength(0)
    expect(document.querySelectorAll('button')).toHaveLength(0)
  })

  it('does not create a late widget after an unmount while its script is loading', async () => {
    let finish!: (sdk: NonNullable<typeof window.WeKnora>) => void
    vi.mocked(loadWidgetSDK).mockReturnValue(new Promise((resolve) => { finish = resolve }))
    const init = vi.fn()
    const view = render(<WeKnoraChatbot config={{ ...config, mode: 'widget' }}
      onState={vi.fn()} onEvent={vi.fn()} />)
    view.unmount()
    await act(async () => finish({ init, on: vi.fn(), off: vi.fn() }))
    expect(init).not.toHaveBeenCalled()
  })
})
