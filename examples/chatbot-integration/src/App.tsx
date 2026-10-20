import { useCallback, useRef, useState, type FormEvent } from 'react'
import { normalizeConfig, type EmbedConfig } from './embed/config'
import { WeKnoraChatbot, type ChatbotHandle, type ChatEvent, type ConnectionState } from './embed/WeKnoraChatbot'

const initial: EmbedConfig = {
  baseUrl: import.meta.env.VITE_WEKNORA_BASE_URL || 'http://localhost:5173',
  channelId: import.meta.env.VITE_WEKNORA_CHANNEL_ID || '',
  token: import.meta.env.VITE_WEKNORA_PUBLISH_TOKEN || '',
  tokenEndpoint: import.meta.env.VITE_WEKNORA_TOKEN_ENDPOINT || '',
  title: '知识助手', locale: 'zh-CN', primaryColor: '#16845b',
  mode: import.meta.env.VITE_WEKNORA_TOKEN_ENDPOINT ? 'widget' : 'inline',
}

const questions = ['介绍一下这个知识库的内容', '文档解析的流程是什么？', '如何找到回答对应的原文？']
const statusLabels = { connecting: '正在连接', ready: '已连接', error: '连接待检查' }

function Mark({ small = false }: { small?: boolean }) {
  return <span className={`brand-mark ${small ? 'small' : ''}`} aria-hidden="true">
    <svg viewBox="0 0 28 28" fill="none"><path d="M6 7v14M14 4v17M22 7v14M6 13l8 8 8-8"
      stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
  </span>
}

