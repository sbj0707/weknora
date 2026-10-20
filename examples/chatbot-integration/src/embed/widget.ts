export interface WidgetInstance {
  open(): void
  close(): void
  destroy(): void
  setContext(context: Record<string, unknown>): void
  setLocale(locale: string): void
  openWithQuery(query: string): void
}

export interface WidgetSDK {
  init(options: Record<string, unknown>): WidgetInstance | null
  on(event: string, handler: (payload: unknown) => void): void
  off(event: string, handler: (payload: unknown) => void): void
}

declare global {
  interface Window { WeKnora?: WidgetSDK }
}

const loads = new Map<string, Promise<WidgetSDK>>()

/** Cache the script load so React StrictMode doesn't inject duplicate SDKs. */
export function loadWidgetSDK(baseUrl: string): Promise<WidgetSDK> {
  const src = `${baseUrl}/weknora-widget.js`
  const existing = loads.get(src)
  if (existing) return existing

  const promise = new Promise<WidgetSDK>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = src
    script.async = true
    const fail = () => {
      clearTimeout(timer)
      script.remove()
      reject(new Error('Widget SDK 加载失败，请检查 WeKnora 网页地址和网络连接。'))
    }
    const timer = window.setTimeout(fail, 15_000)
    script.onerror = fail
    script.onload = () => {
      clearTimeout(timer)
      if (window.WeKnora) resolve(window.WeKnora)
      else fail()
    }
    document.head.appendChild(script)
  })
  loads.set(src, promise)
  void promise.catch(() => loads.delete(src))
  return promise
}
