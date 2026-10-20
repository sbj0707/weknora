import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react'
import { embedUrl, type EmbedConfig } from './config'
import { loadWidgetSDK, type WidgetInstance } from './widget'

export type ConnectionState = 'connecting' | 'ready' | 'error'
export interface ChatEvent { type: string; detail?: string }
export interface ChatbotHandle { open(): void; close(): void; ask(query: string): void }
interface Props {
  config: EmbedConfig
  onState(state: ConnectionState, message?: string): void
  onEvent(event: ChatEvent): void
}

/** One mounted widget per page: the official SDK is a page-wide singleton. */
export const WeKnoraChatbot = forwardRef<ChatbotHandle, Props>(function WeKnoraChatbot(
  { config, onState, onEvent }, ref,
) {
  const iframeRef = useRef<HTMLIFrameElement>(null)
  const widgetRef = useRef<WidgetInstance | null>(null)
  const readyRef = useRef(false)
  const callbacks = useRef({ onState, onEvent })
  callbacks.current = { onState, onEvent }

  function post(type: string, payload: Record<string, unknown>) {
    iframeRef.current?.contentWindow?.postMessage(
      { source: 'weknora-host', type, payload }, config.baseUrl,
    )
  }

  useImperativeHandle(ref, () => ({
    open: () => widgetRef.current?.open(),
    close: () => widgetRef.current?.close(),
    ask: (query) => {
      if (!readyRef.current || !query.trim()) return
      if (config.mode === 'widget') widgetRef.current?.openWithQuery(query)
      else post('open_with_query', { query })
    },
  }))

  useEffect(() => {
    let disposed = false
    let cleanupWidget: (() => void) | undefined
    readyRef.current = false
    callbacks.current.onState('connecting')
    const timer = window.setTimeout(() => {
      if (!readyRef.current) callbacks.current.onState('error',
        '暂未收到聊天就绪信号，请检查嵌入页中的提示、渠道 Token 和允许域名。')
    }, 25_000)
    const context = { page: window.location.pathname, integration: 'react-chatbot-example' }
    const ready = () => {
      readyRef.current = true
      clearTimeout(timer)
      callbacks.current.onState('ready')
      callbacks.current.onEvent({ type: 'ready', detail: '聊天已就绪' })
    }

    const handleMessage = (event: MessageEvent) => {
      if (config.mode !== 'inline' || event.origin !== config.baseUrl ||
          event.source !== iframeRef.current?.contentWindow) return
      const data = event.data
      if (!data || data.source !== 'weknora-embed' ||
          (data.channel_id && data.channel_id !== config.channelId)) return
      if (data.type === 'bootstrap_request') {
        iframeRef.current?.contentWindow?.postMessage({
          source: 'weknora-host', type: 'provide_token',
          token: config.token, channel_id: config.channelId,
        }, config.baseUrl)
      } else if (data.type === 'ready') {
        iframeRef.current?.contentWindow?.postMessage({
          source: 'weknora-host', type: 'set_context', payload: context,
        }, config.baseUrl)
        ready()
      } else if (data.type === 'message_sent' || data.type === 'message_received') {
        callbacks.current.onEvent({ type: data.type,
          detail: typeof data.session_id === 'string' ? `会话 ${data.session_id}` : undefined })
      }
    }
    window.addEventListener('message', handleMessage)

    if (config.mode === 'widget') {
      void loadWidgetSDK(config.baseUrl).then((sdk) => {
        if (disposed) return
        const instance = sdk.init({
          baseUrl: config.baseUrl, channel: config.channelId,
          ...(config.tokenEndpoint ? { tokenEndpoint: config.tokenEndpoint } : { token: config.token }),
          title: config.title, primaryColor: config.primaryColor,
          position: 'bottom-right', width: 420, height: 640,
        })
        if (!instance) throw new Error('Widget 初始化失败，请检查渠道配置。')
        widgetRef.current = instance
        const onReady = () => {
          instance.setContext(context)
          instance.setLocale(config.locale)
          ready()
        }
        const onSent = () => callbacks.current.onEvent({ type: 'message_sent', detail: '访客已提问' })
        const onReceived = () => callbacks.current.onEvent({ type: 'message_received', detail: '助手已完成回复' })
        sdk.on('ready', onReady)
        sdk.on('message_sent', onSent)
        sdk.on('message_received', onReceived)
        instance.open()
        cleanupWidget = () => {
          sdk.off('ready', onReady)
          sdk.off('message_sent', onSent)
          sdk.off('message_received', onReceived)
          instance.destroy()
          widgetRef.current = null
        }
      }).catch((error: unknown) => {
        if (!disposed) {
          clearTimeout(timer)
          callbacks.current.onState('error', error instanceof Error ? error.message : 'Widget 加载失败')
        }
      })
    }

    return () => {
      disposed = true
      clearTimeout(timer)
      readyRef.current = false
      window.removeEventListener('message', handleMessage)
      cleanupWidget?.()
    }
  }, [config])

  if (config.mode === 'widget') return null
  return <iframe ref={iframeRef} className="chat-frame" src={embedUrl(config)}
    title={config.title} allow="clipboard-write"
    sandbox="allow-scripts allow-forms allow-popups allow-modals allow-same-origin"
    onLoad={() => iframeRef.current?.contentWindow?.postMessage({
      source: 'weknora-host', type: 'provide_token', token: config.token,
      channel_id: config.channelId,
    }, config.baseUrl)} />
})