export default function App() {
  const [draft, setDraft] = useState(initial)
  const [authMode, setAuthMode] = useState(initial.tokenEndpoint ? 'endpoint' : 'token')
  const [connection, setConnection] = useState<EmbedConfig | null>(() => {
    if (!initial.channelId || (!initial.token && !initial.tokenEndpoint)) return null
    try { return normalizeConfig(initial) } catch { return null }
  })
  const [state, setState] = useState<ConnectionState>('connecting')
  const [error, setError] = useState('')
  const [formError, setFormError] = useState('')
  const [events, setEvents] = useState<(ChatEvent & { time: string; id: number })[]>([])
  const eventId = useRef(0)
  const chatbot = useRef<ChatbotHandle>(null)

  const update = (field: keyof EmbedConfig, value: string) =>
    setDraft((previous) => ({ ...previous, [field]: value }))

  const onState = useCallback((next: ConnectionState, message?: string) => {
    setState(next)
    setError(message || '')
  }, [])
  const onEvent = useCallback((event: ChatEvent) => {
    const entry = { ...event, id: ++eventId.current,
      time: new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
    setEvents((previous) => [entry, ...previous].slice(0, 8))
  }, [])

  function connect(event: FormEvent) {
    event.preventDefault()
    try {
      const next = normalizeConfig({ ...draft,
        token: authMode === 'token' ? draft.token : '',
        tokenEndpoint: authMode === 'endpoint' ? draft.tokenEndpoint : '',
      })
      setFormError('')
      setError('')
      setEvents([])
      setState('connecting')
      setConnection(next)
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : '请检查配置。')
    }
  }

  return <div className="app">
    <header className="topbar">
      <a className="brand" href="/" aria-label="WeKnora React Chatbot 首页"><Mark small />
        <span>WeKnora <span className="brand-divider">/</span> <span className="brand-sub">Chatbot</span></span>
      </a>
      <span className="example-badge"><span /> REACT EXAMPLE</span>
    </header>

    <main className="workspace">
      <aside className="setup-column">
        <div className="intro">
          <span className="eyebrow">YOUR KNOWLEDGE, IN CONVERSATION</span>
          <h1>让知识，<br />在对话中连接。</h1>
          <p>将 WeKnora 知识助手接入你的 React 网站，随时提问，获取有据可查的回答。</p>
        </div>

        <section className="config-card" aria-labelledby="config-title">
          <div className="section-title"><span className="step-number">01</span>
            <h2 id="config-title">连接你的助手</h2><span className="muted">渠道配置</span>
          </div>
          <form onSubmit={connect}>
            <label>WeKnora 网页地址
              <input type="url" value={draft.baseUrl} placeholder="http://localhost:5173" required
                onChange={(event) => update('baseUrl', event.target.value)} />
            </label>
            <p className="field-hint">填写能打开 WeKnora 网页的地址，包含端口。</p>
            <label>渠道 ID
              <input value={draft.channelId} placeholder="网页嵌入渠道的 UUID" autoComplete="off" required
                onChange={(event) => update('channelId', event.target.value)} />
            </label>

            <div className="auth-heading"><span>接入凭证</span>
              <select aria-label="凭证类型" value={authMode} onChange={(event) => {
                setAuthMode(event.target.value)
                if (event.target.value === 'endpoint') update('mode', 'widget')
              }}><option value="token">发布 Token</option><option value="endpoint">Token 接口</option></select>
            </div>
            {authMode === 'token' ? <label className="visually-labeled"><span className="sr-only">发布 Token</span>
              <input type="password" value={draft.token} placeholder="em_…" autoComplete="off" required
                onChange={(event) => update('token', event.target.value)} />
            </label> : <label className="visually-labeled"><span className="sr-only">Token 接口地址</span>
              <input value={draft.tokenEndpoint} placeholder="/api/weknora/embed-token" required
                onChange={(event) => update('tokenEndpoint', event.target.value)} />
            </label>}
            <p className="field-hint">{authMode === 'token' ? '使用渠道的发布 Token，不使用管理 API Key。' :
              '由你的业务后端返回短效 Token，发布 Token 留在服务端。'}</p>

            <fieldset className="mode-fieldset"><legend>展示方式</legend>
              <div className="mode-options">
                <label className={draft.mode === 'inline' ? 'mode-option selected' : 'mode-option'}>
                  <input type="radio" name="mode" value="inline" checked={draft.mode === 'inline'}
                    disabled={authMode === 'endpoint'} onChange={() => update('mode', 'inline')} />
                  <span>▣ 页面内嵌</span><small>聊天区域融入页面</small>
                </label>
                <label className={draft.mode === 'widget' ? 'mode-option selected' : 'mode-option'}>
                  <input type="radio" name="mode" value="widget" checked={draft.mode === 'widget'}
                    onChange={() => update('mode', 'widget')} />
                  <span>◧ 悬浮聊天</span><small>右下角随时唤起</small>
                </label>
              </div>
            </fieldset>
            <div className="form-row">
              <label>界面语言<select value={draft.locale} onChange={(event) => update('locale', event.target.value)}>
                <option value="zh-CN">简体中文</option><option value="en-US">English</option>
                <option value="ja-JP">日本語</option><option value="ko-KR">한국어</option>
                <option value="ru-RU">Русский</option>
              </select></label>
              <label>悬浮按钮颜色<input type="color" value={draft.primaryColor}
                onChange={(event) => update('primaryColor', event.target.value)} /></label>
            </div>
            {formError && <p className="error-note" role="alert">{formError}</p>}
            <button className="button primary connect-button" type="submit">
              {connection ? '重新连接助手' : '连接助手'} <span aria-hidden="true">↗</span>
            </button>
          </form>
        </section>

        <div className="origin-note"><span aria-hidden="true">↳</span><p>在渠道的允许域名中添加本站来源：
          <code>{window.location.origin}</code></p></div>
      </aside>

      <section className="chat-column" aria-labelledby="chat-title">
        <div className="chat-shell">
          <div className="chat-toolbar"><div><span className="step-number">02</span>
            <h2 id="chat-title">知识助手</h2></div>
            <span className={`connection-status ${connection ? state : 'idle'}`} role="status">
              <i />{connection ? statusLabels[state] : '等待连接'}</span>
          </div>
          {error && <div className="connection-error" role="alert">{error}
            <small>确认渠道已启用，Token 有效，允许域名包含左侧来源；生产嵌入还需 Redis 支持短效 Token 交换。</small>
          </div>}

          <div className="chat-body">
            {connection && <WeKnoraChatbot ref={chatbot} config={connection} onState={onState} onEvent={onEvent} />}
            {(!connection || connection.mode === 'widget') && <div className="welcome">
              <div className="welcome-mark"><Mark /></div>
              <span className="eyebrow">A LITTLE HELP, A LOT OF KNOWLEDGE</span>
              <h3>{connection ? '你的助手，就在右下角。' : '你好，今天想了解什么？'}</h3>
              <p>{connection ? '打开悬浮窗口，开始与你的知识库对话。' : '连接你的 WeKnora 渠道后，即可在这里开始对话。'}</p>
              {connection && <button className="button primary" onClick={() => chatbot.current?.open()}>打开聊天窗口 ↗</button>}
              <div className="capability-list"><span>✓ 知识库问答</span><span>✓ 原文引用</span><span>✓ 连续对话</span></div>
            </div>}
          </div>

          <div className="chat-footer"><span className="footer-symbol">✳</span>
            回答与引用由渠道绑定的 WeKnora 智能体提供。
            {connection && <button className="text-button" onClick={() => {
              setConnection(null); setEvents([]); setError('')
            }}>断开连接</button>}
          </div>
        </div>

        <div className="question-section"><div className="section-title"><span className="step-number">03</span>
          <h2>试着问一问</h2><span className="muted">连接后发送到助手</span></div>
          <div className="question-list">{questions.map((question, index) =>
            <button key={question} disabled={!connection || state !== 'ready'}
              onClick={() => chatbot.current?.ask(question)}>
              <span className="question-index">0{index + 1}</span><span>{question}</span><span aria-hidden="true">↗</span>
            </button>)}</div>
        </div>

        <details className="events"><summary>连接事件 <span>{events.length}</span></summary>
          {events.length ? <ol>{events.map((event) => <li key={event.id}><time>{event.time}</time>
            <code>{event.type}</code><span>{event.detail}</span></li>)}</ol> : <p>连接就绪、提问和回复完成的事件会显示在这里。</p>}
        </details>
      </section>
    </main>
    <footer className="page-footer"><span>Powered by WeKnora</span><span>React + TypeScript · 网页嵌入示例</span></footer>
  </div>
}
