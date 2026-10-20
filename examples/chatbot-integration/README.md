# React Chatbot 集成 WeKnora

独立的 React + TypeScript + Vite 示例，通过 WeKnora 官方网页嵌入功能接入真实智能体。聊天 UI、流式回复、引用、历史会话和文件能力由 WeKnora 嵌入页提供，本项目负责 React 宿主页面、接入配置和宿主通信。

支持两种展示方式：

- **页面内嵌**：React 页面直接承载 `/embed/:channelId`，通过官方 `postMessage` 协议提供 Token 和发送问题。
- **悬浮聊天**：加载 WeKnora 提供的 `weknora-widget.js`，使用官方 SDK 打开、关闭和操作右下角聊天窗口。

## 目录

- [快速启动](#快速启动)
- [配置渠道](#配置渠道)
- [使用环境变量](#使用环境变量)
- [Token 接口模式](#token-接口模式)
- [在其他 React 项目中使用](#在其他-react-项目中使用)
- [代码结构](#代码结构)
- [验证与排查](#验证与排查)

## 快速启动

需要 Node.js 22.12+（推荐 Node.js 22 LTS）以及已运行的 WeKnora。

```bash
cd examples/chatbot-integration
npm install
npm run dev
```

打开 **http://127.0.0.1:5180**，在左侧填写 WeKnora 网页地址、渠道 ID 和渠道发布 Token，点击「连接助手」。就绪后，可以在嵌入页聊天，也可以点击下方示例问题。

开发端口固定为 5180；端口被占用时会直接报错，避免端口自动变化导致渠道白名单失效。页面中的手动配置只保存在 React 内存，不写入 localStorage。

## 配置渠道

1. 在 WeKnora 中准备一个已配置模型和知识库的智能体。
2. 打开智能体的「网页嵌入」面板，创建、启用并绑定渠道，取得**渠道 ID**和 `em_` 开头的**发布 Token**。
3. 将本示例实际访问来源加入渠道的允许域名。例如使用默认启动地址时，添加 `http://127.0.0.1:5180`。
4. 在示例中填写 WeKnora 的网页源站，如 `http://localhost:5173`。如果 `make dev-frontend` 实际运行在 5174，则填写 `http://localhost:5174`。该源站需提供 `/embed/:channelId` 页面和 `/weknora-widget.js`，以及嵌入页使用的 API 代理。
5. 点击「连接助手」。切换展示模式、语言或凭证后，点击「重新连接助手」应用配置。

`localhost` 与 `127.0.0.1` 是不同的来源。白名单填写**React 宿主页面的来源**，不是 WeKnora 的地址；以页面左下方显示的来源为准。使用 `npm run preview` 时，宿主来源变为 `http://127.0.0.1:4180`，需要另行加入。

当前 WeKnora 的生产嵌入页会把发布 Token 换成短效会话 Token，**Token 交换需要 WeKnora 配置 Redis**。仅将本地 Go 改成无 Redis 的 Lite 模式不能保证生产网页嵌入可用；开发构建中的降级行为也不代表生产行为。

## 使用环境变量

也可以通过环境变量预填配置，配置完整时会自动连接：

```bash
cp .env.example .env.local
```

编辑 `.env.local`：

```dotenv
VITE_WEKNORA_BASE_URL=http://localhost:5174
VITE_WEKNORA_CHANNEL_ID=替换为渠道UUID
VITE_WEKNORA_PUBLISH_TOKEN=em_替换为渠道发布Token
VITE_WEKNORA_TOKEN_ENDPOINT=
```

修改后重新启动 Vite。所有 `VITE_` 变量都会被编译进浏览器代码，发布 Token 在静态接入模式下对访客可见；不要填写管理 API Key 或业务后端密钥。`.env.local` 已被 Git 忽略。

## Token 接口模式

悬浮模式支持官方 SDK 的 `tokenEndpoint`。在页面中将凭证类型改成「Token 接口」，或配置：

```dotenv
VITE_WEKNORA_BASE_URL=https://weknora.example.com
VITE_WEKNORA_CHANNEL_ID=替换为渠道UUID
VITE_WEKNORA_PUBLISH_TOKEN=
VITE_WEKNORA_TOKEN_ENDPOINT=https://your-business.example.com/api/weknora/embed-token
```

本示例不提供业务后端。这个接口需要由你的后端实现：验证业务访客身份和权限，使用服务端保管的发布 Token 调用 `POST /api/v1/embed/:channelId/exchange`，然后将结果转换为：

```json
{ "token": "ems_短效会话Token", "expiresIn": 1800 }
```

服务端调用 exchange 时，发送 `Authorization: Embed em_...` 和命中渠道白名单的宿主 `Origin`。Widget 以携带 Cookie 的 GET 请求获取凭证，并负责定时刷新。跨域接口需配置正确的凭证 CORS；相对路径接口需由实际宿主站点提供，Vite 示例没有自带该 API 或代理。

本示例的 Token 接口接入仅支持悬浮模式。不要手工把会过期的 `ems_` Token 当成长效配置。

## 在其他 React 项目中使用

复制 `src/embed/` 中的三个非测试文件，给内嵌 iframe 设置容器高度：

```tsx
import { useMemo, useRef } from 'react'
import { normalizeConfig } from './embed/config'
import { WeKnoraChatbot, type ChatbotHandle } from './embed/WeKnoraChatbot'

export function SupportChat() {
  const chatbot = useRef<ChatbotHandle>(null)
  const config = useMemo(() => normalizeConfig({
    baseUrl: 'https://weknora.example.com',
    channelId: 'your-channel-id',
    token: 'em_your-publish-token',
    tokenEndpoint: '',
    title: '知识助手',
    locale: 'zh-CN',
    primaryColor: '#16845b',
    mode: 'inline', // 或 widget
  }), [])

  return <>
    <div style={{ height: 640 }}>
      <WeKnoraChatbot ref={chatbot} config={config}
        onState={(state, message) => console.log(state, message)}
        onEvent={(event) => console.log(event.type)} />
    </div>
    {/* 收到 ready 状态后再允许发送问题。 */}
    <button onClick={() => chatbot.current?.ask('如何使用知识库？')}>提问</button>
  </>
}
```

```css
.chat-frame { display: block; width: 100%; height: 100%; border: 0; }
```

- `config` 保持引用稳定，配置变化时组件会重新连接。
- `ref.open()` / `ref.close()` 操作悬浮窗口；`ref.ask(query)` 在就绪后发送问题，悬浮模式会自动打开窗口。
- `onState`：`connecting`、`ready`、`error`。收到可信的嵌入页 `ready` 事件才算连接就绪，iframe `load` 不代表认证或聊天成功。
- `onEvent`：`ready`、`message_sent`、`message_received`。示例事件面板只显示事件类型和少量会话信息，不展示 Token 或聊天正文。
- 组件向助手传入当前页面路径和集成来源，不会自动传递宿主用户身份。宿主上下文是提问上下文，不能作为服务端身份认证依据。
- 内嵌模式通过固定目标源的 `postMessage` 提供 Token，不把 Token 拼进 URL；接收消息同时检查源站、iframe 窗口和渠道。
- 官方 Widget SDK 为页面级单例，同一页面只挂载一个悬浮组件；卸载时销毁窗口和监听器，支持 React StrictMode。
- 标题和主要聊天样式由 WeKnora 渠道配置控制；本示例的颜色选择控制悬浮按钮，不覆盖渠道内的聊天主题。

## 代码结构

```text
chatbot-integration/
├── .env.example                 # 环境变量模板
├── index.html
├── package.json
├── src/
│   ├── App.tsx                  # 配置、聊天容器、示例问题及事件面板
│   ├── main.tsx
│   ├── styles.css               # 响应式页面
│   └── embed/
│       ├── config.ts            # 配置校验和无 Token 的嵌入地址
│       ├── widget.ts            # SDK 加载、复用和失败重试
│       ├── WeKnoraChatbot.tsx   # 可复用 React 组件和生命周期管理
│       └── *.test.*             # 配置及官方 SDK / bridge 集成测试
├── tsconfig.json
└── vite.config.ts
```

## 验证与排查

```bash
npm test
npm run build
npm run preview
```

页面能打开但聊天未就绪时，检查嵌入页中的实际错误信息，并依次确认网页源站、渠道是否启用、发布 Token、宿主白名单、模型配置和 Redis。SDK 加载失败时，直接检查 `<WeKnora网页源站>/weknora-widget.js` 是否可访问。HTTPS 宿主需要使用 HTTPS WeKnora 地址，避免浏览器阻止混合内容。

如果嵌入页显示 `embed session signing key is not configured: set SYSTEM_SIGNING_KEY on the server`，需要在 **WeKnora 后端**生成并持久配置签名密钥（可用 `openssl rand -hex 32` 生成，保存为 `SYSTEM_SIGNING_KEY`），让后端进程或容器加载该配置后重新启动。它不是 React 的 `VITE_` 变量；已有的 `SYSTEM_AES_KEY` 应保持原值。见 [官方故障排查](../../website-docs/01-getting-started/05-troubleshooting.md)。

配置测试验证 URL 和凭证行为；组件测试验证可信消息来源、发送问题、StrictMode、卸载清理，并执行仓库中真实的 Widget SDK。真实问答仍需连接你自己的有效渠道和模型。

完整协议见 [WeKnora 网页嵌入文档](../../website-docs/03-features/13-embed-channel.md)。
