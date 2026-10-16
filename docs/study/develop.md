# 本地开发环境启动指南

WeKnora 使用 Docker 运行基础设施，在本地运行 Go 后端、Vue 前端和文档解析服务。

本文按当前 `.env.local` 配置整理；配置调整后，以实际端口和服务模式为准。

## 环境准备

- Go 1.26（当前已安装 1.26.2）
- Node.js 22 和 npm（当前已安装 Node.js 22.22.2）
- Docker Desktop 和 Docker Compose
- uv，以及 Python >= 3.10.18（用于本地文档解析服务）

当前已有 `.env`、`.env.local` 和 docreader Python 虚拟环境，无需重新复制配置文件。开发脚本先加载 `.env`，再用 `.env.local` 覆盖。

首次在其他环境配置时，仅在 `.env` 不存在时执行：

```bash
cp .env.example .env
```

当前 `.env.local` 中影响启动方式的配置为：

```dotenv
DB_PORT=5434
REDIS_PORT=6381
REDIS_ADDR=127.0.0.1:6381
LANGFUSE_ENABLED=false
DEV_LANGFUSE_ENABLED=false
DEV_DOCREADER_MODE=local
DEV_FRONTEND_PORT=5174
```

## 启动步骤

先打开 Docker Desktop，等待 Docker 引擎启动。可执行以下命令确认：

```bash
docker info
```

以下命令都从项目根目录执行。当前本机路径为：

```bash
cd /Users/barryshen/source_code/WeKnora
```

### 1. 启动基础设施

```bash
make dev-start
```

按当前配置，该命令启动 PostgreSQL 和 Redis。Langfuse 已关闭，docreader 设置为本地运行，因此需要单独启动。

### 2. 启动文档解析服务

另开一个终端，进入项目根目录：

```bash
make dev-docreader
```

首次运行或提示缺少依赖时，先安装依赖，再启动：

```bash
(cd docreader && uv sync --locked)
make dev-docreader
```

### 3. 启动 Go 后端

另开一个终端，进入项目根目录：

```bash
make dev-app
```

开发脚本会加载环境配置，并将数据库、Redis 和文档解析服务的连接地址设置为本地地址。

### 4. 启动前端

另开一个终端，进入项目根目录：

```bash
make dev-frontend
```

如果 `frontend/node_modules` 不存在，脚本会自动执行 `npm install`。前端将 API 请求代理到 `http://localhost:8080`。

## 访问地址

| 服务 | 当前地址或端口 |
| --- | --- |
| 前端 | http://localhost:5174 |
| 后端 API | http://localhost:8080 |
| PostgreSQL | `localhost:5434` |
| Redis | `localhost:6381` |
| docreader（gRPC） | `localhost:50051` |

当前前端端口为 **5174**，覆盖了项目默认的 5173。

## 代码修改与重启

- 前端支持 Vite 热更新。
- 后端未安装 Air 时，修改 Go 代码后需要按 `Ctrl+C` 停止，再运行 `make dev-app`。
- 如需后端自动重启，可安装 Air；开发脚本检测到后会自动使用：

```bash
go install github.com/air-verse/air@latest
```

安装后需确保 Go 的二进制安装目录在 `PATH` 中，再重新运行 `make dev-app`。

## 状态、日志与停止

```bash
# 查看 Docker 基础设施状态
make dev-status

# 查看 Docker 基础设施日志
make dev-logs

# 停止 Docker 基础设施
make dev-stop
```

本地运行的 docreader、后端和前端需要在各自终端按 `Ctrl+C` 停止。`make dev-stop` 不会停止这些本地进程。

## 常见问题

- **无法连接 Docker daemon**：打开 Docker Desktop，等待引擎启动后重试。
- **docreader 环境不存在或缺少依赖**：在项目根目录执行 `(cd docreader && uv sync --locked)`。
- **端口冲突**：在 `.env.local` 调整 `DB_PORT`、`REDIS_PORT`、`DOCREADER_PORT` 或 `DEV_FRONTEND_PORT`，然后重启对应服务。

完整开发说明见 [项目开发指南](website-docs/06-development/01-dev-guide.md)。
