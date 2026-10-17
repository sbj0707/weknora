# WeKnora Go 接口文档

生成日期：2026-10-17（Asia/Shanghai）。适用于本仓库当前 Go 主后端 HTTP API。

本文包含 **386 个接口参考条目**（385 个来自当前 Go Swagger 注释，1 个对话链路接口按源码补充），以及 **96 条未被这些说明覆盖的路由声明**和 **266 个数据结构**。

资料来源：Go Handler 注释重新生成的 Swagger、[internal/router/router.go](../../internal/router/router.go)、[internal/router/](../../internal/router/) 和 [internal/handler/](../../internal/handler/)。路由索引通过 Go AST 静态提取，包含条件注册分支，不代表某个部署实例实际全部启用；不包含 Python DocReader gRPC 服务和 Lite 前端静态资源。

## 目录

- [调用约定](#conventions)
- [接口总览](#index)
- [DataSource（15）](#group-01)
- [FAQ管理（13）](#group-02)
- [IM 回调（2）](#group-03)
- [IM 渠道（5）](#group-04)
- [Knowledge（1）](#group-05)
- [MCP服务（20）](#group-06)
- [MCP端点（7）](#group-07)
- [Me（5）](#group-08)
- [SandboxConfig（17）](#group-09)
- [Skills（7）](#group-10)
- [StorageBackend（9）](#group-11)
- [System Admin（15）](#group-12)
- [User（3）](#group-13)
- [VectorStore（8）](#group-14)
- [WeKnoraCloud（2）](#group-15)
- [Wiki（22）](#group-16)
- [knowledge（1）](#group-17)
- [会话（19）](#group-18)
- [其他（3）](#group-19)
- [分块（1）](#group-20)
- [分块管理（6）](#group-21)
- [初始化（17）](#group-22)
- [审计日志（2）](#group-23)
- [我的邀请（5）](#group-24)
- [智能体（9）](#group-25)
- [标签管理（4）](#group-26)
- [模型管理（8）](#group-27)
- [消息（5）](#group-28)
- [知识（2）](#group-29)
- [知识库（14）](#group-30)
- [知识库共享（5）](#group-31)
- [知识管理（25）](#group-32)
- [空间成员（5）](#group-33)
- [空间管理（14）](#group-34)
- [空间邀请（4）](#group-35)
- [系统（8）](#group-36)
- [系统管理（1）](#group-37)
- [组织（3）](#group-38)
- [组织管理（23）](#group-39)
- [网络搜索（7）](#group-40)
- [认证（17）](#group-41)
- [评估（2）](#group-42)
- [长期记忆（16）](#group-43)
- [问答（9）](#group-44)
- [未被 Swagger 覆盖的路由](#unannotated)
- [源码与接口注释的路径差异](#drift)
- [数据结构目录](#models)
- [更新说明](#update)

<a id="conventions"></a>

## 调用约定

- 开发环境地址通常为 `http://localhost:8080`；实际地址由部署决定。业务 API 通常带 `/api/v1` 前缀，下面每个接口都给出完整路径。
- Web 用户：`Authorization: Bearer <access_token>`。机器调用：`X-API-Key: <api_key>`；平台 Key 调用空间接口通常还需 `X-Tenant-ID`。
- 认证方案只是声明的凭证类型，不能代替 Viewer / Admin / Owner、资源归属及 API Key capability 校验。
- Embed、IM 回调、短时资源授权 URL、终端 WebSocket 与 MCP Server 使用各自的认证方式；见路由源码和 [API 总览](../../website-docs/04-api/01-api-overview.md)。
- `required` 来自 Swagger 声明，不代表缺少此字段必定由同一个校验器拦截。注释可能落后于实现，路由和 Handler 是最终依据。
- 参数表中的 body 类型、响应类型可点击跳转到数据结构。`object` / 未声明表示注释未提供更精确的结构，不臆造字段。
- 多数成功响应为 `{"success":true,"data":...}`，列表可能另含 `total`、`page`、`page_size`；部分系统接口和流式接口有自己的响应格式。
- 常见错误格式为 `{"success":false,"error":{"code":1003,"message":"...","details":null}}`；认证中间件可能直接返回 `{"error":"..."}`。
- `text/event-stream` 表示 SSE 流。子步骤耗时可能重叠，不能直接相加；流式接口应按事件消费，不能当作单次 JSON 响应解析。

```bash
curl http://localhost:8080/health
curl http://localhost:8080/api/v1/knowledge-bases \
  -H "Authorization: Bearer <access_token>"
```

<a id="index"></a>

## 接口总览

| 模块 | 接口数量 |
| --- | --- |
| [DataSource](#group-01) | 15 |
| [FAQ管理](#group-02) | 13 |
| [IM 回调](#group-03) | 2 |
| [IM 渠道](#group-04) | 5 |
| [Knowledge](#group-05) | 1 |
| [MCP服务](#group-06) | 20 |
| [MCP端点](#group-07) | 7 |
| [Me](#group-08) | 5 |
| [SandboxConfig](#group-09) | 17 |
| [Skills](#group-10) | 7 |
| [StorageBackend](#group-11) | 9 |
| [System Admin](#group-12) | 15 |
| [User](#group-13) | 3 |
| [VectorStore](#group-14) | 8 |
| [WeKnoraCloud](#group-15) | 2 |
| [Wiki](#group-16) | 22 |
| [knowledge](#group-17) | 1 |
| [会话](#group-18) | 19 |
| [其他](#group-19) | 3 |
| [分块](#group-20) | 1 |
| [分块管理](#group-21) | 6 |
| [初始化](#group-22) | 17 |
| [审计日志](#group-23) | 2 |
| [我的邀请](#group-24) | 5 |
| [智能体](#group-25) | 9 |
| [标签管理](#group-26) | 4 |
| [模型管理](#group-27) | 8 |
| [消息](#group-28) | 5 |
| [知识](#group-29) | 2 |
| [知识库](#group-30) | 14 |
| [知识库共享](#group-31) | 5 |
| [知识管理](#group-32) | 25 |
| [空间成员](#group-33) | 5 |
| [空间管理](#group-34) | 14 |
| [空间邀请](#group-35) | 4 |
| [系统](#group-36) | 8 |
| [系统管理](#group-37) | 1 |
| [组织](#group-38) | 3 |
| [组织管理](#group-39) | 23 |
| [网络搜索](#group-40) | 7 |
| [认证](#group-41) | 17 |
| [评估](#group-42) | 2 |
| [长期记忆](#group-43) | 16 |
| [问答](#group-44) | 9 |

<a id="group-01"></a>

## DataSource

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/datasource`](#api-001) | List data sources for a knowledge base |
| POST | [`/api/v1/datasource`](#api-002) | Create a new data source |
| GET | [`/api/v1/datasource/logs/{log_id}`](#api-003) | Get specific sync log |
| GET | [`/api/v1/datasource/types`](#api-004) | Get available connectors |
| POST | [`/api/v1/datasource/validate-credentials`](#api-005) | Test connection with raw credentials (no persistence) |
| DELETE | [`/api/v1/datasource/{id}`](#api-006) | Delete a data source |
| GET | [`/api/v1/datasource/{id}`](#api-007) | Get a data source by ID |
| PUT | [`/api/v1/datasource/{id}`](#api-008) | Update a data source |
| GET | [`/api/v1/datasource/{id}/logs`](#api-009) | Get sync logs |
| POST | [`/api/v1/datasource/{id}/pause`](#api-010) | Pause data source |
| POST | [`/api/v1/datasource/{id}/resource-ancestors`](#api-011) | Resolve resource ancestors |
| GET | [`/api/v1/datasource/{id}/resources`](#api-012) | List available resources in data source |
| POST | [`/api/v1/datasource/{id}/resume`](#api-013) | Resume data source |
| POST | [`/api/v1/datasource/{id}/sync`](#api-014) | Trigger immediate sync |
| POST | [`/api/v1/datasource/{id}/validate`](#api-015) | Test data source connection |

<a id="api-001"></a>

### GET `/api/v1/datasource`

List data sources for a knowledge base

List all data sources for a specific knowledge base

实现：[ListDataSources](../../internal/handler/datasource.go#L152)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | query | 是 | string | Knowledge base ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | array&lt;[types.DataSource](#model-123)&gt; |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-002"></a>

### POST `/api/v1/datasource`

Create a new data source

Create a new data source configuration for a knowledge base

实现：[CreateDataSource](../../internal/handler/datasource.go#L81)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.DataSource](#model-123) | Data source configuration |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | [types.DataSource](#model-123) |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-003"></a>

### GET `/api/v1/datasource/logs/{log_id}`

Get specific sync log

Retrieve a specific sync log entry

实现：[GetSyncLog](../../internal/handler/datasource.go#L569)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| log_id | path | 是 | string | Sync log ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.SyncLog](#model-222) |
| 404 | Not Found | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-004"></a>

### GET `/api/v1/datasource/types`

Get available connectors

Get list of available data source connectors

实现：[GetAvailableConnectors](../../internal/handler/datasource.go#L602)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | array&lt;[github_com_Tencent_WeKnora_internal_datasource.ConnectorMetadata](#model-010)&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-005"></a>

### POST `/api/v1/datasource/validate-credentials`

Test connection with raw credentials (no persistence)

Validate connectivity to an external data source using type + credentials

实现：[ValidateCredentials](../../internal/handler/datasource.go#L295)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | type and credentials |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | map&lt;string, string&gt; |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-006"></a>

### DELETE `/api/v1/datasource/{id}`

Delete a data source

Delete a data source (soft delete)

实现：[DeleteDataSource](../../internal/handler/datasource.go#L233)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 204 | No Content | 未声明 |
| 404 | Not Found | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-007"></a>

### GET `/api/v1/datasource/{id}`

Get a data source by ID

Retrieve a data source configuration by ID

实现：[GetDataSource](../../internal/handler/datasource.go#L124)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.DataSource](#model-123) |
| 404 | Not Found | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-008"></a>

### PUT `/api/v1/datasource/{id}`

Update a data source

Update an existing data source configuration

实现：[UpdateDataSource](../../internal/handler/datasource.go#L188)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |
| request | body | 是 | [types.DataSource](#model-123) | Updated configuration |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.DataSource](#model-123) |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-009"></a>

### GET `/api/v1/datasource/{id}/logs`

Get sync logs

Retrieve sync history for a data source

实现：[GetSyncLogs](../../internal/handler/datasource.go#L513)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |
| limit | query | 否 | integer | Limit (default: 10) |
| offset | query | 否 | integer | Offset (default: 0) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | array&lt;[types.SyncLog](#model-222)&gt; |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-010"></a>

### POST `/api/v1/datasource/{id}/pause`

Pause data source

Pause a data source's scheduled syncs

实现：[PauseDataSource](../../internal/handler/datasource.go#L451)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | map&lt;string, string&gt; |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-011"></a>

### POST `/api/v1/datasource/{id}/resource-ancestors`

Resolve resource ancestors

Resolve the ancestor ExternalIDs that must be expanded to reveal the given (possibly deeply nested) resources in a lazily-loaded picker. Used to restore an existing selection when editing a data source.

实现：[ResolveResourceAncestors](../../internal/handler/datasource.go#L371)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |
| request | body | 是 | [internal_handler.resolveAncestorsRequest](#model-087) | Resource IDs to resolve |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | map&lt;string, array&lt;string&gt;&gt; |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-012"></a>

### GET `/api/v1/datasource/{id}/resources`

List available resources in data source

List resources available for sync in the external system. Pass parent_id to lazily load the direct children of a resource (used for large hierarchical sources such as Feishu wiki).

实现：[ListAvailableResources](../../internal/handler/datasource.go#L334)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |
| parent_id | query | 否 | string | Parent resource ExternalID; empty lists the top level |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | array&lt;[types.Resource](#model-198)&gt; |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-013"></a>

### POST `/api/v1/datasource/{id}/resume`

Resume data source

Resume a paused data source

实现：[ResumeDataSource](../../internal/handler/datasource.go#L482)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | map&lt;string, string&gt; |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-014"></a>

### POST `/api/v1/datasource/{id}/sync`

Trigger immediate sync

Trigger an immediate sync for a data source

实现：[ManualSync](../../internal/handler/datasource.go#L419)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.SyncLog](#model-222) |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="api-015"></a>

### POST `/api/v1/datasource/{id}/validate`

Test data source connection

Validate the connection to an external data source

实现：[ValidateConnection](../../internal/handler/datasource.go#L264)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Data source ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | map&lt;string, string&gt; |
| 400 | Bad Request | map&lt;string, string&gt; |

[返回模块目录](#group-01) · [返回总目录](#index)

<a id="group-02"></a>

## FAQ管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/faq/import/progress/{task_id}`](#api-016) | 获取FAQ导入进度 |
| DELETE | [`/api/v1/knowledge-bases/{id}/faq/entries`](#api-017) | 批量删除FAQ条目 |
| GET | [`/api/v1/knowledge-bases/{id}/faq/entries`](#api-018) | 获取FAQ条目列表 |
| POST | [`/api/v1/knowledge-bases/{id}/faq/entries`](#api-019) | 批量更新/插入FAQ条目 |
| GET | [`/api/v1/knowledge-bases/{id}/faq/entries/export`](#api-020) | 导出FAQ条目 |
| PUT | [`/api/v1/knowledge-bases/{id}/faq/entries/fields`](#api-021) | 批量更新FAQ字段 |
| PUT | [`/api/v1/knowledge-bases/{id}/faq/entries/tags`](#api-022) | 批量更新FAQ标签 |
| GET | [`/api/v1/knowledge-bases/{id}/faq/entries/{entry_id}`](#api-023) | 获取FAQ条目详情 |
| PUT | [`/api/v1/knowledge-bases/{id}/faq/entries/{entry_id}`](#api-024) | 更新FAQ条目 |
| POST | [`/api/v1/knowledge-bases/{id}/faq/entries/{entry_id}/similar-questions`](#api-025) | 添加相似问 |
| POST | [`/api/v1/knowledge-bases/{id}/faq/entry`](#api-026) | 创建单个FAQ条目 |
| PUT | [`/api/v1/knowledge-bases/{id}/faq/import/last-result/display`](#api-027) | 更新FAQ最后一次导入结果显示状态 |
| POST | [`/api/v1/knowledge-bases/{id}/faq/search`](#api-028) | 搜索FAQ |

<a id="api-016"></a>

### GET `/api/v1/faq/import/progress/{task_id}`

获取FAQ导入进度

获取FAQ导入任务的进度

实现：[GetImportProgress](../../internal/handler/faq.go#L495)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| task_id | path | 是 | string | 任务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 导入进度 | object |
| 404 | 任务不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-017"></a>

### DELETE `/api/v1/knowledge-bases/{id}/faq/entries`

批量删除FAQ条目

批量删除指定的FAQ条目

实现：[DeleteEntries](../../internal/handler/faq.go#L333)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | object | 要删除的FAQ ID列表(seq_id) |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "ids": {
      "type": "array",
      "items": {
        "type": "integer"
      }
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-018"></a>

### GET `/api/v1/knowledge-bases/{id}/faq/entries`

获取FAQ条目列表

获取知识库下的FAQ条目列表，支持分页和筛选

实现：[ListEntries](../../internal/handler/faq.go#L61)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| page | query | 否 | integer | 页码 |
| page_size | query | 否 | integer | 每页数量 |
| tag_id | query | 否 | integer | 标签ID筛选(seq_id)，兼容旧版单标签 |
| tag_ids | query | 否 | string | 标签UUID筛选，逗号分隔（OR语义） |
| keyword | query | 否 | string | 关键词搜索 |
| search_field | query | 否 | string | 搜索字段: standard_question(标准问题), similar_questions(相似问法), answers(答案), 默认搜索全部 |
| sort_order | query | 否 | string | 排序方式: asc(按更新时间正序), 默认按更新时间倒序 |
| is_enabled | query | 否 | boolean | 启用状态筛选；不传时返回全部 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | FAQ列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-019"></a>

### POST `/api/v1/knowledge-bases/{id}/faq/entries`

批量更新/插入FAQ条目

异步批量更新或插入FAQ条目。支持 dry_run 模式（设置 dry_run=true），异步验证不实际导入。
dry_run 模式是异步操作，返回 task_id，通过 /faq/import/progress/{task_id} 查询进度和结果。
验证内容包括：1) 条目基本格式 2) 重复问题（批次内和知识库已有） 3) 内容安全检查。

实现：[UpsertEntries](../../internal/handler/faq.go#L145)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [types.FAQBatchUpsertPayload](#model-129) | 批量操作请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 任务ID | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-020"></a>

### GET `/api/v1/knowledge-bases/{id}/faq/entries/export`

导出FAQ条目

将所有FAQ条目导出为 CSV（默认）或 JSON。?format=json 返回与 FAQEntryPayload 结构兼容的数组。

实现：[ExportEntries](../../internal/handler/faq.go#L411)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`text/csv`, `application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| format | query | 否 | string | 导出格式：csv（默认）或 json |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 导出文件 | file |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-021"></a>

### PUT `/api/v1/knowledge-bases/{id}/faq/entries/fields`

批量更新FAQ字段

批量更新FAQ条目的多个字段（is_enabled, is_recommended, tag_id）

实现：[UpdateEntryFieldsBatch](../../internal/handler/faq.go#L300)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [types.FAQEntryFieldsBatchUpdate](#model-131) | 字段更新请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-022"></a>

### PUT `/api/v1/knowledge-bases/{id}/faq/entries/tags`

批量更新FAQ标签

批量更新FAQ条目的标签

实现：[UpdateEntryTagBatch](../../internal/handler/faq.go#L267)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | object | 标签更新请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-023"></a>

### GET `/api/v1/knowledge-bases/{id}/faq/entries/{entry_id}`

获取FAQ条目详情

根据ID获取单个FAQ条目的详情

实现：[GetEntry](../../internal/handler/faq.go#L458)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| entry_id | path | 是 | integer | FAQ条目ID(seq_id) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | FAQ条目详情 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 条目不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-024"></a>

### PUT `/api/v1/knowledge-bases/{id}/faq/entries/{entry_id}`

更新FAQ条目

更新指定的FAQ条目

实现：[UpdateEntry](../../internal/handler/faq.go#L223)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| entry_id | path | 是 | integer | FAQ条目ID(seq_id) |
| request | body | 是 | [types.FAQEntryPayload](#model-133) | FAQ条目 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-025"></a>

### POST `/api/v1/knowledge-bases/{id}/faq/entries/{entry_id}/similar-questions`

添加相似问

向指定的FAQ条目添加相似问题

实现：[AddSimilarQuestions](../../internal/handler/faq.go#L564)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| entry_id | path | 是 | integer | FAQ条目ID(seq_id) |
| request | body | 是 | [internal_handler.addSimilarQuestionsRequest](#model-074) | 相似问列表 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的FAQ条目 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 条目不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-026"></a>

### POST `/api/v1/knowledge-bases/{id}/faq/entry`

创建单个FAQ条目

同步创建单个FAQ条目

实现：[CreateEntry](../../internal/handler/faq.go#L186)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [types.FAQEntryPayload](#model-133) | FAQ条目 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 创建的FAQ条目 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-027"></a>

### PUT `/api/v1/knowledge-bases/{id}/faq/import/last-result/display`

更新FAQ最后一次导入结果显示状态

更新FAQ知识库导入结果统计卡片的显示或隐藏状态

实现：[UpdateLastImportResultDisplayStatus](../../internal/handler/faq.go#L528)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [internal_handler.updateLastFAQImportResultDisplayStatusRequest](#model-092) | 状态更新请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 知识库不存在或无导入记录 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="api-028"></a>

### POST `/api/v1/knowledge-bases/{id}/faq/search`

搜索FAQ

使用混合搜索在FAQ中搜索，支持两级优先级标签召回：first_priority_tag_ids优先级最高，second_priority_tag_ids次之

实现：[SearchFAQ](../../internal/handler/faq.go#L368)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [types.FAQSearchRequest](#model-134) | 搜索请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 搜索结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-02) · [返回总目录](#index)

<a id="group-03"></a>

## IM 回调

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/im/callback/{channel_id}`](#api-029) | IM 平台回调 |
| POST | [`/api/v1/im/callback/{channel_id}`](#api-030) | IM 平台回调 |

<a id="api-029"></a>

### GET `/api/v1/im/callback/{channel_id}`

IM 平台回调

接收各 IM 平台的事件回调；走平台自身签名校验，不使用 API Key

实现：[IMCallback](../../internal/handler/im.go#L396)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| channel_id | path | 是 | string | 渠道 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 处理结果 | object |
| 400 | 请求参数错误 | object |
| 401 | 签名校验失败 | object |

[返回模块目录](#group-03) · [返回总目录](#index)

<a id="api-030"></a>

### POST `/api/v1/im/callback/{channel_id}`

IM 平台回调

接收各 IM 平台的事件回调；走平台自身签名校验，不使用 API Key

实现：[IMCallback](../../internal/handler/im.go#L396)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| channel_id | path | 是 | string | 渠道 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 处理结果 | object |
| 400 | 请求参数错误 | object |
| 401 | 签名校验失败 | object |

[返回模块目录](#group-03) · [返回总目录](#index)

<a id="group-04"></a>

## IM 渠道

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| DELETE | [`/api/v1/im-channels/{id}`](#api-031) | 删除 IM 渠道 |
| PUT | [`/api/v1/im-channels/{id}`](#api-032) | 更新 IM 渠道 |
| POST | [`/api/v1/im-channels/{id}/toggle`](#api-033) | 启用/停用 IM 渠道 |
| POST | [`/api/v1/wechat/qrcode`](#api-034) | 获取微信扫码登录二维码 |
| POST | [`/api/v1/wechat/qrcode/status`](#api-035) | 轮询微信二维码状态 |

<a id="api-031"></a>

### DELETE `/api/v1/im-channels/{id}`

删除 IM 渠道

删除指定 IM 渠道

实现：[DeleteIMChannel](../../internal/handler/im.go#L308)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 渠道 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | success: true | object |
| 400 | 请求参数错误 | object |
| 404 | 渠道不存在 | object |

[返回模块目录](#group-04) · [返回总目录](#index)

<a id="api-032"></a>

### PUT `/api/v1/im-channels/{id}`

更新 IM 渠道

更新指定 IM 渠道的名称、模式、回复语言、知识库、凭证或启用状态

实现：[UpdateIMChannel](../../internal/handler/im.go#L189)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 渠道 ID |
| request | body | 是 | object | 更新字段（含 locale） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的渠道 | object |
| 400 | 请求参数错误 | object |
| 404 | 渠道不存在 | object |

[返回模块目录](#group-04) · [返回总目录](#index)

<a id="api-033"></a>

### POST `/api/v1/im-channels/{id}/toggle`

启用/停用 IM 渠道

切换指定 IM 渠道的启用状态

实现：[ToggleIMChannel](../../internal/handler/im.go#L343)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 渠道 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的渠道 | object |
| 400 | 请求参数错误 | object |
| 404 | 渠道不存在 | object |

[返回模块目录](#group-04) · [返回总目录](#index)

<a id="api-034"></a>

### POST `/api/v1/wechat/qrcode`

获取微信扫码登录二维码

申请一个用于扫码登录绑定的微信二维码（无请求体）

实现：[WeChatGetQRCode](../../internal/handler/wechat_qrcode.go#L14)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 二维码信息（qrcode_url + qrcode 标识） | object |
| 500 | 二维码生成失败 | object |

[返回模块目录](#group-04) · [返回总目录](#index)

<a id="api-035"></a>

### POST `/api/v1/wechat/qrcode/status`

轮询微信二维码状态

查询指定二维码是否已被扫描/确认/过期；confirmed 时返回凭证

实现：[WeChatPollQRCodeStatus](../../internal/handler/wechat_qrcode.go#L45)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | {qrcode: string} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 扫码状态 | object |
| 400 | 请求参数错误 | object |
| 500 | 服务器错误 | object |

[返回模块目录](#group-04) · [返回总目录](#index)

<a id="group-05"></a>

## Knowledge

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/knowledge/search`](#api-036) | Search knowledge |

<a id="api-036"></a>

### GET `/api/v1/knowledge/search`

Search knowledge

Search knowledge files by keyword. Pass recent=true without a keyword to browse recent files. When agent_id is set (shared agent), scope is the agent's configured knowledge bases.

实现：[SearchKnowledge](../../internal/handler/knowledge.go#L2599)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| keyword | query | 否 | string | Keyword to search |
| offset | query | 否 | integer | Offset for pagination (minimum 0)；最小值：0 |
| limit | query | 否 | integer | Limit for pagination (default 20, maximum 100)；最小值：1；最大值：100 |
| file_types | query | 否 | string | Comma-separated file extensions to filter (e.g., csv,xlsx) |
| agent_id | query | 否 | string | Shared agent ID (search within agent's KB scope) |
| recent | query | 否 | boolean | Return recent files when keyword is empty |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Search results | object |
| 400 | Invalid request | [errors.AppError](#model-005) |

[返回模块目录](#group-05) · [返回总目录](#index)

<a id="group-06"></a>

## MCP服务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/agent/mcp-oauth-resolutions/{pending_id}`](#api-037) | 完成对话内 MCP OAuth 授权 |
| POST | [`/api/v1/agent/mcp-oauth-resolutions/{pending_id}/cancel`](#api-038) | 跳过对话内 MCP OAuth 授权 |
| POST | [`/api/v1/agent/tool-approvals/{pending_id}`](#api-039) | 处理 MCP 工具调用待审批请求 |
| GET | [`/api/v1/mcp-oauth/callback`](#api-040) | MCP OAuth 回调 |
| GET | [`/api/v1/mcp-services`](#api-041) | 获取MCP服务列表 |
| POST | [`/api/v1/mcp-services`](#api-042) | 创建MCP服务 |
| DELETE | [`/api/v1/mcp-services/{id}`](#api-043) | 删除MCP服务 |
| GET | [`/api/v1/mcp-services/{id}`](#api-044) | 获取MCP服务详情 |
| PUT | [`/api/v1/mcp-services/{id}`](#api-045) | 更新MCP服务 |
| PUT | [`/api/v1/mcp-services/{id}/credentials`](#api-046) | 设置 MCP 服务凭据 |
| DELETE | [`/api/v1/mcp-services/{id}/credentials/{field}`](#api-047) | 移除 MCP 服务的单个凭据字段 |
| GET | [`/api/v1/mcp-services/{id}/metadata`](#api-048) | 读取已保存的 MCP 工具目录 |
| POST | [`/api/v1/mcp-services/{id}/metadata/refresh`](#api-049) | 同步 MCP 工具目录 |
| POST | [`/api/v1/mcp-services/{id}/oauth/authorize-url`](#api-050) | 发起 MCP OAuth 授权 |
| GET | [`/api/v1/mcp-services/{id}/oauth/status`](#api-051) | 查询 MCP OAuth 授权状态 |
| DELETE | [`/api/v1/mcp-services/{id}/oauth/token`](#api-052) | 撤销 MCP OAuth 授权 |
| GET | [`/api/v1/mcp-services/{id}/resources`](#api-053) | 获取MCP服务资源列表 |
| POST | [`/api/v1/mcp-services/{id}/test`](#api-054) | 测试MCP服务连接 |
| PUT | [`/api/v1/mcp-services/{id}/tool-approvals/{tool_name}`](#api-055) | 设置 MCP 工具策略 |
| GET | [`/api/v1/mcp-services/{id}/tools`](#api-056) | 获取MCP服务工具列表 |

<a id="api-037"></a>

### POST `/api/v1/agent/mcp-oauth-resolutions/{pending_id}`

完成对话内 MCP OAuth 授权

用户在对话中完成 OAuth 授权后调用，校验令牌存在后恢复被暂停的 Agent 工具调用

实现：[ResolveMCPOAuth](../../internal/handler/mcp_oauth.go#L260)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| pending_id | path | 是 | string | 待授权 ID |
| request | body | 是 | object | {service_id: string} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 400 | Bad Request | [errors.AppError](#model-005) |
| 409 | 用户尚未完成授权 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-038"></a>

### POST `/api/v1/agent/mcp-oauth-resolutions/{pending_id}/cancel`

跳过对话内 MCP OAuth 授权

用户主动跳过 OAuth 授权，解除 Agent 阻塞

实现：[CancelMCPOAuth](../../internal/handler/mcp_oauth.go#L374)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| pending_id | path | 是 | string | 待授权 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-039"></a>

### POST `/api/v1/agent/tool-approvals/{pending_id}`

处理 MCP 工具调用待审批请求

用户审批通过或驳回一次工具调用（用于 Agent 阻塞等待审批的场景）

实现：[ResolveToolApproval](../../internal/handler/mcp_service.go#L717)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| pending_id | path | 是 | string | 待审批记录 ID |
| request | body | 是 | object | {decision: \ |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 审批结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 待审批记录不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-040"></a>

### GET `/api/v1/mcp-oauth/callback`

MCP OAuth 回调

接收授权服务器回调并完成 code 交换，随后重定向回前端

实现：[Callback](../../internal/handler/mcp_oauth.go#L123)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| code | query | 否 | string | 授权码 |
| state | query | 否 | string | 状态参数 |
| error | query | 否 | string | 授权错误码 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 302 | Found | 未声明 |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-041"></a>

### GET `/api/v1/mcp-services`

获取MCP服务列表

获取当前空间的所有MCP服务（含已保存工具目录数量）

实现：[ListMCPServices](../../internal/handler/mcp_service.go#L127)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| agent_id | query | 否 | string | Agent ID; needs agent_source_tenant_id |
| agent_source_tenant_id | query | 否 | integer | Shared agent source workspace |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | MCP服务列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 无权使用该共享智能体 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-042"></a>

### POST `/api/v1/mcp-services`

创建MCP服务

创建新的MCP服务配置

实现：[CreateMCPService](../../internal/handler/mcp_service.go#L69)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.MCPService](#model-167) | MCP服务配置 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 创建的MCP服务 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-043"></a>

### DELETE `/api/v1/mcp-services/{id}`

删除MCP服务

删除指定的MCP服务

实现：[DeleteMCPService](../../internal/handler/mcp_service.go#L470)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP服务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 500 | 服务器错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-044"></a>

### GET `/api/v1/mcp-services/{id}`

获取MCP服务详情

根据ID获取MCP服务详情

实现：[GetMCPService](../../internal/handler/mcp_service.go#L220)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP服务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | MCP服务详情 | object |
| 404 | 服务不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-045"></a>

### PUT `/api/v1/mcp-services/{id}`

更新MCP服务

更新MCP服务配置

实现：[UpdateMCPService](../../internal/handler/mcp_service.go#L259)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP服务ID |
| request | body | 是 | object | 更新字段 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的MCP服务 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-046"></a>

### PUT `/api/v1/mcp-services/{id}/credentials`

设置 MCP 服务凭据

为指定字段写入新凭据；省略的字段保留原值；空字符串视为 no-op（如需删除请用 DELETE）

实现：[Put](../../internal/handler/mcp_credentials.go#L48)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP 服务 ID |
| request | body | 是 | object | {api_key?: string, token?: string} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 写入后的凭据状态 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 服务不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-047"></a>

### DELETE `/api/v1/mcp-services/{id}/credentials/{field}`

移除 MCP 服务的单个凭据字段

删除指定字段的存储凭据；删除已为空的字段是幂等的

实现：[DeleteField](../../internal/handler/mcp_credentials.go#L116)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP 服务 ID |
| field | path | 是 | string | 字段名（api_key &#124; token） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 204 | No Content | 未声明 |
| 400 | 字段名非法 | [errors.AppError](#model-005) |
| 404 | 服务不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-048"></a>

### GET `/api/v1/mcp-services/{id}/metadata`

读取已保存的 MCP 工具目录

只读数据库，不连接上游。未同步时 data 为 null；连接配置变更后 stale 为 true。OAuth 目录按当前授权主体隔离。

实现：[GetMCPMetadata](../../internal/handler/mcp_metadata.go#L16)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP服务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 目录快照 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 401 | OAuth 目录缺少授权主体 | [errors.AppError](#model-005) |
| 404 | 服务不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-049"></a>

### POST `/api/v1/mcp-services/{id}/metadata/refresh`

同步 MCP 工具目录

显式连接上游并原子替换完整目录。OAuth 服务写入当前用户的快照，Viewer 及以上可调用；静态认证写入租户共享快照，需要 Admin。

实现：[RefreshMCPMetadata](../../internal/handler/mcp_metadata.go#L32)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP服务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 同步后的目录快照 | object |
| 400 | 目录不完整或校验失败 | [errors.AppError](#model-005) |
| 401 | OAuth 目录缺少授权主体 | [errors.AppError](#model-005) |
| 403 | 静态认证目录需要管理员刷新 | [errors.AppError](#model-005) |
| 404 | 服务不存在 | [errors.AppError](#model-005) |
| 409 | 刷新期间连接配置已变更 | [errors.AppError](#model-005) |
| 503 | 元数据存储不可用 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-050"></a>

### POST `/api/v1/mcp-services/{id}/oauth/authorize-url`

发起 MCP OAuth 授权

对使用 OAuth 的 MCP 服务执行发现与动态客户端注册，返回浏览器应跳转的授权地址（当前用户维度）

实现：[AuthorizeURL](../../internal/handler/mcp_oauth.go#L58)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP 服务 ID |
| request | body | 是 | object | {redirect_uri: string, frontend_redirect?: string} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | {authorization_url: string, authorization_attempt: string} | object |
| 400 | Bad Request | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-051"></a>

### GET `/api/v1/mcp-services/{id}/oauth/status`

查询 MCP OAuth 授权状态

返回当前用户的 OAuth Token 生命周期状态；传 authorization_attempt 时只检查本次授权流程

实现：[Status](../../internal/handler/mcp_oauth.go#L172)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP 服务 ID |
| authorization_attempt | query | 否 | string | 本次授权尝试 ID；传入后不会接受历史 Token |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | {authorized: bool, state: string, refresh_available: bool, expires_at?: string} | object |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-052"></a>

### DELETE `/api/v1/mcp-services/{id}/oauth/token`

撤销 MCP OAuth 授权

删除当前用户对指定 MCP 服务的 OAuth 令牌

实现：[Revoke](../../internal/handler/mcp_oauth.go#L222)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP 服务 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 204 | No Content | 未声明 |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-053"></a>

### GET `/api/v1/mcp-services/{id}/resources`

获取MCP服务资源列表

获取MCP服务提供的资源列表

实现：[GetMCPServiceResources](../../internal/handler/mcp_service.go#L587)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP服务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 资源列表 | object |
| 500 | 服务器错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-054"></a>

### POST `/api/v1/mcp-services/{id}/test`

测试MCP服务连接

测试MCP服务是否可以正常连接

实现：[TestMCPService](../../internal/handler/mcp_service.go#L506)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP服务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 测试结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-055"></a>

### PUT `/api/v1/mcp-services/{id}/tool-approvals/{tool_name}`

设置 MCP 工具策略

为指定 MCP 服务下的某个工具更新启用状态和/或人工审批要求。至少提供 require_approval 或 enabled 之一；省略的字段保持原值。

实现：[SetMCPToolApproval](../../internal/handler/mcp_service.go#L656)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP 服务 ID |
| tool_name | path | 是 | string | 工具名 |
| request | body | 是 | object | {require_approval?: bool, enabled?: bool} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | MCP 服务或工具不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="api-056"></a>

### GET `/api/v1/mcp-services/{id}/tools`

获取MCP服务工具列表

获取MCP服务提供的工具列表

实现：[GetMCPServiceTools](../../internal/handler/mcp_service.go#L551)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | MCP服务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 工具列表 | object |
| 500 | 服务器错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-06) · [返回总目录](#index)

<a id="group-07"></a>

## MCP端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/mcp-endpoints`](#api-057) | 获取 MCP 端点列表 |
| POST | [`/api/v1/mcp-endpoints`](#api-058) | 创建 MCP 端点 |
| GET | [`/api/v1/mcp-endpoints/tools`](#api-059) | 获取 MCP 端点工具目录 |
| DELETE | [`/api/v1/mcp-endpoints/{endpoint_id}`](#api-060) | 删除 MCP 端点 |
| GET | [`/api/v1/mcp-endpoints/{endpoint_id}`](#api-061) | 获取 MCP 端点详情 |
| PUT | [`/api/v1/mcp-endpoints/{endpoint_id}`](#api-062) | 更新 MCP 端点 |
| POST | [`/api/v1/mcp-endpoints/{endpoint_id}/rotate-token`](#api-063) | 轮换 MCP 端点令牌 |

<a id="api-057"></a>

### GET `/api/v1/mcp-endpoints`

获取 MCP 端点列表

实现：[ListMCPEndpoints](../../internal/handler/mcp_endpoint.go#L118)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 端点列表 | object |

[返回模块目录](#group-07) · [返回总目录](#index)

<a id="api-058"></a>

### POST `/api/v1/mcp-endpoints`

创建 MCP 端点

为当前工作空间发布一个 MCP 端点，响应中的 token 只返回一次

实现：[CreateMCPEndpoint](../../internal/handler/mcp_endpoint.go#L66)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 端点配置：name、description、enabled、knowledge_base_ids、tools 等 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 创建的端点，含一次性 token | object |
| 400 | 请求参数错误 | object |

[返回模块目录](#group-07) · [返回总目录](#index)

<a id="api-059"></a>

### GET `/api/v1/mcp-endpoints/tools`

获取 MCP 端点工具目录

返回工作空间 MCP 端点可勾选的工具清单、分组和默认勾选项

实现：[ListToolCatalog](../../internal/handler/mcp_endpoint.go#L44)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 工具目录 | object |

[返回模块目录](#group-07) · [返回总目录](#index)

<a id="api-060"></a>

### DELETE `/api/v1/mcp-endpoints/{endpoint_id}`

删除 MCP 端点

实现：[DeleteMCPEndpoint](../../internal/handler/mcp_endpoint.go#L201)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| endpoint_id | path | 是 | string | 端点 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 404 | 端点不存在 | object |

[返回模块目录](#group-07) · [返回总目录](#index)

<a id="api-061"></a>

### GET `/api/v1/mcp-endpoints/{endpoint_id}`

获取 MCP 端点详情

实现：[GetMCPEndpoint](../../internal/handler/mcp_endpoint.go#L141)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| endpoint_id | path | 是 | string | 端点 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 端点详情 | object |
| 404 | 端点不存在 | object |

[返回模块目录](#group-07) · [返回总目录](#index)

<a id="api-062"></a>

### PUT `/api/v1/mcp-endpoints/{endpoint_id}`

更新 MCP 端点

实现：[UpdateMCPEndpoint](../../internal/handler/mcp_endpoint.go#L163)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| endpoint_id | path | 是 | string | 端点 ID |
| request | body | 是 | object | 要更新的字段，未提供的字段保持不变 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的端点 | object |
| 400 | 请求参数错误 | object |
| 404 | 端点不存在 | object |

[返回模块目录](#group-07) · [返回总目录](#index)

<a id="api-063"></a>

### POST `/api/v1/mcp-endpoints/{endpoint_id}/rotate-token`

轮换 MCP 端点令牌

生成新令牌并立即作废旧令牌，响应中的 token 只返回一次

实现：[RotateMCPEndpointToken](../../internal/handler/mcp_endpoint.go#L222)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| endpoint_id | path | 是 | string | 端点 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 含新 token 的端点 | object |
| 404 | 端点不存在 | object |

[返回模块目录](#group-07) · [返回总目录](#index)

<a id="group-08"></a>

## Me

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/me/env-vars`](#api-064) | List my environment variables |
| DELETE | [`/api/v1/me/env-vars/sandbox`](#api-065) | Delete one of my sandbox environment variables |
| PUT | [`/api/v1/me/env-vars/sandbox`](#api-066) | Set one of my sandbox environment variables |
| DELETE | [`/api/v1/me/env-vars/skill`](#api-067) | Delete one of my skill credentials |
| PUT | [`/api/v1/me/env-vars/skill`](#api-068) | Set one of my skill credentials |

<a id="api-064"></a>

### GET `/api/v1/me/env-vars`

List my environment variables

List every sandbox config of this workspace with the caller's own config-wide variables and the credentials its skills declared, each reporting whether it is unset, filled in workspace-wide, or filled in by the caller. Values are never returned.

实现：[List](../../internal/handler/me_env_var.go#L67)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | One group per sandbox config | object |
| 401 | Unauthorized | object |

[返回模块目录](#group-08) · [返回总目录](#index)

<a id="api-065"></a>

### DELETE `/api/v1/me/env-vars/sandbox`

Delete one of my sandbox environment variables

Remove the caller's own value for one variable on a sandbox config.

实现：[DeleteSandbox](../../internal/handler/me_env_var.go#L156)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.meEnvVarRequest](#model-084) | Sandbox config and variable name |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Deleted | object |
| 404 | Not set | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-08) · [返回总目录](#index)

<a id="api-066"></a>

### PUT `/api/v1/me/env-vars/sandbox`

Set one of my sandbox environment variables

Store the caller's own value for one variable on a sandbox config. It is injected into every skill script and shell command this caller's turns run on that config.

实现：[SetSandbox](../../internal/handler/me_env_var.go#L131)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.meEnvVarRequest](#model-084) | Sandbox config, variable name and value |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Stored | object |
| 400 | Invalid request | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-08) · [返回总目录](#index)

<a id="api-067"></a>

### DELETE `/api/v1/me/env-vars/skill`

Delete one of my skill credentials

Remove the caller's own value. The workspace-wide value, if there is one, applies again afterwards.

实现：[DeleteSkill](../../internal/handler/me_env_var.go#L108)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.meEnvVarRequest](#model-084) | Skill and variable name |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Deleted | object |
| 404 | Not set | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-08) · [返回总目录](#index)

<a id="api-068"></a>

### PUT `/api/v1/me/env-vars/skill`

Set one of my skill credentials

Store the caller's own value for one variable the skill declared. It overrides the workspace-wide value for this caller only, and is injected only into executions that name this skill.

实现：[SetSkill](../../internal/handler/me_env_var.go#L85)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.meEnvVarRequest](#model-084) | Skill, variable name and value |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Stored | object |
| 400 | Invalid request | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-08) · [返回总目录](#index)

<a id="group-09"></a>

## SandboxConfig

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/sandbox-configs`](#api-069) | List sandbox configs |
| POST | [`/api/v1/sandbox-configs`](#api-070) | Create sandbox config |
| DELETE | [`/api/v1/sandbox-configs/{id}`](#api-071) | Delete sandbox config |
| GET | [`/api/v1/sandbox-configs/{id}`](#api-072) | Get sandbox config |
| PUT | [`/api/v1/sandbox-configs/{id}`](#api-073) | Update sandbox config |
| GET | [`/api/v1/sandbox-configs/{id}/sandboxes`](#api-074) | Inspect sandbox config inventory |
| GET | [`/api/v1/sandbox-configs/{id}/skills`](#api-075) | List installed skills |
| POST | [`/api/v1/sandbox-configs/{id}/skills`](#api-076) | Install a skill |
| DELETE | [`/api/v1/sandbox-configs/{id}/skills/{skillId}`](#api-077) | Remove an installed skill |
| GET | [`/api/v1/sandbox-configs/{id}/skills/{skillId}`](#api-078) | Get an installed skill |
| PATCH | [`/api/v1/sandbox-configs/{id}/skills/{skillId}`](#api-079) | Update an installed skill |
| GET | [`/api/v1/sandbox-configs/{id}/skills/{skillId}/files`](#api-080) | List files of an installed skill |
| GET | [`/api/v1/sandbox-configs/{id}/skills/{skillId}/files/content`](#api-081) | Read one file of an installed skill |
| GET | [`/api/v1/sandbox-configs/{id}/skills/{skillId}/install-events`](#api-082) | Follow an install or removal |
| POST | [`/api/v1/sandbox-configs/{id}/skills/{skillId}/reinstall`](#api-083) | Retry a skill install |
| POST | [`/api/v1/sandbox-configs/{id}/skills/{skillId}/stop`](#api-084) | Stop a skill install |
| GET | [`/api/v1/sandbox-configs/{id}/skills/{skillId}/transcript`](#api-085) | Follow an install's agent transcript |

<a id="api-069"></a>

### GET `/api/v1/sandbox-configs`

List sandbox configs

List workspace sandbox backend configs with credentials masked.

实现：[List](../../internal/handler/sandbox_config.go#L242)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Sandbox configs and defaults | object |
| 401 | Unauthorized | object |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-070"></a>

### POST `/api/v1/sandbox-configs`

Create sandbox config

Create a named workspace sandbox backend config. Credentials are masked in the response.

实现：[Create](../../internal/handler/sandbox_config.go#L299)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.sandboxConfigRequest](#model-088) | Sandbox backend config |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created sandbox config | object |
| 400 | Invalid request or validation failure | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-071"></a>

### DELETE `/api/v1/sandbox-configs/{id}`

Delete sandbox config

Soft-delete a sandbox backend config. force=true only overrides unverifiable provider inventory, never confirmed live sandboxes.

实现：[Delete](../../internal/handler/sandbox_config.go#L408)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| force | query | 否 | boolean | Force delete when inventory is unverifiable |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Deletion success | object |
| 401 | Unauthorized | object |
| 409 | Live sandboxes or unverifiable inventory | object |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-072"></a>

### GET `/api/v1/sandbox-configs/{id}`

Get sandbox config

Retrieve a workspace sandbox backend config with credentials masked.

实现：[Get](../../internal/handler/sandbox_config.go#L334)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Sandbox config | object |
| 401 | Unauthorized | object |
| 404 | Sandbox config not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-073"></a>

### PUT `/api/v1/sandbox-configs/{id}`

Update sandbox config

Update a sandbox backend config. Identity-field changes are refused while the config owns live or paused sandboxes.

实现：[Update](../../internal/handler/sandbox_config.go#L362)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| request | body | 是 | [internal_handler.sandboxConfigRequest](#model-088) | Updated sandbox config |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Updated sandbox config | object |
| 400 | Invalid request or validation failure | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Sandbox config not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 409 | Live sandboxes or unverifiable inventory | object |
| 423 | Sandbox config is being modified by another request | object |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-074"></a>

### GET `/api/v1/sandbox-configs/{id}/sandboxes`

Inspect sandbox config inventory

Return live/paused sandbox inventory and affected agent names for one config.

实现：[Inventory](../../internal/handler/sandbox_config.go#L436)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Sandbox inventory | object |
| 401 | Unauthorized | object |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-075"></a>

### GET `/api/v1/sandbox-configs/{id}/skills`

List installed skills

List the agent skills installed onto one sandbox config's image.

实现：[List](../../internal/handler/sandbox_skill.go#L187)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Installed skills | object |
| 401 | Unauthorized | object |
| 404 | Sandbox config not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-076"></a>

### POST `/api/v1/sandbox-configs/{id}/skills`

Install a skill

Install a skill onto this sandbox config's image. Send a zip
as multipart form field "file", or JSON {"source":"..."} to
pull a public skill. source is one of: "@owner/slug" or a
slash-free slug (ClawHub), a github.com / gitlab.com /
skills.sh / clawhub / skillhub URL, a ClawHub skills-sh
catalog page (https://clawhub.ai/skills-sh/owner/repo/slug),
a skills-sh:owner/repo/slug locator, or a direct zip/SKILL.md
URL. Bare "owner/slug" is rejected as ambiguous. The source
must be readable anonymously. The install boots a sandbox and
runs for minutes, so the request is only accepted; follow it
via the install-events stream.

实现：[Upload](../../internal/handler/sandbox_skill.go#L285)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`, `multipart/form-data`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| file | formData | 否 | file | Skill bundle (zip) |
| request | body | 否 | [internal_handler.skillSourceRequest](#model-090) | Install from a registry, git host, or archive URL |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 202 | Install accepted | object |
| 400 | Missing, oversized or invalid bundle or source | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Sandbox config not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-077"></a>

### DELETE `/api/v1/sandbox-configs/{id}/skills/{skillId}`

Remove an installed skill

Remove a skill from the config's image. The removal rebuilds
the image and runs for minutes, so it is only accepted; follow
it via the install-events stream.

实现：[Delete](../../internal/handler/sandbox_skill.go#L532)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 202 | Removal accepted | object |
| 401 | Unauthorized | object |
| 404 | Skill not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-078"></a>

### GET `/api/v1/sandbox-configs/{id}/skills/{skillId}`

Get an installed skill

Retrieve one installed skill of a sandbox config.

实现：[Get](../../internal/handler/sandbox_skill.go#L212)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Installed skill | object |
| 401 | Unauthorized | object |
| 404 | Skill not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-079"></a>

### PATCH `/api/v1/sandbox-configs/{id}/skills/{skillId}`

Update an installed skill

Show or hide an installed skill and set the workspace-wide values of the environment variables it declared. Either field may be sent, or both. The files stay in the image either way; removal is a separate flow.

实现：[Patch](../../internal/handler/sandbox_skill.go#L477)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |
| request | body | 是 | [internal_handler.skillPatchRequest](#model-089) | Fields to update |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Updated skill | object |
| 400 | Invalid request | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Skill not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-080"></a>

### GET `/api/v1/sandbox-configs/{id}/skills/{skillId}/files`

List files of an installed skill

List files in the stored skill bundle without starting a sandbox.

实现：[ListFiles](../../internal/handler/sandbox_skill.go#L234)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Skill files | object |
| 401 | Unauthorized | object |
| 404 | Skill or files not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-081"></a>

### GET `/api/v1/sandbox-configs/{id}/skills/{skillId}/files/content`

Read one file of an installed skill

Read one skill file as UTF-8, a small base64 image, or binary.

实现：[GetFile](../../internal/handler/sandbox_skill.go#L258)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |
| path | query | 是 | string | Skill-root-relative file path |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Skill file | object |
| 400 | Invalid path | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Skill or file not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-082"></a>

### GET `/api/v1/sandbox-configs/{id}/skills/{skillId}/install-events`

Follow an install or removal

Server-sent progress for one install or removal. The stream
always terminates: with the run's own terminal event, with one
derived from the durable status, or with a "detached" frame
when it stops following a run that is still going.

实现：[InstallEvents](../../internal/handler/sandbox_skill.go#L619)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`text/event-stream`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | SSE stream of progress events | string |
| 401 | Unauthorized | object |
| 404 | Skill not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-083"></a>

### POST `/api/v1/sandbox-configs/{id}/skills/{skillId}/reinstall`

Retry a skill install

Retry a failed install from the stored archive; does not re-upload.

实现：[Reinstall](../../internal/handler/sandbox_skill.go#L393)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 202 | Reinstall accepted | object |
| 400 | The stored archive is gone | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Skill not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-084"></a>

### POST `/api/v1/sandbox-configs/{id}/skills/{skillId}/stop`

Stop a skill install

Abort an in-flight install so the operator can retry or uninstall. After a process restart the row may still say installing with no live process; this rewrites it immediately instead of waiting for the stuck-run reaper. Removal is not stopped.

实现：[Stop](../../internal/handler/sandbox_skill.go#L425)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Stopped skill | object |
| 400 | Skill is not installing | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Skill not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="api-085"></a>

### GET `/api/v1/sandbox-configs/{id}/skills/{skillId}/transcript`

Follow an install's agent transcript

Server-sent replay of everything the installer agent did — the
prompt it was given, its thinking, the commands it ran and
their output — followed live while the install is still
running. Frames are the same shape the chat stream uses, so a
console renders an install with the components it renders a
chat turn with. 404 once the event log has expired; the
durable message history is the fallback.

实现：[InstallTranscript](../../internal/handler/sandbox_skill.go#L736)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`text/event-stream`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Sandbox config ID |
| skillId | path | 是 | string | Skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | SSE stream of transcript events | string |
| 204 | Install is still preparing; retry once locators exist | string |
| 401 | Unauthorized | object |
| 404 | Skill or transcript not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-09) · [返回总目录](#index)

<a id="group-10"></a>

## Skills

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/skills`](#api-086) | 获取当前沙箱配置上可执行的 Skills |
| GET | [`/api/v1/skills/catalog`](#api-087) | List workspace skills |
| POST | [`/api/v1/skills/catalog`](#api-088) | Add a skill to the workspace catalog |
| DELETE | [`/api/v1/skills/catalog/{id}`](#api-089) | Delete a catalog skill |
| GET | [`/api/v1/skills/catalog/{id}/files`](#api-090) | List files of a catalog skill |
| GET | [`/api/v1/skills/catalog/{id}/files/content`](#api-091) | Read one file of a catalog skill |
| POST | [`/api/v1/skills/catalog/{id}/install`](#api-092) | Install a catalog skill onto sandboxes |

<a id="api-086"></a>

### GET `/api/v1/skills`

获取当前沙箱配置上可执行的 Skills

返回指定沙箱配置镜像内、智能体实际能调用的已安装技能（ready 且启用）。不传 sandbox_config_id 时列表为空。

实现：[ListSkills](../../internal/handler/skill_handler.go#L61)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| sandbox_config_id | query | 否 | string | Sandbox config ID; ignored for a shared agent |
| agent_id | query | 否 | string | Agent ID; needs agent_source_tenant_id |
| agent_source_tenant_id | query | 否 | integer | Shared agent source workspace |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Skills列表 | object |
| 403 | 无权使用该共享智能体 | [errors.AppError](#model-005) |

[返回模块目录](#group-10) · [返回总目录](#index)

<a id="api-087"></a>

### GET `/api/v1/skills/catalog`

List workspace skills

Returns every skill definition in this workspace and which sandbox configs it is installed on.

实现：[ListCatalog](../../internal/handler/skill_catalog.go#L17)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-10) · [返回总目录](#index)

<a id="api-088"></a>

### POST `/api/v1/skills/catalog`

Add a skill to the workspace catalog

Records a skill without installing it. Send a zip as multipart field "file", or JSON {"source":"..."}.

实现：[RegisterCatalog](../../internal/handler/skill_catalog.go#L39)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`, `multipart/form-data`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | object |

[返回模块目录](#group-10) · [返回总目录](#index)

<a id="api-089"></a>

### DELETE `/api/v1/skills/catalog/{id}`

Delete a catalog skill

Refused while any sandbox still has an installation of this skill.

实现：[DeleteCatalog](../../internal/handler/skill_catalog.go#L169)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Catalog skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-10) · [返回总目录](#index)

<a id="api-090"></a>

### GET `/api/v1/skills/catalog/{id}/files`

List files of a catalog skill

Lists the stored catalog bundle. Files belong to the skill definition, not a sandbox install.

实现：[ListCatalogFiles](../../internal/handler/skill_catalog.go#L190)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Catalog skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-10) · [返回总目录](#index)

<a id="api-091"></a>

### GET `/api/v1/skills/catalog/{id}/files/content`

Read one file of a catalog skill

实现：[GetCatalogFile](../../internal/handler/skill_catalog.go#L213)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Catalog skill ID |
| path | query | 是 | string | Skill-root-relative file path |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-10) · [返回总目录](#index)

<a id="api-092"></a>

### POST `/api/v1/skills/catalog/{id}/install`

Install a catalog skill onto sandboxes

Runs the existing snapshot install onto each named sandbox config.

实现：[InstallCatalog](../../internal/handler/skill_catalog.go#L125)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Catalog skill ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 202 | Accepted | object |

[返回模块目录](#group-10) · [返回总目录](#index)

<a id="group-11"></a>

## StorageBackend

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/storage-backends`](#api-093) | List storage backends |
| POST | [`/api/v1/storage-backends`](#api-094) | Create storage backend |
| POST | [`/api/v1/storage-backends/test`](#api-095) | Test storage backend with raw config |
| GET | [`/api/v1/storage-backends/types`](#api-096) | List allowed storage provider types |
| DELETE | [`/api/v1/storage-backends/{id}`](#api-097) | Delete storage backend |
| GET | [`/api/v1/storage-backends/{id}`](#api-098) | Get storage backend |
| PUT | [`/api/v1/storage-backends/{id}`](#api-099) | Update storage backend |
| PUT | [`/api/v1/storage-backends/{id}/default`](#api-100) | Set default storage backend |
| POST | [`/api/v1/storage-backends/{id}/test`](#api-101) | Test storage backend by ID |

<a id="api-093"></a>

### GET `/api/v1/storage-backends`

List storage backends

List all storage backend instances for the current workspace, with credentials masked. The workspace default backend id is returned alongside the list.

实现：[List](../../internal/handler/storagebackend.go#L44)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | List of storage backends and default_storage_backend_id | object |
| 401 | Unauthorized | object |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="api-094"></a>

### POST `/api/v1/storage-backends`

Create storage backend

Register a new object/file storage instance for the current workspace. The configuration is validated and a connectivity test is run before the backend is persisted.

实现：[Create](../../internal/handler/storagebackend.go#L98)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.storageBackendRequest](#model-091) | Storage backend configuration |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created storage backend | object |
| 400 | Invalid request, validation, or connectivity test failure | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 409 | A storage backend with this name already exists | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="api-095"></a>

### POST `/api/v1/storage-backends/test`

Test storage backend with raw config

Test connectivity for the provided storage configuration without persisting it. Returns success=false with a sanitized error message on failure (the HTTP status stays 200).

实现：[TestRaw](../../internal/handler/storagebackend.go#L202)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.storageBackendRequest](#model-091) | Storage backend configuration to test |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Connectivity test result (success, error) | object |
| 400 | Invalid request or validation error | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="api-096"></a>

### GET `/api/v1/storage-backends/types`

List allowed storage provider types

Return the storage provider types allowed by STORAGE_ALLOW_LIST for UI form generation (e.g. local, minio, cos, tos, s3, oss, ks3, obs).

实现：[Types](../../internal/handler/storagebackend.go#L262)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | List of allowed storage provider types | object |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="api-097"></a>

### DELETE `/api/v1/storage-backends/{id}`

Delete storage backend

Soft-delete a storage backend. A backend that is the workspace default, still bound to knowledge bases, environment-sourced, or a legacy alias cannot be deleted.

实现：[Delete](../../internal/handler/storagebackend.go#L160)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Storage backend ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Deletion success | object |
| 400 | Backend is default, bound, read-only, or legacy alias | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Storage backend not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="api-098"></a>

### GET `/api/v1/storage-backends/{id}`

Get storage backend

Retrieve a single storage backend by ID for the current workspace. Credentials are masked.

实现：[Get](../../internal/handler/storagebackend.go#L73)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Storage backend ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Storage backend details | object |
| 401 | Unauthorized | object |
| 404 | Storage backend not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="api-099"></a>

### PUT `/api/v1/storage-backends/{id}`

Update storage backend

Update a storage backend's mutable fields (name, credentials, status). Provider and physical location (endpoint, region, bucket, path prefix) are immutable; use storage migration to move data. Environment-sourced backends are read-only. Redacted secret placeholders preserve the stored credentials.

实现：[Update](../../internal/handler/storagebackend.go#L126)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Storage backend ID |
| request | body | 是 | [internal_handler.storageBackendRequest](#model-091) | Updated storage backend fields |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Updated storage backend | object |
| 400 | Immutable field change, read-only backend, validation, or connectivity failure | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Storage backend not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="api-100"></a>

### PUT `/api/v1/storage-backends/{id}/default`

Set default storage backend

Mark a storage backend as the workspace default. Only an active backend can become the default. New knowledge bases without an explicit binding use the default.

实现：[SetDefault](../../internal/handler/storagebackend.go#L181)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Storage backend ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Default set successfully | object |
| 400 | Backend is not active | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 401 | Unauthorized | object |
| 404 | Storage backend not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="api-101"></a>

### POST `/api/v1/storage-backends/{id}/test`

Test storage backend by ID

Test connectivity of an existing saved storage backend using its stored credentials. Returns success=false with a sanitized error message on failure (the HTTP status stays 200).

实现：[TestByID](../../internal/handler/storagebackend.go#L233)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Storage backend ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Connectivity test result (success, error) | object |
| 401 | Unauthorized | object |
| 404 | Storage backend not found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-11) · [返回总目录](#index)

<a id="group-12"></a>

## System Admin

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/system/admin/api-keys`](#api-102) | List platform API keys |
| POST | [`/api/v1/system/admin/api-keys`](#api-103) | Create a platform API key |
| DELETE | [`/api/v1/system/admin/api-keys/{key_id}`](#api-104) | Revoke a platform API key |
| GET | [`/api/v1/system/admin/list`](#api-105) | List all system administrators |
| POST | [`/api/v1/system/admin/promote`](#api-106) | Promote a user to system administrator |
| POST | [`/api/v1/system/admin/revoke`](#api-107) | Revoke system administrator privileges from a user |
| DELETE | [`/api/v1/system/admin/runtime/queues/{queue}/archived`](#api-108) | Purge all archived tasks in a queue |
| GET | [`/api/v1/system/admin/runtime/queues/{queue}/tasks`](#api-109) | List runtime queue tasks by state |
| POST | [`/api/v1/system/admin/runtime/queues/{queue}/tasks/{task_id}/actions/{action}`](#api-110) | Run a safe runtime task action |
| DELETE | [`/api/v1/system/admin/settings/{key}`](#api-111) | Reset a system setting to ENV / built-in default |
| GET | [`/api/v1/system/admin/settings/{key}`](#api-112) | Get a single system setting by key |
| PUT | [`/api/v1/system/admin/settings/{key}`](#api-113) | Update a system setting value |
| POST | [`/api/v1/system/admin/tenants/apply-default-storage-quota`](#api-114) | Apply the default storage quota to every existing workspace |
| POST | [`/api/v1/system/admin/users/create`](#api-115) | Create a new user (SystemAdmin) |
| POST | [`/api/v1/system/admin/users/reset-password`](#api-116) | Reset another user's password |

<a id="api-102"></a>

### GET `/api/v1/system/admin/api-keys`

List platform API keys

Lists non-workspace API keys. Full tokens are always masked. Human SystemAdmin only.

实现：[ListPlatformAPIKeys](../../internal/handler/system.go#L110)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-103"></a>

### POST `/api/v1/system/admin/api-keys`

Create a platform API key

Creates a capability-scoped key that may target any workspace through X-Tenant-ID. The plaintext token is returned once. Human SystemAdmin only.

实现：[CreatePlatformAPIKey](../../internal/handler/system.go#L133)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.platformAPIKeyCreateRequest](#model-085) | Platform API key |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-104"></a>

### DELETE `/api/v1/system/admin/api-keys/{key_id}`

Revoke a platform API key

Immediately revokes a platform API key. Human SystemAdmin only.

实现：[DeletePlatformAPIKey](../../internal/handler/system.go#L186)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| key_id | path | 是 | integer | API key ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-105"></a>

### GET `/api/v1/system/admin/list`

List all system administrators

Retrieve a paginated list of users with system administrator
privileges (SystemAdmin only). Supports `offset` (default 0)
and `limit` (default 50, max 200) query parameters. Walks the
partial-friendly idx_users_is_system_admin index.

实现：[ListSystemAdmins](../../internal/handler/system.go#L1470)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| offset | query | 否 | integer | Page offset；默认值：0 |
| limit | query | 否 | integer | Page size (max 200)；默认值：50 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | System admins retrieved successfully | [internal_handler.ListSystemAdminsResponse](#model-034) |
| 403 | Forbidden: not a system admin | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-106"></a>

### POST `/api/v1/system/admin/promote`

Promote a user to system administrator

Grant system administrator privileges to a user (SystemAdmin only).
Idempotent: re-promoting an existing system admin returns 200 with no DB write.
Identify the user by email (preferred for human operators) or user_id (UUID, for API clients).

实现：[PromoteUserToSystemAdmin](../../internal/handler/system.go#L1297)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.PromoteUserToSystemAdminRequest](#model-044) | User promotion request |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | User promoted successfully | [types.UserInfo](#model-237) |
| 400 | Bad request | object |
| 403 | Forbidden: not a system admin | object |
| 404 | User not found | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-107"></a>

### POST `/api/v1/system/admin/revoke`

Revoke system administrator privileges from a user

Remove system administrator privileges from a user (SystemAdmin only).
Two safety guards: the caller cannot revoke their own privileges,
and revoking the last remaining system admin is rejected — both
prevent a SystemAdmin from accidentally locking the platform out
of system-level administration. Idempotent on already-non-admin users.

实现：[RevokeSystemAdmin](../../internal/handler/system.go#L1385)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.RevokeSystemAdminRequest](#model-048) | User revocation request |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Privileges revoked successfully | [types.UserInfo](#model-237) |
| 400 | Bad request / would remove last admin / self-revoke | object |
| 403 | Forbidden: not a system admin | object |
| 404 | User not found | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-108"></a>

### DELETE `/api/v1/system/admin/runtime/queues/{queue}/archived`

Purge all archived tasks in a queue

实现：[PurgeArchivedRuntimeTasks](../../internal/handler/system.go#L2124)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| queue | path | 是 | string | Queue name |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-109"></a>

### GET `/api/v1/system/admin/runtime/queues/{queue}/tasks`

List runtime queue tasks by state

实现：[ListRuntimeTasks](../../internal/handler/system.go#L1931)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| queue | path | 是 | string | Queue name |
| state | query | 是 | string | Task state；可选值：["pending", "active", "scheduled", "retry", "archived", "completed"] |
| cursor | query | 否 | string | Opaque continuation cursor |
| page_size | query | 否 | integer | Page size；默认值：20 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.RuntimeTasksResponse](#model-050) |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-110"></a>

### POST `/api/v1/system/admin/runtime/queues/{queue}/tasks/{task_id}/actions/{action}`

Run a safe runtime task action

实现：[MutateRuntimeTask](../../internal/handler/system.go#L2110)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| queue | path | 是 | string | Queue name |
| task_id | path | 是 | string | Task ID |
| action | path | 是 | string | Action；可选值：["cancel", "run_now", "delete"] |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | map&lt;string, boolean&gt; |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-111"></a>

### DELETE `/api/v1/system/admin/settings/{key}`

Reset a system setting to ENV / built-in default

Removes the DB override for :key so the 3-tier resolver
falls back to the environment variable (when configured)
or the in-code default. Idempotent — resetting a key
that was never persisted returns 200.

实现：[ResetSystemSetting](../../internal/handler/system.go#L2372)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| key | path | 是 | string | Setting key |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Reset acknowledged | object |
| 400 | Unknown key | object |
| 500 | DB write failed | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-112"></a>

### GET `/api/v1/system/admin/settings/{key}`

Get a single system setting by key

Returns the row matching :key. 404 when the key is unknown
to the registry; 200 with the row when known.

实现：[GetSystemSetting](../../internal/handler/system.go#L2226)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| key | path | 是 | string | Setting key (e.g. file.max_size_mb) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | the setting row | [types.SystemSetting](#model-223) |
| 400 | Unknown key | object |
| 404 | Key registered but DB row absent | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-113"></a>

### PUT `/api/v1/system/admin/settings/{key}`

Update a system setting value

Persist a new value for :key. Service validates the
rawValue against the registry's declared value_type and
rejects mismatches with 400. SystemAdmin only. Emits an
audit row (action=system.setting_changed) on success.

实现：[UpdateSystemSetting](../../internal/handler/system.go#L2266)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| key | path | 是 | string | Setting key |
| request | body | 是 | [internal_handler.UpdateSystemSettingRequest](#model-071) | New value |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | the updated row | [types.SystemSetting](#model-223) |
| 400 | Bad request / unknown key / type mismatch | object |
| 403 | Forbidden: not a system admin | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-114"></a>

### POST `/api/v1/system/admin/tenants/apply-default-storage-quota`

Apply the default storage quota to every existing workspace

Reads the current value of `tenant.default_storage_quota_gb`
(3-tier resolver: DB > ENV > default) and writes that many
GiB into storage_quota for every row in tenants. Bypasses
the per-workspace PUT whitelist, which forbids storage_quota
edits by Owners. SystemAdmin only.
Idempotent — running twice with the same setting is a no-op.

实现：[ApplyDefaultStorageQuotaToAllTenants](../../internal/handler/system.go#L2307)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | { affected: int64, quota_bytes: int64 } | object |
| 500 | DB write failed | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-115"></a>

### POST `/api/v1/system/admin/users/create`

Create a new user (SystemAdmin)

Provision a new local user account (SystemAdmin only).
When `password` is omitted or null, a cryptographically random
password is generated (OIDC-style crypto/rand + base64url)
and returned once in the response body. Any provided value,
including empty string, is policy-checked. Tenant provisioning
follows the shared auth.default_tenant_mode policy.

实现：[CreateSystemUser](../../internal/handler/system.go#L1604)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.AdminCreateUserRequest](#model-108) | User creation request |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Identity already exists, returns the existing user | [internal_handler.CreateSystemUserResponse](#model-025) |
| 201 | User created successfully | [internal_handler.CreateSystemUserResponse](#model-025) |
| 400 | Invalid request or weak password | object |
| 403 | Forbidden: not a system admin | object |
| 409 | Email and username refer to conflicting identities | object |
| 500 | Internal error | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="api-116"></a>

### POST `/api/v1/system/admin/users/reset-password`

Reset another user's password

Replace another user's local password and revoke all of their existing sessions (SystemAdmin only).
A system administrator cannot reset their own password through this endpoint; self-service password change still requires the old password.

实现：[ResetUserPassword](../../internal/handler/system.go#L1535)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.ResetUserPasswordRequest](#model-047) | Password reset request |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Password reset successfully | object |
| 400 | Invalid request, weak password, or self reset | object |
| 403 | Forbidden: not a system admin | object |
| 404 | User not found | object |

[返回模块目录](#group-12) · [返回总目录](#index)

<a id="group-13"></a>

## User

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/user/favorites`](#api-117) | List my favorites |
| POST | [`/api/v1/user/favorites`](#api-118) | Star a resource |
| DELETE | [`/api/v1/user/favorites/{type}/{id}`](#api-119) | Unstar a resource |

<a id="api-117"></a>

### GET `/api/v1/user/favorites`

List my favorites

Lists this user's starred resources in the current workspace for a given type

实现：[ListFavorites](../../internal/handler/user_resource_favorite.go#L53)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| type | query | 是 | string | Resource type (kb &#124; agent) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-13) · [返回总目录](#index)

<a id="api-118"></a>

### POST `/api/v1/user/favorites`

Star a resource

实现：[AddFavorite](../../internal/handler/user_resource_favorite.go#L89)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| body | body | 是 | [internal_handler.AddFavoriteRequest](#model-017) | Type + id |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-13) · [返回总目录](#index)

<a id="api-119"></a>

### DELETE `/api/v1/user/favorites/{type}/{id}`

Unstar a resource

实现：[RemoveFavorite](../../internal/handler/user_resource_favorite.go#L118)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| type | path | 是 | string | Resource type |
| id | path | 是 | string | Resource id |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-13) · [返回总目录](#index)

<a id="group-14"></a>

## VectorStore

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/vector-stores`](#api-120) | List vector stores |
| POST | [`/api/v1/vector-stores`](#api-121) | Create vector store |
| POST | [`/api/v1/vector-stores/test`](#api-122) | Test vector store connection with raw credentials |
| GET | [`/api/v1/vector-stores/types`](#api-123) | List vector store types |
| DELETE | [`/api/v1/vector-stores/{id}`](#api-124) | Delete vector store |
| GET | [`/api/v1/vector-stores/{id}`](#api-125) | Get vector store |
| PUT | [`/api/v1/vector-stores/{id}`](#api-126) | Update vector store |
| POST | [`/api/v1/vector-stores/{id}/test`](#api-127) | Test vector store connection by ID |

<a id="api-120"></a>

### GET `/api/v1/vector-stores`

List vector stores

List all vector stores for the current workspace, including environment-configured and user-created stores

实现：[ListStores](../../internal/handler/vectorstore.go#L130)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | List of vector stores (env + DB) | object |
| 401 | Unauthorized | object |

[返回模块目录](#group-14) · [返回总目录](#index)

<a id="api-121"></a>

### POST `/api/v1/vector-stores`

Create vector store

Create a new vector store configuration for the current workspace

实现：[CreateStore](../../internal/handler/vectorstore.go#L80)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.CreateStoreRequest](#model-024) | Vector store configuration |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created vector store | object |
| 400 | Invalid request or validation error | [errors.AppError](#model-005) |
| 401 | Unauthorized | object |
| 409 | Duplicate endpoint and index | [errors.AppError](#model-005) |

[返回模块目录](#group-14) · [返回总目录](#index)

<a id="api-122"></a>

### POST `/api/v1/vector-stores/test`

Test vector store connection with raw credentials

Test connectivity using provided credentials without persisting. Returns detected server version.

实现：[TestStoreRaw](../../internal/handler/vectorstore.go#L419)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.TestStoreRequest](#model-062) | Engine type and connection config |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Connection test result (success, version) | object |
| 400 | Invalid request | [errors.AppError](#model-005) |
| 401 | Unauthorized | object |

[返回模块目录](#group-14) · [返回总目录](#index)

<a id="api-123"></a>

### GET `/api/v1/vector-stores/types`

List vector store types

Return supported engine types with connection and index field schemas for UI form generation

实现：[ListStoreTypes](../../internal/handler/vectorstore.go#L341)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | List of engine types with config schemas | object |

[返回模块目录](#group-14) · [返回总目录](#index)

<a id="api-124"></a>

### DELETE `/api/v1/vector-stores/{id}`

Delete vector store

Soft-delete a vector store. Env stores cannot be deleted.

实现：[DeleteStore](../../internal/handler/vectorstore.go#L296)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Vector store ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Deletion success | object |
| 400 | Env store cannot be deleted | object |
| 401 | Unauthorized | object |
| 404 | Vector store not found | object |

[返回模块目录](#group-14) · [返回总目录](#index)

<a id="api-125"></a>

### GET `/api/v1/vector-stores/{id}`

Get vector store

Retrieve a single vector store by ID. Supports both DB stores and env stores (__env_* IDs)

实现：[GetStore](../../internal/handler/vectorstore.go#L175)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Vector store ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Vector store details | object |
| 401 | Unauthorized | object |
| 404 | Vector store not found | object |

[返回模块目录](#group-14) · [返回总目录](#index)

<a id="api-126"></a>

### PUT `/api/v1/vector-stores/{id}`

Update vector store

Update a vector store (name only). Engine type, connection config, and index config are immutable. Env stores cannot be modified.

实现：[UpdateStore](../../internal/handler/vectorstore.go#L225)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Vector store ID |
| request | body | 是 | [internal_handler.UpdateStoreRequest](#model-070) | Updated fields |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Updated vector store | object |
| 400 | Env store or validation error | object |
| 401 | Unauthorized | object |
| 404 | Vector store not found | object |

[返回模块目录](#group-14) · [返回总目录](#index)

<a id="api-127"></a>

### POST `/api/v1/vector-stores/{id}/test`

Test vector store connection by ID

Test connectivity of an existing saved or env store. Returns detected server version. For DB stores, the version is automatically saved to connection_config.

实现：[TestStoreByID](../../internal/handler/vectorstore.go#L355)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Vector store ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Connection test result (success, version) | object |
| 401 | Unauthorized | object |
| 404 | Vector store not found | object |

[返回模块目录](#group-14) · [返回总目录](#index)

<a id="group-15"></a>

## WeKnoraCloud

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/models/weknoracloud/status`](#api-128) | 检查 WeKnoraCloud 凭证状态 |
| POST | [`/api/v1/weknoracloud/credentials`](#api-129) | 保存 WeKnoraCloud 凭证 |

<a id="api-128"></a>

### GET `/api/v1/models/weknoracloud/status`

检查 WeKnoraCloud 凭证状态

检查当前空间的 WeKnoraCloud 凭证是否完好；needs_reinit=true 表示需要重新保存

实现：[Status](../../internal/handler/weknoracloud.go#L55)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 凭证状态 | object |
| 500 | 服务器错误 | object |

[返回模块目录](#group-15) · [返回总目录](#index)

<a id="api-129"></a>

### POST `/api/v1/weknoracloud/credentials`

保存 WeKnoraCloud 凭证

保存 APPID/APPSECRET 到当前空间配置（不自动创建模型）

实现：[SaveCredentials](../../internal/handler/weknoracloud.go#L25)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | {app_id, app_secret} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | success: true | object |
| 400 | 请求参数错误 | object |

[返回模块目录](#group-15) · [返回总目录](#index)

<a id="group-16"></a>

## Wiki

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/knowledgebase/{kb_id}/wiki/auto-fix`](#api-130) | Auto-fix wiki issues |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/folders`](#api-131) | List wiki folders |
| POST | [`/api/v1/knowledgebase/{kb_id}/wiki/folders`](#api-132) | Create a wiki folder |
| DELETE | [`/api/v1/knowledgebase/{kb_id}/wiki/folders/{folder_id}`](#api-133) | Delete an empty wiki folder |
| PUT | [`/api/v1/knowledgebase/{kb_id}/wiki/folders/{folder_id}`](#api-134) | Rename or move a wiki folder |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/graph`](#api-135) | Get wiki link graph |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/index`](#api-136) | Get wiki index view |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/issues`](#api-137) | List wiki page issues |
| PUT | [`/api/v1/knowledgebase/{kb_id}/wiki/issues/{issue_id}/status`](#api-138) | Update wiki page issue status |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/lint`](#api-139) | Run wiki lint |
| PUT | [`/api/v1/knowledgebase/{kb_id}/wiki/move-page`](#api-140) | Move a wiki page into a folder |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/pages`](#api-141) | List wiki pages |
| POST | [`/api/v1/knowledgebase/{kb_id}/wiki/pages`](#api-142) | Create a wiki page |
| DELETE | [`/api/v1/knowledgebase/{kb_id}/wiki/pages/{slug}`](#api-143) | Delete a wiki page |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/pages/{slug}`](#api-144) | Get a wiki page by slug |
| PUT | [`/api/v1/knowledgebase/{kb_id}/wiki/pages/{slug}`](#api-145) | Update a wiki page |
| POST | [`/api/v1/knowledgebase/{kb_id}/wiki/rebuild-links`](#api-146) | Rebuild wiki links |
| POST | [`/api/v1/knowledgebase/{kb_id}/wiki/revert`](#api-147) | Revert a wiki page to an earlier revision |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/revisions/{slug}`](#api-148) | List wiki page revisions |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/search`](#api-149) | Search wiki pages |
| GET | [`/api/v1/knowledgebase/{kb_id}/wiki/stats`](#api-150) | Get wiki statistics |
| POST | [`/api/v1/wiki-search`](#api-151) | Cross-KB wiki search |

<a id="api-130"></a>

### POST `/api/v1/knowledgebase/{kb_id}/wiki/auto-fix`

Auto-fix wiki issues

Automatically fix fixable wiki issues (broken links, etc.)

实现：[AutoFix](../../internal/handler/wiki_page.go#L1183)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-131"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/folders`

List wiki folders

Retrieve the direct child folders of a parent folder (parent_id empty = root level), each with its page count and a has-children flag for the directory tree.

实现：[ListFolders](../../internal/handler/wiki_page.go#L143)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| parent_id | query | 否 | string | Parent folder id (empty = root) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiFolderListResponse](#model-248) |
| 400 | Bad Request | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-132"></a>

### POST `/api/v1/knowledgebase/{kb_id}/wiki/folders`

Create a wiki folder

Create a new (initially empty) directory node under parent_id

实现：[CreateFolder](../../internal/handler/wiki_page.go#L180)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| folder | body | 是 | [types.WikiFolderCreateRequest](#model-247) | Folder data |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | [types.WikiFolder](#model-246) |
| 400 | Bad Request | [errors.AppError](#model-005) |
| 409 | Conflict | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-133"></a>

### DELETE `/api/v1/knowledgebase/{kb_id}/wiki/folders/{folder_id}`

Delete an empty wiki folder

Delete a folder that has no pages and no child folders

实现：[DeleteFolder](../../internal/handler/wiki_page.go#L252)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| folder_id | path | 是 | string | Folder ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 204 | No Content | 未声明 |
| 400 | Bad Request | [errors.AppError](#model-005) |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-134"></a>

### PUT `/api/v1/knowledgebase/{kb_id}/wiki/folders/{folder_id}`

Rename or move a wiki folder

Rename and/or reparent a folder; the whole subtree's paths and the affected pages' cached paths are recomputed

实现：[UpdateFolder](../../internal/handler/wiki_page.go#L212)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| folder_id | path | 是 | string | Folder ID |
| folder | body | 是 | [types.WikiFolderUpdateRequest](#model-250) | Folder update |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiFolder](#model-246) |
| 400 | Bad Request | [errors.AppError](#model-005) |
| 404 | Not Found | [errors.AppError](#model-005) |
| 409 | Conflict | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-135"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/graph`

Get wiki link graph

Returns a slice of the wiki link graph for visualization. Supports
`mode=overview` (top-N most-connected pages, default) and
`mode=ego` (BFS neighborhood of a center slug) to keep response
size tractable for knowledge bases with tens of thousands of pages.

实现：[GetGraph](../../internal/handler/wiki_page.go#L785)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| mode | query | 否 | string | overview (default) &#124; ego |
| center | query | 否 | string | Center slug for ego mode |
| depth | query | 否 | integer | Ego BFS depth (1-3, default 1) |
| types | query | 否 | string | Comma-separated page_type allow-list |
| limit | query | 否 | integer | Max nodes to return (default 500, max 2000) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiGraphData](#model-251) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-136"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/index`

Get wiki index view

Returns the wiki index as intro text plus per-type paginated
directory groups. The heavy directory markdown that used to
live in wiki_pages.content was replaced with this structured
response so a KB with tens of thousands of pages no longer
materializes megabytes of TEXT on every index open.

实现：[GetIndex](../../internal/handler/wiki_page.go#L723)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| types | query | 否 | string | Comma-separated page types (default: all content types) |
| limit | query | 否 | integer | Per-group window size, 1-200 (default 50) |
| cursor | query | 否 | string | Opaque offset cursor from previous response |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiIndexResponse](#model-257) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-137"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/issues`

List wiki page issues

List issues flagged on wiki pages with optional filtering

实现：[ListIssues](../../internal/handler/wiki_page.go#L906)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| slug | query | 否 | string | Filter by page slug |
| status | query | 否 | string | Filter by status (pending, ignored, resolved) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | array&lt;[types.WikiPageIssue](#model-259)&gt; |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-138"></a>

### PUT `/api/v1/knowledgebase/{kb_id}/wiki/issues/{issue_id}/status`

Update wiki page issue status

Update the status of a flagged wiki page issue

实现：[UpdateIssueStatus](../../internal/handler/wiki_page.go#L936)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| issue_id | path | 是 | string | Issue ID |
| status | body | 是 | object | New status {'status': 'ignored'} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | map&lt;string, string&gt; |
| 400 | Bad Request | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-139"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/lint`

Run wiki lint

Perform a comprehensive health check on the wiki

实现：[Lint](../../internal/handler/wiki_page.go#L1158)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [github_com_Tencent_WeKnora_internal_application_service.WikiLintReport](#model-009) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-140"></a>

### PUT `/api/v1/knowledgebase/{kb_id}/wiki/move-page`

Move a wiki page into a folder

Relocate a page (identified by slug in the body) into a folder (folder_id empty = root); the page's cached category path is recomputed

实现：[MovePage](../../internal/handler/wiki_page.go#L281)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| move | body | 是 | [types.WikiPageMoveRequest](#model-261) | Move target |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiPage](#model-258) |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-141"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/pages`

List wiki pages

List wiki pages with optional filtering and pagination

实现：[ListPages](../../internal/handler/wiki_page.go#L79)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| page_type | query | 否 | string | Filter by page type; comma-separated for multiple (e.g. entity,concept) |
| status | query | 否 | string | Filter by status |
| query | query | 否 | string | Full-text search |
| page | query | 否 | integer | Page number |
| page_size | query | 否 | integer | Page size |
| sort_by | query | 否 | string | Sort field |
| sort_order | query | 否 | string | Sort order (asc/desc) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiPageListResponse](#model-260) |
| 400 | Bad Request | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-142"></a>

### POST `/api/v1/knowledgebase/{kb_id}/wiki/pages`

Create a wiki page

Create a new wiki page in the knowledge base

实现：[CreatePage](../../internal/handler/wiki_page.go#L344)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| page | body | 是 | [types.WikiPage](#model-258) | Wiki page data |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | [types.WikiPage](#model-258) |
| 400 | Bad Request | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-143"></a>

### DELETE `/api/v1/knowledgebase/{kb_id}/wiki/pages/{slug}`

Delete a wiki page

Soft-delete a wiki page by slug

实现：[DeletePage](../../internal/handler/wiki_page.go#L683)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| slug | path | 是 | string | Page slug |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 204 | No Content | 未声明 |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-144"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/pages/{slug}`

Get a wiki page by slug

Retrieve a wiki page by its slug

实现：[GetPage](../../internal/handler/wiki_page.go#L406)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| slug | path | 是 | string | Page slug |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiPage](#model-258) |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-145"></a>

### PUT `/api/v1/knowledgebase/{kb_id}/wiki/pages/{slug}`

Update a wiki page

Partially update a wiki page by slug. Absent fields keep
their stored value. When `version` is > 0 it acts as an
optimistic-lock guard: a mismatch with the stored version
returns 409 together with the current version so the client
can reload and re-apply.

实现：[UpdatePage](../../internal/handler/wiki_page.go#L443)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| slug | path | 是 | string | Page slug |
| page | body | 是 | [types.WikiPageUpdateRequest](#model-265) | Fields to update |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiPage](#model-258) |
| 400 | Bad Request | [errors.AppError](#model-005) |
| 404 | Not Found | [errors.AppError](#model-005) |
| 409 | Conflict | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-146"></a>

### POST `/api/v1/knowledgebase/{kb_id}/wiki/rebuild-links`

Rebuild wiki links

Re-parse all pages and rebuild bidirectional link references

实现：[RebuildLinks](../../internal/handler/wiki_page.go#L1135)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | map&lt;string, string&gt; |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-147"></a>

### POST `/api/v1/knowledgebase/{kb_id}/wiki/revert`

Revert a wiki page to an earlier revision

Rolls the page (slug in the body, like move-page) back to
the content of the given stored revision. Applied as a
regular edit: the pre-revert state is snapshotted and the
version advances.

实现：[RevertPage](../../internal/handler/wiki_page.go#L624)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| revert | body | 是 | [types.WikiPageRevertRequest](#model-262) | Revert target |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiPage](#model-258) |
| 400 | Bad Request | [errors.AppError](#model-005) |
| 404 | Not Found | [errors.AppError](#model-005) |
| 409 | Conflict | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-148"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/revisions/{slug}`

List wiki page revisions

Returns the stored historical snapshots for a page, newest
first (content omitted), plus the current version. Passing
`version` returns that single snapshot with full content.

实现：[ListRevisions](../../internal/handler/wiki_page.go#L550)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| slug | path | 是 | string | Page slug |
| version | query | 否 | integer | Return this single revision with content |
| limit | query | 否 | integer | Page size (default 50, max 200) |
| offset | query | 否 | integer | Offset into the newest-first list |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiPageRevisionListResponse](#model-264) |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-149"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/search`

Search wiki pages

Full-text search over wiki pages

实现：[SearchPages](../../internal/handler/wiki_page.go#L988)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |
| q | query | 是 | string | Search query |
| limit | query | 否 | integer | Max results (default 10) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | array&lt;[types.WikiPage](#model-258)&gt; |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-150"></a>

### GET `/api/v1/knowledgebase/{kb_id}/wiki/stats`

Get wiki statistics

Returns aggregate statistics about the wiki

实现：[GetStats](../../internal/handler/wiki_page.go#L881)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kb_id | path | 是 | string | Knowledge base ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.WikiStats](#model-266) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="api-151"></a>

### POST `/api/v1/wiki-search`

Cross-KB wiki search

Cross-KB wiki search using the same POSIX regex ranking as single-KB wiki search

实现：[SearchPagesAcross](../../internal/handler/wiki_page.go#L1044)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.SearchWikiRequest](#model-056) | Wiki search request |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 400 | Bad Request | [errors.AppError](#model-005) |

[返回模块目录](#group-16) · [返回总目录](#index)

<a id="group-17"></a>

## knowledge

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/knowledge-bases/{id}/images`](#api-152) | 列出知识库图片资产 |

<a id="api-152"></a>

### GET `/api/v1/knowledge-bases/{id}/images`

列出知识库图片资产

浏览某知识库内的全部图片资产，支持关键字搜索（caption/OCR 文本）、按图片属性筛选（attr_filters / attr_rules）与排序分页。

实现：[ListImages](../../internal/handler/knowledge.go#L1131)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库 ID |
| page | query | 否 | integer | 页码，默认 1 |
| page_size | query | 否 | integer | 每页数量，默认 20，最大 1000 |
| keyword | query | 否 | string | 关键字，对 search_in 指定字段做不区分大小写的子串匹配 |
| search_in | query | 否 | string | 参与搜索的属性 ID（逗号分隔）；须为 in_searchfield=true |
| sort_by | query | 否 | string | 排序属性 ID；须为 in_sortfield=true，默认 created_at |
| sort_order | query | 否 | string | 排序方向：asc / desc，默认 desc |
| is_enabled | query | 否 | boolean | 仅包含启用状态的图片 |
| attr_filters | query | 否 | string | 属性筛选 JSON，同一属性多值 OR、属性间 AND |
| attr_rules | query | 否 | string | 属性逐值裁决 JSON（off 隐藏 / on 强制显示，on 优先） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 图片资产分页列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-17) · [返回总目录](#index)

<a id="group-18"></a>

## 会话

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| DELETE | [`/api/v1/artifacts`](#api-153) | 删除产物库中的文件 |
| GET | [`/api/v1/artifacts`](#api-154) | 列出我的产物 |
| GET | [`/api/v1/sessions`](#api-155) | 获取会话列表 |
| POST | [`/api/v1/sessions`](#api-156) | 创建会话 |
| DELETE | [`/api/v1/sessions/batch`](#api-157) | 批量删除会话 |
| DELETE | [`/api/v1/sessions/{id}`](#api-158) | 删除会话 |
| GET | [`/api/v1/sessions/{id}`](#api-159) | 获取会话详情 |
| PUT | [`/api/v1/sessions/{id}`](#api-160) | 更新会话 |
| DELETE | [`/api/v1/sessions/{id}/messages`](#api-161) | 清空会话消息 |
| DELETE | [`/api/v1/sessions/{id}/pin`](#api-162) | 取消置顶会话 |
| GET | [`/api/v1/sessions/{session_id}/artifacts`](#api-163) | 列出会话生成的产物文件 |
| POST | [`/api/v1/sessions/{session_id}/fork`](#api-164) | 分叉会话 |
| POST | [`/api/v1/sessions/{session_id}/generate_title`](#api-165) | 生成会话标题 |
| DELETE | [`/api/v1/sessions/{session_id}/messages/{message_id}/artifacts/{index}`](#api-166) | 删除会话中的产物文件 |
| GET | [`/api/v1/sessions/{session_id}/messages/{message_id}/suggestions`](#api-167) | 获取回答后推荐问题 |
| POST | [`/api/v1/sessions/{session_id}/messages/{message_id}/suggestions`](#api-168) | 确保生成回答后推荐问题 |
| POST | [`/api/v1/sessions/{session_id}/pin`](#api-169) | 置顶会话 |
| POST | [`/api/v1/sessions/{session_id}/rewind`](#api-170) | 回滚会话 |
| POST | [`/api/v1/sessions/{session_id}/suggestion-events`](#api-171) | 上报推荐问题事件 |

<a id="api-153"></a>

### DELETE `/api/v1/artifacts`

删除产物库中的文件

删除产物库中的一个文件。产物库一行代表一个文件的最新版本，默认连同该文件在本会话中的所有历史版本一并删除

实现：[DeleteLibraryArtifact](../../internal/handler/session/artifact_delete.go#L50)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | query | 是 | string | 会话ID |
| message_id | query | 是 | string | 消息ID |
| index | query | 是 | integer | 产物在消息中的位置 |
| all_versions | query | 否 | boolean | 是否删除所有版本，默认 true |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-154"></a>

### GET `/api/v1/artifacts`

列出我的产物

跨会话列出当前用户可见会话中技能生成的文件，每个文件只返回最新版本（不含存储 URL）

实现：[ListArtifactLibrary](../../internal/handler/session/artifact_library.go#L13)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| keyword | query | 否 | string | 按文件名过滤 |
| file_types | query | 否 | string | 逗号分隔的扩展名，如 .pdf,.pptx |
| page | query | 否 | integer | 页码 |
| page_size | query | 否 | integer | 每页数量（最大 100） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-155"></a>

### GET `/api/v1/sessions`

获取会话列表

获取当前空间的会话列表，支持分页、关键字搜索、按来源/Agent 筛选

实现：[GetSessionsByTenant](../../internal/handler/session/handler.go#L280)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| page | query | 否 | integer | 页码 |
| page_size | query | 否 | integer | 每页数量 |
| keyword | query | 否 | string | 标题模糊搜索 |
| source | query | 否 | string | 来源过滤：web / embed / api / feishu / wechat / slack / ...（api、embed、IM 渠道需 Admin+） |
| agent_id | query | 否 | string | 按 Agent 过滤（仅对 IM 会话生效） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 会话列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-156"></a>

### POST `/api/v1/sessions`

创建会话

创建新的对话会话

实现：[CreateSession](../../internal/handler/session/handler.go#L157)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler_session.CreateSessionRequest](#model-097) | 会话创建请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 创建的会话 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-157"></a>

### DELETE `/api/v1/sessions/batch`

批量删除会话

根据ID列表批量删除对话会话，或设置 delete_all=true 删除当前空间的所有会话

实现：[BatchDeleteSessions](../../internal/handler/session/handler.go#L496)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler_session.batchDeleteRequest](#model-106) | 批量删除请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-158"></a>

### DELETE `/api/v1/sessions/{id}`

删除会话

删除指定的会话

实现：[DeleteSession](../../internal/handler/session/handler.go#L403)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 会话ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 404 | 会话不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-159"></a>

### GET `/api/v1/sessions/{id}`

获取会话详情

根据ID获取会话详情

实现：[GetSession](../../internal/handler/session/handler.go#L233)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 会话ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 会话详情 | object |
| 404 | 会话不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-160"></a>

### PUT `/api/v1/sessions/{id}`

更新会话

更新会话属性

实现：[UpdateSession](../../internal/handler/session/handler.go#L332)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 会话ID |
| request | body | 是 | [types.Session](#model-210) | 会话信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的会话 | object |
| 404 | 会话不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-161"></a>

### DELETE `/api/v1/sessions/{id}/messages`

清空会话消息

删除会话中的所有消息，同时清除 LLM 上下文和聊天历史知识库条目。会话本身保留。

实现：[ClearSessionMessages](../../internal/handler/session/handler.go#L447)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 会话ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 清空成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 会话不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-162"></a>

### DELETE `/api/v1/sessions/{id}/pin`

取消置顶会话

取消指定会话的置顶

实现：[UnpinSession](../../internal/handler/session/handler.go#L584)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 会话ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 取消置顶成功 | object |
| 404 | 会话不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-163"></a>

### GET `/api/v1/sessions/{session_id}/artifacts`

列出会话生成的产物文件

返回本会话中所有 assistant 消息产生的技能产物元数据（不含 URL）

实现：[ListSessionArtifacts](../../internal/handler/session/artifact_download.go#L32)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-164"></a>

### POST `/api/v1/sessions/{session_id}/fork`

分叉会话

从指定的用户或助手消息处分叉出一个新会话。用户消息：复制其之前的历史并预填该问题；助手消息：复制含该回答在内的历史，从该轮沙箱状态继续。

实现：[ForkSession](../../internal/handler/session/fork.go#L37)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 源会话 ID |
| request | body | 是 | [internal_handler_session.ForkSessionRequest](#model-098) | 分叉请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 新会话 | object |
| 400 | 请求参数错误 / 分叉点角色不支持 | [errors.AppError](#model-005) |
| 404 | 会话或消息不存在 | [errors.AppError](#model-005) |
| 409 | 源会话正在生成中 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-165"></a>

### POST `/api/v1/sessions/{session_id}/generate_title`

生成会话标题

根据消息内容自动生成会话标题

实现：[GenerateTitle](../../internal/handler/session/title.go#L12)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |
| request | body | 是 | [internal_handler_session.GenerateTitleRequest](#model-099) | 生成请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 生成的标题 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-166"></a>

### DELETE `/api/v1/sessions/{session_id}/messages/{message_id}/artifacts/{index}`

删除会话中的产物文件

删除某条 assistant 消息生成的单个文件；all_versions=true 时连同本会话中该文件的所有历史版本一并删除

实现：[DeleteMessageArtifact](../../internal/handler/session/artifact_delete.go#L16)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |
| message_id | path | 是 | string | 消息ID |
| index | path | 是 | integer | 产物在消息中的位置 |
| all_versions | query | 否 | boolean | 是否删除该文件的所有版本 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-167"></a>

### GET `/api/v1/sessions/{session_id}/messages/{message_id}/suggestions`

获取回答后推荐问题

实现：[Get](../../internal/handler/message_suggestion.go#L73)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话 ID |
| message_id | path | 是 | string | 助手消息 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-168"></a>

### POST `/api/v1/sessions/{session_id}/messages/{message_id}/suggestions`

确保生成回答后推荐问题

对已完成的助手消息异步生成或重新生成推荐问题；相同配置快照会复用持久化结果

实现：[Ensure](../../internal/handler/message_suggestion.go#L34)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话 ID |
| message_id | path | 是 | string | 助手消息 ID |
| request | body | 否 | [internal_handler.EnsureMessageSuggestionsRequest](#model-027) | 生成选项 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 202 | Accepted | object |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-169"></a>

### POST `/api/v1/sessions/{session_id}/pin`

置顶会话

将指定会话置顶（用户维度）

实现：[PinSession](../../internal/handler/session/handler.go#L569)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 置顶成功 | object |
| 404 | 会话不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-170"></a>

### POST `/api/v1/sessions/{session_id}/rewind`

回滚会话

回滚当前会话到指定用户或助手消息：删除其后消息，可到达时 git reset 工作区。

实现：[RewindSession](../../internal/handler/session/rewind.go#L35)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话 ID |
| request | body | 是 | [internal_handler_session.RewindSessionRequest](#model-102) | 回滚请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 回滚结果 | object |
| 400 | 请求参数错误 / 回滚点角色不支持 | [errors.AppError](#model-005) |
| 404 | 会话或消息不存在 | [errors.AppError](#model-005) |
| 409 | 会话正在生成中 / 无检查点 / 沙箱已更换 | [errors.AppError](#model-005) |
| 500 | 工作区回滚失败 | [errors.AppError](#model-005) |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="api-171"></a>

### POST `/api/v1/sessions/{session_id}/suggestion-events`

上报推荐问题事件

记录曝光、点击或关闭事件

实现：[RecordEvent](../../internal/handler/message_suggestion.go#L104)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话 ID |
| request | body | 是 | [internal_handler.SuggestionEventRequest](#model-060) | 事件 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 204 | No Content | 未声明 |

[返回模块目录](#group-18) · [返回总目录](#index)

<a id="group-19"></a>

## 其他

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/organizations/{id}/search-users`](#api-172) | 未命名 |
| GET | [`/api/v1/sessions/{session_id}/messages/{message_id}/artifacts`](#api-173) | 未命名 |
| GET | [`/api/v1/sessions/{session_id}/messages/{message_id}/artifacts/{index}/download`](#api-174) | 未命名 |

<a id="api-172"></a>

### GET `/api/v1/organizations/{id}/search-users`

未命名接口

实现：[SearchUsersForInvite](../../internal/handler/organization.go#L1965)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

请求参数：注释未声明。

此接口在注释中标记为已弃用。

[返回模块目录](#group-19) · [返回总目录](#index)

<a id="api-173"></a>

### GET `/api/v1/sessions/{session_id}/messages/{message_id}/artifacts`

未命名接口

实现：[ListMessageArtifacts](../../internal/handler/session/artifact_download.go#L100)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

请求参数：注释未声明。

[返回模块目录](#group-19) · [返回总目录](#index)

<a id="api-174"></a>

### GET `/api/v1/sessions/{session_id}/messages/{message_id}/artifacts/{index}/download`

未命名接口

实现：[DownloadMessageArtifact](../../internal/handler/session/artifact_download.go#L154)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

请求参数：注释未声明。

[返回模块目录](#group-19) · [返回总目录](#index)

<a id="group-20"></a>

## 分块

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/chunker/preview`](#api-175) | 预览分块结果 |

<a id="api-175"></a>

### POST `/api/v1/chunker/preview`

预览分块结果

对提交的文本运行自适应分块器并返回分块预览，不写入数据库不生成 embedding。文本最大 64k 字符

实现：[PreviewChunking](../../internal/handler/chunker_debug.go#L105)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.PreviewChunkingRequest](#model-041) | {text, chunking_config} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 分块结果 | [internal_handler.PreviewChunkingResponse](#model-042) |
| 400 | 请求参数错误 | object |
| 413 | 文本超过预览限制 | object |
| 504 | 分块超时 | object |

[返回模块目录](#group-20) · [返回总目录](#index)

<a id="group-21"></a>

## 分块管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/chunks/by-id/{id}`](#api-176) | 通过ID获取分块 |
| DELETE | [`/api/v1/chunks/by-id/{id}/questions`](#api-177) | 删除生成的问题 |
| DELETE | [`/api/v1/chunks/{knowledge_id}`](#api-178) | 删除知识下所有分块 |
| GET | [`/api/v1/chunks/{knowledge_id}`](#api-179) | 获取知识分块列表 |
| DELETE | [`/api/v1/chunks/{knowledge_id}/{id}`](#api-180) | 删除分块 |
| PUT | [`/api/v1/chunks/{knowledge_id}/{id}`](#api-181) | 更新分块 |

<a id="api-176"></a>

### GET `/api/v1/chunks/by-id/{id}`

通过ID获取分块

仅通过分块ID获取分块详情（不需要knowledge_id）；支持共享知识库下的分块访问

实现：[GetChunkByIDOnly](../../internal/handler/chunk.go#L40)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 分块ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 分块详情 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 分块不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-21) · [返回总目录](#index)

<a id="api-177"></a>

### DELETE `/api/v1/chunks/by-id/{id}/questions`

删除生成的问题

删除分块中生成的问题

实现：[DeleteGeneratedQuestion](../../internal/handler/chunk.go#L429)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 分块ID |
| request | body | 是 | object | 问题ID |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "question_id": {
      "type": "string"
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 分块不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-21) · [返回总目录](#index)

<a id="api-178"></a>

### DELETE `/api/v1/chunks/{knowledge_id}`

删除知识下所有分块

删除指定知识下的所有分块

实现：[DeleteChunksByKnowledgeID](../../internal/handler/chunk.go#L394)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| knowledge_id | path | 是 | string | 知识ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-21) · [返回总目录](#index)

<a id="api-179"></a>

### GET `/api/v1/chunks/{knowledge_id}`

获取知识分块列表

获取指定知识下的所有分块列表，支持分页

实现：[ListKnowledgeChunks](../../internal/handler/chunk.go#L85)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| knowledge_id | path | 是 | string | 知识ID |
| page | query | 否 | integer | 页码；默认值：1 |
| page_size | query | 否 | integer | 每页数量；默认值：10 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 分块列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-21) · [返回总目录](#index)

<a id="api-180"></a>

### DELETE `/api/v1/chunks/{knowledge_id}/{id}`

删除分块

删除指定的分块

实现：[DeleteChunk](../../internal/handler/chunk.go#L358)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| knowledge_id | path | 是 | string | 知识ID |
| id | path | 是 | string | 分块ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 分块不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-21) · [返回总目录](#index)

<a id="api-181"></a>

### PUT `/api/v1/chunks/{knowledge_id}/{id}`

更新分块

更新指定分块的内容和属性

实现：[UpdateChunk](../../internal/handler/chunk.go#L199)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| knowledge_id | path | 是 | string | 知识ID |
| id | path | 是 | string | 分块ID |
| request | body | 是 | [internal_handler.UpdateChunkRequest](#model-065) | 更新请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的分块 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 分块不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-21) · [返回总目录](#index)

<a id="group-22"></a>

## 初始化

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/initialization/asr/check`](#api-182) | 检查ASR模型 |
| GET | [`/api/v1/initialization/config/{kbId}`](#api-183) | 获取知识库配置 |
| PUT | [`/api/v1/initialization/config/{kbId}`](#api-184) | 更新知识库配置 |
| POST | [`/api/v1/initialization/embedding/test`](#api-185) | 测试Embedding模型 |
| POST | [`/api/v1/initialization/extract/fabri-tag`](#api-186) | 生成随机标签 |
| POST | [`/api/v1/initialization/extract/fabri-text`](#api-187) | 生成示例文本 |
| POST | [`/api/v1/initialization/extract/text-relation`](#api-188) | 提取文本关系 |
| POST | [`/api/v1/initialization/initialize/{kbId}`](#api-189) | 初始化知识库配置 |
| POST | [`/api/v1/initialization/multimodal/test`](#api-190) | 测试多模态功能 |
| GET | [`/api/v1/initialization/ollama/download/progress/{taskId}`](#api-191) | 获取下载进度 |
| GET | [`/api/v1/initialization/ollama/download/tasks`](#api-192) | 列出下载任务 |
| GET | [`/api/v1/initialization/ollama/models`](#api-193) | 列出Ollama模型 |
| POST | [`/api/v1/initialization/ollama/models/check`](#api-194) | 检查Ollama模型状态 |
| POST | [`/api/v1/initialization/ollama/models/download`](#api-195) | 下载Ollama模型 |
| GET | [`/api/v1/initialization/ollama/status`](#api-196) | 检查Ollama服务状态 |
| POST | [`/api/v1/initialization/remote/check`](#api-197) | 检查远程模型 |
| POST | [`/api/v1/initialization/rerank/check`](#api-198) | 检查Rerank模型 |

<a id="api-182"></a>

### POST `/api/v1/initialization/asr/check`

检查ASR模型

检查ASR（语音识别）模型连接是否正常，通过发送一段静默音频测试 /v1/audio/transcriptions 端点

实现：[CheckASRModel](../../internal/handler/initialization.go#L2194)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.ModelTestRequest](#model-035) | ASR检查请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 检查结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-183"></a>

### GET `/api/v1/initialization/config/{kbId}`

获取知识库配置

根据知识库ID获取当前配置信息

实现：[GetCurrentConfigByKB](../../internal/handler/initialization.go#L1471)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kbId | path | 是 | string | 知识库ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 配置信息 | object |
| 404 | 知识库不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-184"></a>

### PUT `/api/v1/initialization/config/{kbId}`

更新知识库配置

根据知识库ID更新模型和分块配置

实现：[UpdateKBConfig](../../internal/handler/initialization.go#L225)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kbId | path | 是 | string | 知识库ID |
| request | body | 是 | [internal_handler.KBModelConfigRequest](#model-033) | 配置请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 知识库不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-185"></a>

### POST `/api/v1/initialization/embedding/test`

测试Embedding模型

测试Embedding接口是否可用并返回向量维度

实现：[TestEmbeddingModel](../../internal/handler/initialization.go#L1962)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.ModelTestRequest](#model-035) | Embedding测试请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 测试结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-186"></a>

### POST `/api/v1/initialization/extract/fabri-tag`

生成随机标签

随机生成一组标签

实现：[FabriTag](../../internal/handler/initialization.go#L2733)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 生成的标签 | object |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-187"></a>

### POST `/api/v1/initialization/extract/fabri-text`

生成示例文本

根据标签生成示例文本

实现：[FabriText](../../internal/handler/initialization.go#L2655)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.FabriTextRequest](#model-029) | 生成请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 生成的文本 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-188"></a>

### POST `/api/v1/initialization/extract/text-relation`

提取文本关系

从文本中提取实体和关系

实现：[ExtractTextRelations](../../internal/handler/initialization.go#L2554)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.TextRelationExtractionRequest](#model-063) | 提取请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 提取结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-189"></a>

### POST `/api/v1/initialization/initialize/{kbId}`

初始化知识库配置

根据知识库ID执行完整配置更新

实现：[InitializeByKB](../../internal/handler/initialization.go#L547)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| kbId | path | 是 | string | 知识库ID |
| request | body | 是 | [internal_handler.InitializationRequest](#model-032) | 初始化请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 初始化成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-190"></a>

### POST `/api/v1/initialization/multimodal/test`

测试多模态功能

上传图片测试多模态处理功能

实现：[TestMultimodalFunction](../../internal/handler/initialization.go#L2319)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`multipart/form-data`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| image | formData | 是 | file | 测试图片 |
| vlm_model | formData | 是 | string | VLM模型名称 |
| vlm_base_url | formData | 是 | string | VLM Base URL |
| vlm_api_key | formData | 否 | string | VLM API Key |
| vlm_interface_type | formData | 否 | string | VLM接口类型 |
| storage_type | formData | 是 | string | 存储类型(cos/minio) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 测试结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-191"></a>

### GET `/api/v1/initialization/ollama/download/progress/{taskId}`

获取下载进度

获取Ollama模型下载任务的进度

实现：[GetDownloadProgress](../../internal/handler/initialization.go#L1274)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| taskId | path | 是 | string | 任务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 下载进度 | object |
| 404 | 任务不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-192"></a>

### GET `/api/v1/initialization/ollama/download/tasks`

列出下载任务

列出所有Ollama模型下载任务

实现：[ListDownloadTasks](../../internal/handler/initialization.go#L1309)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 任务列表 | object |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-193"></a>

### GET `/api/v1/initialization/ollama/models`

列出Ollama模型

列出已安装的Ollama模型

实现：[ListOllamaModels](../../internal/handler/initialization.go#L1333)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 模型列表 | object |
| 500 | 服务器错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-194"></a>

### POST `/api/v1/initialization/ollama/models/check`

检查Ollama模型状态

检查指定的Ollama模型是否已安装

实现：[CheckOllamaModels](../../internal/handler/initialization.go#L1100)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 模型名称列表 |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "models": {
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 模型状态 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-195"></a>

### POST `/api/v1/initialization/ollama/models/download`

下载Ollama模型

异步下载指定的Ollama模型

实现：[DownloadOllamaModel](../../internal/handler/initialization.go#L1162)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 模型名称 |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "modelName": {
      "type": "string"
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 下载任务信息 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-196"></a>

### GET `/api/v1/initialization/ollama/status`

检查Ollama服务状态

检查Ollama服务是否可用

实现：[CheckOllamaStatus](../../internal/handler/initialization.go#L1049)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Ollama状态 | object |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-197"></a>

### POST `/api/v1/initialization/remote/check`

检查远程模型

检查远程API模型连接是否正常

实现：[CheckRemoteModel](../../internal/handler/initialization.go#L1905)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.RemoteModelCheckRequest](#model-045) | 模型检查请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 检查结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="api-198"></a>

### POST `/api/v1/initialization/rerank/check`

检查Rerank模型

检查Rerank模型连接和功能是否正常

实现：[CheckRerankModel](../../internal/handler/initialization.go#L2130)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.ModelTestRequest](#model-035) | Rerank检查请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 检查结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-22) · [返回总目录](#index)

<a id="group-23"></a>

## 审计日志

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/system/admin/audit-log`](#api-199) | 获取平台审计日志 |
| GET | [`/api/v1/tenants/{id}/audit-log`](#api-200) | 获取空间审计日志 |

<a id="api-199"></a>

### GET `/api/v1/system/admin/audit-log`

获取平台审计日志

返回 system-scope（tenant_id=0）的审计事件，覆盖 system.setting_changed / system.admin_promoted / system.admin_revoked 等 SystemAdmin 操作。按 id 倒序的游标分页。

实现：[ListSystemAuditLog](../../internal/handler/audit_log.go#L188)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| after_id | query | 否 | integer | 游标：返回 id 小于此值的记录（默认从最新开始） |
| limit | query | 否 | integer | 页大小，1-100，默认 50 |
| action | query | 否 | string | 按 action 精确过滤（如 system.setting_changed） |
| outcome | query | 否 | string | 按 outcome 精确过滤（success / denied） |
| actor | query | 否 | string | 按 actor_user_id 精确过滤 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.auditLogListResponse](#model-077) |
| 500 | Internal Server Error | [errors.AppError](#model-005) |

[返回模块目录](#group-23) · [返回总目录](#index)

<a id="api-200"></a>

### GET `/api/v1/tenants/{id}/audit-log`

获取空间审计日志

返回该空间最近的审计事件，按 id 倒序。游标分页：将上次响应的 next_cursor 作为下一次请求的 after_id。

实现：[ListTenantAuditLog](../../internal/handler/audit_log.go#L37)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间ID |
| after_id | query | 否 | integer | 游标：返回 id 小于此值的记录（默认从最新开始） |
| limit | query | 否 | integer | 页大小，1-100，默认 50 |
| action | query | 否 | string | 按 action 精确过滤（如 rbac.member_added / rbac.access_denied） |
| outcome | query | 否 | string | 按 outcome 精确过滤（success / denied） |
| actor | query | 否 | string | 按 actor_user_id 精确过滤 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.auditLogListResponse](#model-077) |
| 400 | Bad Request | [errors.AppError](#model-005) |

[返回模块目录](#group-23) · [返回总目录](#index)

<a id="group-24"></a>

## 我的邀请

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/me/invitations`](#api-201) | 列出我的待接受邀请 |
| POST | [`/api/v1/me/invitations/accept-by-token`](#api-202) | 通过共享链接加入空间 |
| GET | [`/api/v1/me/invitations/pending-count`](#api-203) | 获取我的待处理邀请数 |
| POST | [`/api/v1/me/invitations/{inv_id}/accept`](#api-204) | 接受邀请 |
| POST | [`/api/v1/me/invitations/{inv_id}/decline`](#api-205) | 拒绝邀请 |

<a id="api-201"></a>

### GET `/api/v1/me/invitations`

列出我的待接受邀请

返回当前登录用户的待接受邀请（默认仅 pending），用于头像入口和 /invitations 收件箱页。

实现：[ListMyInvitations](../../internal/handler/tenant_invitation.go#L446)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| include_terminal | query | 否 | boolean | 是否包含已处理 / 已过期等终止态行（默认 false） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-24) · [返回总目录](#index)

<a id="api-202"></a>

### POST `/api/v1/me/invitations/accept-by-token`

通过共享链接加入空间

已登录用户用共享邀请链接 token 加入空间，不创建新账号；对已是成员的用户幂等。

实现：[AcceptMyInvitationByToken](../../internal/handler/tenant_invitation.go#L586)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.acceptInvitationByTokenRequest](#model-072) | 邀请 token |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 410 | 链接无效或已撤销 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-24) · [返回总目录](#index)

<a id="api-203"></a>

### GET `/api/v1/me/invitations/pending-count`

获取我的待处理邀请数

轻量级 endpoint，返回当前登录用户的 pending 邀请数，用于头像旁的角标轮询。

实现：[CountMyPendingInvitations](../../internal/handler/tenant_invitation.go#L486)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-24) · [返回总目录](#index)

<a id="api-204"></a>

### POST `/api/v1/me/invitations/{inv_id}/accept`

接受邀请

当前登录用户接受一条 pending 邀请；服务端会同时写入 tenant_members 行。

实现：[AcceptMyInvitation](../../internal/handler/tenant_invitation.go#L514)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| inv_id | path | 是 | string | 邀请 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-24) · [返回总目录](#index)

<a id="api-205"></a>

### POST `/api/v1/me/invitations/{inv_id}/decline`

拒绝邀请

当前登录用户拒绝一条 pending 邀请；不创建 tenant_members 行。

实现：[DeclineMyInvitation](../../internal/handler/tenant_invitation.go#L664)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| inv_id | path | 是 | string | 邀请 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-24) · [返回总目录](#index)

<a id="group-25"></a>

## 智能体

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/agents`](#api-206) | 获取智能体列表 |
| POST | [`/api/v1/agents`](#api-207) | 创建智能体 |
| GET | [`/api/v1/agents/placeholders`](#api-208) | 获取占位符定义 |
| GET | [`/api/v1/agents/type-presets`](#api-209) | 获取智能体类型预设列表 |
| DELETE | [`/api/v1/agents/{id}`](#api-210) | 删除智能体 |
| GET | [`/api/v1/agents/{id}`](#api-211) | 获取智能体详情 |
| PUT | [`/api/v1/agents/{id}`](#api-212) | 更新智能体 |
| POST | [`/api/v1/agents/{id}/copy`](#api-213) | 复制智能体 |
| GET | [`/api/v1/agents/{id}/suggested-questions`](#api-214) | 获取推荐问题 |

<a id="api-206"></a>

### GET `/api/v1/agents`

获取智能体列表

获取当前空间的所有智能体（包括内置智能体）

实现：[ListAgents](../../internal/handler/custom_agent.go#L217)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 智能体列表 | object |
| 500 | 服务器错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="api-207"></a>

### POST `/api/v1/agents`

创建智能体

创建新的自定义智能体

实现：[CreateAgent](../../internal/handler/custom_agent.go#L82)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.CreateAgentRequest](#model-022) | 智能体信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 创建的智能体 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="api-208"></a>

### GET `/api/v1/agents/placeholders`

获取占位符定义

获取所有可用的提示词占位符定义，按字段类型分组

实现：[GetPlaceholders](../../internal/handler/custom_agent.go#L597)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 占位符定义 | object |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="api-209"></a>

### GET `/api/v1/agents/type-presets`

获取智能体类型预设列表

返回所有 smart-reasoning 下可用的智能体类型预设（RAG/Wiki/Hybrid/Custom），用于编辑器自动填充系统提示词、工具和 KB 兼容性

实现：[GetAgentTypePresets](../../internal/handler/custom_agent.go#L623)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 预设列表 | object |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="api-210"></a>

### DELETE `/api/v1/agents/{id}`

删除智能体

删除指定的智能体

实现：[DeleteAgent](../../internal/handler/custom_agent.go#L450)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 智能体ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 无法删除内置智能体 | [errors.AppError](#model-005) |
| 404 | 智能体不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="api-211"></a>

### GET `/api/v1/agents/{id}`

获取智能体详情

根据ID获取智能体详情

实现：[GetAgent](../../internal/handler/custom_agent.go#L166)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 智能体ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 智能体详情 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 智能体不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="api-212"></a>

### PUT `/api/v1/agents/{id}`

更新智能体

更新智能体的名称、描述和配置

实现：[UpdateAgent](../../internal/handler/custom_agent.go#L342)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 智能体ID |
| request | body | 是 | [internal_handler.UpdateAgentRequest](#model-064) | 更新请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的智能体 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 无法修改内置智能体 | [errors.AppError](#model-005) |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="api-213"></a>

### POST `/api/v1/agents/{id}/copy`

复制智能体

复制指定的智能体

实现：[CopyAgent](../../internal/handler/custom_agent.go#L521)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 智能体ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 复制成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 智能体不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="api-214"></a>

### GET `/api/v1/agents/{id}/suggested-questions`

获取推荐问题

基于智能体关联的知识库，返回推荐问题供用户快捷提问

实现：[GetSuggestedQuestions](../../internal/handler/custom_agent.go#L642)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 智能体ID |
| knowledge_base_ids | query | 否 | string | 知识库ID列表（逗号分隔），覆盖智能体默认配置 |
| knowledge_ids | query | 否 | string | 知识ID列表（逗号分隔），限定到具体文档 |
| tag_scopes | query | 否 | string | 带知识库归属的标签范围（JSON） |
| limit | query | 否 | integer | 返回数量上限（未传时使用智能体配置的开场问题数量，最大30） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 推荐问题列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 智能体不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-25) · [返回总目录](#index)

<a id="group-26"></a>

## 标签管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/knowledge-bases/{id}/tags`](#api-215) | 获取标签列表 |
| POST | [`/api/v1/knowledge-bases/{id}/tags`](#api-216) | 创建标签 |
| DELETE | [`/api/v1/knowledge-bases/{id}/tags/{tag_id}`](#api-217) | 删除标签 |
| PUT | [`/api/v1/knowledge-bases/{id}/tags/{tag_id}`](#api-218) | 更新标签 |

<a id="api-215"></a>

### GET `/api/v1/knowledge-bases/{id}/tags`

获取标签列表

获取知识库下的所有标签及统计信息

实现：[ListTags](../../internal/handler/tag.go#L72)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| page | query | 否 | integer | 页码 |
| page_size | query | 否 | integer | 每页数量 |
| keyword | query | 否 | string | 关键词搜索 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 标签列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-26) · [返回总目录](#index)

<a id="api-216"></a>

### POST `/api/v1/knowledge-bases/{id}/tags`

创建标签

在知识库下创建新标签

实现：[CreateTag](../../internal/handler/tag.go#L119)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | object | 标签信息 |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "color": {
      "type": "string"
    },
    "name": {
      "type": "string"
    },
    "sort_order": {
      "type": "integer"
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 创建的标签 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-26) · [返回总目录](#index)

<a id="api-217"></a>

### DELETE `/api/v1/knowledge-bases/{id}/tags/{tag_id}`

删除标签

删除标签，可使用force=true强制删除被引用的标签，content_only=true仅删除标签下的内容而保留标签本身

实现：[DeleteTag](../../internal/handler/tag.go#L210)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| tag_id | path | 是 | string | 标签ID (UUID或seq_id) |
| force | query | 否 | boolean | 强制删除 |
| content_only | query | 否 | boolean | 仅删除内容，保留标签 |
| body | body | 否 | [internal_handler.DeleteTagRequest](#model-026) | 删除选项 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-26) · [返回总目录](#index)

<a id="api-218"></a>

### PUT `/api/v1/knowledge-bases/{id}/tags/{tag_id}`

更新标签

更新标签信息

实现：[UpdateTag](../../internal/handler/tag.go#L165)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| tag_id | path | 是 | string | 标签ID (UUID或seq_id) |
| request | body | 是 | object | 标签更新信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的标签 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-26) · [返回总目录](#index)

<a id="group-27"></a>

## 模型管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/models`](#api-219) | 获取模型列表 |
| POST | [`/api/v1/models`](#api-220) | 创建模型 |
| GET | [`/api/v1/models/catalog/resolve`](#api-221) | 解析模型的有效接入配置 |
| GET | [`/api/v1/models/providers`](#api-222) | 获取模型厂商列表 |
| DELETE | [`/api/v1/models/{id}`](#api-223) | 删除模型 |
| GET | [`/api/v1/models/{id}`](#api-224) | 获取模型详情 |
| PUT | [`/api/v1/models/{id}`](#api-225) | 更新模型 |
| POST | [`/api/v1/models/{id}/copy`](#api-226) | 复制模型 |

<a id="api-219"></a>

### GET `/api/v1/models`

获取模型列表

获取当前空间的所有模型

实现：[ListModels](../../internal/handler/model.go#L246)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 模型列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-27) · [返回总目录](#index)

<a id="api-220"></a>

### POST `/api/v1/models`

创建模型

创建新的模型配置

实现：[CreateModel](../../internal/handler/model.go#L56)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.CreateModelRequest](#model-023) | 模型信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 创建的模型 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-27) · [返回总目录](#index)

<a id="api-221"></a>

### GET `/api/v1/models/catalog/resolve`

解析模型的有效接入配置

根据厂商、模型名、Base URL 与 extra_config 返回目录解析结果（协议、思考等级、上下文等），供模型编辑器实时展示

实现：[ResolveModelCatalog](../../internal/handler/model_catalog.go#L233)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| provider | query | 是 | string | 厂商标识 |
| model | query | 否 | string | 模型名 |
| base_url | query | 否 | string | Base URL |
| model_type | query | 否 | string | 模型类型 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 解析结果 | object |

[返回模块目录](#group-27) · [返回总目录](#index)

<a id="api-222"></a>

### GET `/api/v1/models/providers`

获取模型厂商列表

根据模型类型获取支持的厂商定义（含图标、默认地址、额外字段、内置模型目录与思考能力）

实现：[ListModelProviders](../../internal/handler/model_catalog.go#L183)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| model_type | query | 否 | string | 模型类型 (chat, embedding, rerank, vllm, asr) |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 厂商列表 | object |

[返回模块目录](#group-27) · [返回总目录](#index)

<a id="api-223"></a>

### DELETE `/api/v1/models/{id}`

删除模型

删除指定的模型

实现：[DeleteModel](../../internal/handler/model.go#L782)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 模型ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 模型仍被知识库、智能体或长期记忆引用 | [errors.AppError](#model-005) |
| 404 | 模型不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-27) · [返回总目录](#index)

<a id="api-224"></a>

### GET `/api/v1/models/{id}`

获取模型详情

根据ID获取模型详情

实现：[GetModel](../../internal/handler/model.go#L201)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 模型ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 模型详情 | object |
| 404 | 模型不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-27) · [返回总目录](#index)

<a id="api-225"></a>

### PUT `/api/v1/models/{id}`

更新模型

更新模型配置信息

实现：[UpdateModel](../../internal/handler/model.go#L648)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 模型ID |
| request | body | 是 | [internal_handler.UpdateModelRequest](#model-068) | 更新信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的模型 | object |
| 404 | 模型不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-27) · [返回总目录](#index)

<a id="api-226"></a>

### POST `/api/v1/models/{id}/copy`

复制模型

复制模型配置。name 与源模型一致，凭证从已存记录复制，不接受客户端重传。

实现：[CopyModel](../../internal/handler/model.go#L138)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 源模型ID |
| request | body | 是 | [internal_handler.CopyModelRequest](#model-021) | 副本展示名 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 复制的模型 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 模型不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-27) · [返回总目录](#index)

<a id="group-28"></a>

## 消息

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/messages/chat-history-stats`](#api-227) | 获取聊天历史知识库统计 |
| POST | [`/api/v1/messages/search`](#api-228) | 搜索历史对话 |
| GET | [`/api/v1/messages/{session_id}/load`](#api-229) | 加载消息历史 |
| DELETE | [`/api/v1/messages/{session_id}/{id}`](#api-230) | 删除消息 |
| GET | [`/api/v1/messages/{session_id}/{id}/trace`](#api-231) | 读取单条消息的对话链路（源码补充） |

<a id="api-227"></a>

### GET `/api/v1/messages/chat-history-stats`

获取聊天历史知识库统计

获取聊天历史知识库的统计信息（已索引消息数、知识库大小等）

实现：[GetChatHistoryKBStats](../../internal/handler/message.go#L304)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 统计信息 | object |

[返回模块目录](#group-28) · [返回总目录](#index)

<a id="api-228"></a>

### POST `/api/v1/messages/search`

搜索历史对话

通过关键词和/或向量相似度搜索历史对话记录，支持关键词、向量、混合三种模式

实现：[SearchMessages](../../internal/handler/message.go#L238)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.SearchMessagesRequest](#model-055) | 搜索请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 搜索结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-28) · [返回总目录](#index)

<a id="api-229"></a>

### GET `/api/v1/messages/{session_id}/load`

加载消息历史

加载会话的消息历史，支持分页和时间筛选

实现：[LoadMessages](../../internal/handler/message.go#L71)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |
| limit | query | 否 | integer | 返回数量；默认值：20 |
| before_time | query | 否 | string | 在此时间之前的消息（RFC3339Nano格式） |
| resource_urls | query | 否 | string | 文件引用形式，public 返回可加载直链；可选值：["handle", "public"]；默认值："handle" |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 消息列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-28) · [返回总目录](#index)

<a id="api-230"></a>

### DELETE `/api/v1/messages/{session_id}/{id}`

删除消息

从会话中删除指定消息

实现：[DeleteMessage](../../internal/handler/message.go#L183)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |
| id | path | 是 | string | 消息ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 500 | 服务器错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-28) · [返回总目录](#index)

<a id="api-231"></a>

### GET `/api/v1/messages/{session_id}/{id}/trace`

读取单条消息的对话链路（源码补充）

先校验当前主体对会话、消息的读取权限，再由服务端查询 Langfuse。优先使用消息持久化的 traceparent；旧消息按会话 ID 与请求 ID 精确匹配，不会用最近一次请求替代。Langfuse 凭据不会返回前端。state=ready 表示已获取链路；disabled 表示未启用追踪；not_found 表示尚未上报或没有匹配记录。

实现：[GetTrace](../../internal/handler/message_trace.go#L16)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话 ID |
| id | path | 是 | string | 持久化消息 ID；分段回答使用 assistant_message_id |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 成功；通过 state 区分已找到、未配置、未找到 | [Study.ChatTraceResponse](#model-002) |
| 401 | 未认证 | 未声明 |
| 403 | 角色或 API Key 权限不足 | 未声明 |
| 404 | 消息不存在或当前主体不可读取 | 未声明 |
| 503 | Langfuse 不可达或读取失败 | 未声明 |

[返回模块目录](#group-28) · [返回总目录](#index)

<a id="group-29"></a>

## 知识

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/knowledge/move`](#api-232) | 移动知识到其他知识库 |
| GET | [`/api/v1/knowledge/move/progress/{task_id}`](#api-233) | 获取知识移动进度 |

<a id="api-232"></a>

### POST `/api/v1/knowledge/move`

移动知识到其他知识库

将一条或多条知识从源知识库移动到目标知识库（异步），返回任务 ID 用于查询进度

实现：[MoveKnowledge](../../internal/handler/knowledge.go#L2773)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.MoveKnowledgeRequest](#model-036) | {source_kb_id, target_kb_id, knowledge_ids} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 任务信息 | [internal_handler.MoveKnowledgeResponse](#model-037) |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-29) · [返回总目录](#index)

<a id="api-233"></a>

### GET `/api/v1/knowledge/move/progress/{task_id}`

获取知识移动进度

按任务 ID 查询移动进度

实现：[GetKnowledgeMoveProgress](../../internal/handler/knowledge.go#L2972)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| task_id | path | 是 | string | 移动任务 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 进度信息 | [types.KnowledgeMoveProgress](#model-155) |
| 404 | 任务不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-29) · [返回总目录](#index)

<a id="group-30"></a>

## 知识库

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/knowledge-bases`](#api-234) | 获取知识库列表 |
| POST | [`/api/v1/knowledge-bases`](#api-235) | 创建知识库 |
| POST | [`/api/v1/knowledge-bases/copy`](#api-236) | 复制知识库 |
| GET | [`/api/v1/knowledge-bases/copy/progress/{task_id}`](#api-237) | 获取知识库复制进度 |
| DELETE | [`/api/v1/knowledge-bases/{id}`](#api-238) | 删除知识库 |
| GET | [`/api/v1/knowledge-bases/{id}`](#api-239) | 获取知识库详情 |
| PUT | [`/api/v1/knowledge-bases/{id}`](#api-240) | 更新知识库 |
| GET | [`/api/v1/knowledge-bases/{id}/activity`](#api-241) | 获取知识库活动记录 |
| POST | [`/api/v1/knowledge-bases/{id}/duplicate`](#api-242) | 创建知识库副本 |
| GET | [`/api/v1/knowledge-bases/{id}/hybrid-search`](#api-243) | 混合搜索 |
| POST | [`/api/v1/knowledge-bases/{id}/hybrid-search`](#api-244) | 混合搜索 |
| GET | [`/api/v1/knowledge-bases/{id}/move-targets`](#api-245) | 获取可移动目标知识库列表 |
| PUT | [`/api/v1/knowledge-bases/{id}/pin`](#api-246) | 置顶/取消置顶知识库 |
| POST | [`/api/v1/knowledge-bases/{id}/profile/generate`](#api-247) | 生成知识库描述 |

<a id="api-234"></a>

### GET `/api/v1/knowledge-bases`

获取知识库列表

获取当前空间的所有知识库；或当传入 agent_id（共享智能体）时，校验权限后返回该智能体配置的知识库范围（用于 @ 提及）

实现：[ListKnowledgeBases](../../internal/handler/knowledgebase.go#L523)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| agent_id | query | 否 | string | 共享智能体 ID（传入时返回该智能体可用的知识库） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 知识库列表 | object |
| 500 | 服务器错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-235"></a>

### POST `/api/v1/knowledge-bases`

创建知识库

创建新的知识库

实现：[CreateKnowledgeBase](../../internal/handler/knowledgebase.go#L404)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.KnowledgeBase](#model-149) | 知识库信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 创建的知识库 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-236"></a>

### POST `/api/v1/knowledge-bases/copy`

复制知识库

将一个知识库的内容复制到另一个知识库（异步任务）

实现：[CopyKnowledgeBase](../../internal/handler/knowledgebase.go#L931)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.CopyKnowledgeBaseRequest](#model-020) | 复制请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 任务ID | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-237"></a>

### GET `/api/v1/knowledge-bases/copy/progress/{task_id}`

获取知识库复制进度

获取知识库复制任务的进度

实现：[GetKBCloneProgress](../../internal/handler/knowledgebase.go#L1149)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| task_id | path | 是 | string | 任务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 进度信息 | object |
| 404 | 任务不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-238"></a>

### DELETE `/api/v1/knowledge-bases/{id}`

删除知识库

删除指定的知识库及其所有内容

实现：[DeleteKnowledgeBase](../../internal/handler/knowledgebase.go#L862)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-239"></a>

### GET `/api/v1/knowledge-bases/{id}`

获取知识库详情

根据ID获取知识库详情。当使用共享智能体时，可传 agent_id 以校验该智能体是否有权访问该知识库。

实现：[GetKnowledgeBase](../../internal/handler/knowledgebase.go#L488)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| agent_id | query | 否 | string | 共享智能体 ID（用于校验智能体是否有权访问该知识库） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 知识库详情 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 知识库不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-240"></a>

### PUT `/api/v1/knowledge-bases/{id}`

更新知识库

更新知识库的名称、描述和配置

实现：[UpdateKnowledgeBase](../../internal/handler/knowledgebase.go#L742)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [internal_handler.UpdateKnowledgeBaseRequest](#model-066) | 更新请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的知识库 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-241"></a>

### GET `/api/v1/knowledge-bases/{id}/activity`

获取知识库活动记录

返回知识库的重要变更与后台任务入口。仅知识库创建者或所属空间管理员可读，共享空间不可读。

实现：[ListKnowledgeBaseActivity](../../internal/handler/audit_log.go#L109)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| after_id | query | 否 | integer | 游标：返回 id 小于此值的记录 |
| limit | query | 否 | integer | 页大小，1-100，默认 50 |
| action | query | 否 | string | 按 action 精确过滤 |
| outcome | query | 否 | string | 按 outcome 精确过滤 |
| actor | query | 否 | string | 按 actor_user_id 精确过滤 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.auditLogListResponse](#model-077) |
| 403 | Forbidden | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-242"></a>

### POST `/api/v1/knowledge-bases/{id}/duplicate`

创建知识库副本

创建一个只包含设置的新知识库副本，不复制知识、FAQ 内容、分块、索引、Wiki 页面、分享或置顶状态

实现：[DuplicateKnowledgeBase](../../internal/handler/knowledgebase.go#L1083)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 源知识库 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 创建后的知识库副本 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-243"></a>

### GET `/api/v1/knowledge-bases/{id}/hybrid-search`

混合搜索

底层召回：向量+关键词混合检索，默认不 rerank（可用 rerank 字段开启）；一般检索请用 /knowledge-search。推荐 POST，GET 带 JSON 体仅兼容旧客户端。

实现：[HybridSearch](../../internal/handler/knowledgebase.go#L306)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [types.SearchParams](#model-208) | 搜索参数 |
| resource_urls | query | 否 | string | 文件引用形式，public 返回可加载直链；可选值：["handle", "public"]；默认值："handle" |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 搜索结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-244"></a>

### POST `/api/v1/knowledge-bases/{id}/hybrid-search`

混合搜索

底层召回：向量+关键词混合检索，默认不 rerank（可用 rerank 字段开启）；一般检索请用 /knowledge-search。推荐 POST，GET 带 JSON 体仅兼容旧客户端。

实现：[HybridSearch](../../internal/handler/knowledgebase.go#L306)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [types.SearchParams](#model-208) | 搜索参数 |
| resource_urls | query | 否 | string | 文件引用形式，public 返回可加载直链；可选值：["handle", "public"]；默认值："handle" |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 搜索结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-245"></a>

### GET `/api/v1/knowledge-bases/{id}/move-targets`

获取可移动目标知识库列表

返回与源知识库 Type 一致、EmbeddingModelID 一致、非临时且不是自身的目标知识库列表

实现：[ListMoveTargets](../../internal/handler/knowledgebase.go#L1261)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 源知识库 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 可移动目标列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 知识库不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-246"></a>

### PUT `/api/v1/knowledge-bases/{id}/pin`

置顶/取消置顶知识库

切换知识库的置顶状态

实现：[TogglePinKnowledgeBase](../../internal/handler/knowledgebase.go#L697)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的知识库 | object |
| 404 | 知识库不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="api-247"></a>

### POST `/api/v1/knowledge-bases/{id}/profile/generate`

生成知识库描述

基于文档画像聚合，立即重新生成知识库的 AI 描述（不覆盖手写描述）

实现：[GenerateKnowledgeBaseProfile](../../internal/handler/knowledgebase.go#L814)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 生成的知识库画像 | object |
| 400 | 知识库类型不支持或未配置模型 | [errors.AppError](#model-005) |

[返回模块目录](#group-30) · [返回总目录](#index)

<a id="group-31"></a>

## 知识库共享

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/knowledge-bases/{id}/shares`](#api-248) | 获取知识库的共享列表 |
| POST | [`/api/v1/knowledge-bases/{id}/shares`](#api-249) | 共享知识库到组织 |
| DELETE | [`/api/v1/knowledge-bases/{id}/shares/{share_id}`](#api-250) | 取消共享 |
| PUT | [`/api/v1/knowledge-bases/{id}/shares/{share_id}`](#api-251) | 更新共享权限 |
| GET | [`/api/v1/shared-knowledge-bases`](#api-252) | 获取共享给我的知识库列表 |

<a id="api-248"></a>

### GET `/api/v1/knowledge-bases/{id}/shares`

获取知识库的共享列表

获取知识库的所有共享记录

实现：[ListKBShares](../../internal/handler/organization.go#L1093)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.ListSharesResponse](#model-159) |

[返回模块目录](#group-31) · [返回总目录](#index)

<a id="api-249"></a>

### POST `/api/v1/knowledge-bases/{id}/shares`

共享知识库到组织

将知识库共享到指定组织

实现：[ShareKnowledgeBase](../../internal/handler/organization.go#L1051)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [types.ShareKnowledgeBaseRequest](#model-212) | 共享信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-31) · [返回总目录](#index)

<a id="api-250"></a>

### DELETE `/api/v1/knowledge-bases/{id}/shares/{share_id}`

取消共享

取消知识库的共享

实现：[RemoveShare](../../internal/handler/organization.go#L1232)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| share_id | path | 是 | string | 共享记录ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-31) · [返回总目录](#index)

<a id="api-251"></a>

### PUT `/api/v1/knowledge-bases/{id}/shares/{share_id}`

更新共享权限

更新知识库共享的权限级别

实现：[UpdateSharePermission](../../internal/handler/organization.go#L1153)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| share_id | path | 是 | string | 共享记录ID |
| request | body | 是 | [types.UpdateSharePermissionRequest](#model-234) | 权限信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-31) · [返回总目录](#index)

<a id="api-252"></a>

### GET `/api/v1/shared-knowledge-bases`

获取共享给我的知识库列表

获取通过组织共享给当前用户的所有知识库

实现：[ListSharedKnowledgeBases](../../internal/handler/organization.go#L1343)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-31) · [返回总目录](#index)

<a id="group-32"></a>

## 知识管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| DELETE | [`/api/v1/knowledge-bases/{id}/knowledge`](#api-253) | 清空知识库内容 |
| GET | [`/api/v1/knowledge-bases/{id}/knowledge`](#api-254) | 获取知识列表 |
| POST | [`/api/v1/knowledge-bases/{id}/knowledge/batch-download`](#api-255) | 批量下载知识文件 |
| POST | [`/api/v1/knowledge-bases/{id}/knowledge/file`](#api-256) | 从文件创建知识 |
| GET | [`/api/v1/knowledge-bases/{id}/knowledge/folders`](#api-257) | 获取知识库文件夹目录树 |
| PUT | [`/api/v1/knowledge-bases/{id}/knowledge/folders`](#api-258) | 重命名或移动文件夹 |
| POST | [`/api/v1/knowledge-bases/{id}/knowledge/manual`](#api-259) | 手工创建知识 |
| POST | [`/api/v1/knowledge-bases/{id}/knowledge/url`](#api-260) | 从URL创建知识 |
| GET | [`/api/v1/knowledge/batch`](#api-261) | 批量获取知识 |
| POST | [`/api/v1/knowledge/batch-delete`](#api-262) | 批量删除知识 |
| POST | [`/api/v1/knowledge/batch-reparse`](#api-263) | 批量重新解析知识 |
| POST | [`/api/v1/knowledge/folder`](#api-264) | 移动知识到文件夹 |
| PUT | [`/api/v1/knowledge/image/{id}/{chunk_id}`](#api-265) | 更新图像信息 |
| PUT | [`/api/v1/knowledge/manual/{id}`](#api-266) | 更新手工知识 |
| PUT | [`/api/v1/knowledge/tags`](#api-267) | 批量更新知识标签 |
| DELETE | [`/api/v1/knowledge/{id}`](#api-268) | 删除知识 |
| GET | [`/api/v1/knowledge/{id}`](#api-269) | 获取知识详情 |
| PUT | [`/api/v1/knowledge/{id}`](#api-270) | 更新知识 |
| POST | [`/api/v1/knowledge/{id}/cancel-parse`](#api-271) | 取消知识解析 |
| GET | [`/api/v1/knowledge/{id}/chunks/{chunk_id}/details`](#api-272) | 获取文档分块的完整内容和索引详情 |
| GET | [`/api/v1/knowledge/{id}/download`](#api-273) | 下载知识文件 |
| GET | [`/api/v1/knowledge/{id}/index-status`](#api-274) | 获取文档各类索引的构建状态与实际数量 |
| GET | [`/api/v1/knowledge/{id}/preview`](#api-275) | 预览知识文件 |
| POST | [`/api/v1/knowledge/{id}/reparse`](#api-276) | 重新解析知识 |
| GET | [`/api/v1/knowledge/{id}/spans`](#api-277) | 获取知识文档解析的 Span 树（含历史尝试） |

<a id="api-253"></a>

### DELETE `/api/v1/knowledge-bases/{id}/knowledge`

清空知识库内容

删除知识库下的所有知识条目（异步任务）。知识库本身保留，仅清空其中的内容

实现：[ClearKnowledgeBaseContents](../../internal/handler/knowledge.go#L1768)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 清空任务已提交 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-254"></a>

### GET `/api/v1/knowledge-bases/{id}/knowledge`

获取知识列表

获取知识库下的知识列表，支持分页和筛选

实现：[ListKnowledge](../../internal/handler/knowledge.go#L990)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| page | query | 否 | integer | 页码 |
| page_size | query | 否 | integer | 每页数量 |
| tag_ids | query | 否 | string | 标签ID筛选，逗号分隔（OR语义） |
| keyword | query | 否 | string | 关键词搜索 |
| file_type | query | 否 | string | 文件类型筛选 |
| parse_status | query | 否 | string | 解析状态筛选 (pending/processing/completed/failed) |
| source | query | 否 | string | 来源/渠道筛选 (web/api/feishu/notion/yuque/wechat/...，或 manual/url 按 type 过滤) |
| start_time | query | 否 | string | 更新时间起点，RFC3339 格式 |
| end_time | query | 否 | string | 更新时间终点，RFC3339 格式 |
| folder_path | query | 否 | string | 文件夹路径筛选，空字符串表示知识库根目录；不传该参数则不按文件夹过滤 |
| folder_recursive | query | 否 | boolean | 为 true 时同时返回子文件夹内的文档 |
| sort_by | query | 否 | string | 排序字段: updated_at/created_at/file_name，默认 created_at |
| sort_order | query | 否 | string | 排序方向: asc/desc，默认 desc |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 知识列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-255"></a>

### POST `/api/v1/knowledge-bases/{id}/knowledge/batch-download`

批量下载知识文件

将同一知识库的最多 200 个文档打包为 ZIP，原始内容合计不超过 512 MiB。无原文件的条目会被跳过；无权访问、跨库或读取失败时不返回残缺压缩包。

实现：[BatchDownloadKnowledge](../../internal/handler/knowledge_download.go#L41)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/zip`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [internal_handler.BatchDownloadKnowledgeRequest](#model-019) | 文档ID列表 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | ZIP 压缩包 | file |
| 400 | Bad Request | [errors.AppError](#model-005) |
| 401 | Unauthorized | [errors.AppError](#model-005) |
| 403 | Forbidden | [errors.AppError](#model-005) |
| 404 | Not Found | [errors.AppError](#model-005) |
| 429 | Too Many Requests | [errors.AppError](#model-005) |
| 500 | Internal Server Error | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-256"></a>

### POST `/api/v1/knowledge-bases/{id}/knowledge/file`

从文件创建知识

上传文件并创建知识条目

实现：[CreateKnowledgeFromFile](../../internal/handler/knowledge.go#L285)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`multipart/form-data`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| file | formData | 是 | file | 上传的文件 |
| fileName | formData | 否 | string | 自定义文件名 |
| metadata | formData | 否 | string | 元数据JSON |
| enable_multimodel | formData | 否 | boolean | 启用多模态处理 |
| tag_ids | formData | 否 | string | 分类ID列表，逗号分隔 |
| process_config | formData | 否 | string | 处理配置JSON（KnowledgeProcessOverrides） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 创建的知识 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 409 | 文件重复 | object |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-257"></a>

### GET `/api/v1/knowledge-bases/{id}/knowledge/folders`

获取知识库文件夹目录树

返回知识库内由文件夹上传形成的目录树，包含每个文件夹的直接文档数与含子目录的总数

实现：[ListKnowledgeFolders](../../internal/handler/knowledge.go#L1359)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 目录树 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-258"></a>

### PUT `/api/v1/knowledge-bases/{id}/knowledge/folders`

重命名或移动文件夹

把一个文件夹及其所有子目录改到新路径。目标路径已存在时两个文件夹合并；不能移动到自身子目录下

实现：[RenameKnowledgeFolder](../../internal/handler/knowledge.go#L1482)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [internal_handler.RenameKnowledgeFolderRequest](#model-046) | 重命名请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 重命名成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-259"></a>

### POST `/api/v1/knowledge-bases/{id}/knowledge/manual`

手工创建知识

手工录入Markdown格式的知识内容

实现：[CreateManualKnowledge](../../internal/handler/knowledge.go#L552)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | [types.ManualKnowledgePayload](#model-169) | 手工知识内容 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 创建的知识 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-260"></a>

### POST `/api/v1/knowledge-bases/{id}/knowledge/url`

从URL创建知识

从指定URL抓取内容并创建知识条目。当提供 file_name/file_type 或 URL 路径含已知文件扩展名时，自动切换为文件下载模式

实现：[CreateKnowledgeFromURL](../../internal/handler/knowledge.go#L454)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识库ID |
| request | body | 是 | object | URL请求 |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "enable_multimodel": {
      "type": "boolean"
    },
    "file_name": {
      "type": "string"
    },
    "file_type": {
      "type": "string"
    },
    "tag_ids": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "title": {
      "type": "string"
    },
    "url": {
      "type": "string"
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 创建的知识 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 409 | URL重复 | object |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-261"></a>

### GET `/api/v1/knowledge/batch`

批量获取知识

根据ID列表批量获取知识条目。可选 kb_id：指定时按该知识库校验权限并用于共享知识库的空间解析；可选 agent_id：使用共享智能体时传此参数，后端按智能体所属空间查询（用于刷新后恢复共享知识库下的文件）

实现：[GetKnowledgeBatch](../../internal/handler/knowledge.go#L1941)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| ids | query | 是 | array&lt;string&gt; | 知识ID列表；集合格式："csv" |
| kb_id | query | 否 | string | 可选，知识库ID（用于共享知识库时指定范围） |
| agent_id | query | 否 | string | 可选，共享智能体ID（用于按智能体空间批量拉取文件详情） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 知识列表 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-262"></a>

### POST `/api/v1/knowledge/batch-delete`

批量删除知识

按 ID 列表批量删除单个知识库下的多个知识条目

实现：[BatchDeleteKnowledge](../../internal/handler/knowledge.go#L1674)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.BatchDeleteKnowledgeRequest](#model-018) | 批量删除请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-263"></a>

### POST `/api/v1/knowledge/batch-reparse`

批量重新解析知识

按 ID 列表批量重新解析单个知识库下的多个知识条目

实现：[BatchReparseKnowledge](../../internal/handler/knowledge.go#L3055)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.batchReparseKnowledgeRequest](#model-078) | 批量重解析请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 任务已提交 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-264"></a>

### POST `/api/v1/knowledge/folder`

移动知识到文件夹

批量修改知识条目所属文件夹。文件夹由路径推导而来，因此目标路径不存在时会自动创建；空路径表示知识库顶层。仅调整归类，不会重新解析文档

实现：[MoveKnowledgeToFolder](../../internal/handler/knowledge.go#L1408)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.MoveKnowledgeToFolderRequest](#model-038) | 移动请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 移动成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-265"></a>

### PUT `/api/v1/knowledge/image/{id}/{chunk_id}`

更新图像信息

更新知识分块的图像信息

实现：[UpdateImageInfo](../../internal/handler/knowledge.go#L2532)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |
| chunk_id | path | 是 | string | 分块ID |
| request | body | 是 | object | 图像信息 |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "image_info": {
      "type": "string"
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-266"></a>

### PUT `/api/v1/knowledge/manual/{id}`

更新手工知识

更新手工录入的Markdown知识内容

实现：[UpdateManualKnowledge](../../internal/handler/knowledge.go#L2273)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |
| request | body | 是 | [types.ManualKnowledgePayload](#model-169) | 手工知识内容 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的知识 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-267"></a>

### PUT `/api/v1/knowledge/tags`

批量更新知识标签

批量更新知识条目的标签。可选 kb_id：指定时按该知识库校验编辑权限并用于共享知识库的空间解析

实现：[UpdateKnowledgeTagBatch](../../internal/handler/knowledge.go#L2457)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 标签更新请求（updates 必填，kb_id 可选） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-268"></a>

### DELETE `/api/v1/knowledge/{id}`

删除知识

根据ID异步删除知识条目。请求会被入队到与批量删除相同的异步管道（asynq）；
接口返回 200 仅表示任务已提交（响应 data.task_id 为任务 ID），实际删除由后台 worker 完成。

实现：[DeleteKnowledge](../../internal/handler/knowledge.go#L1604)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 任务已提交，返回 task_id | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-269"></a>

### GET `/api/v1/knowledge/{id}`

获取知识详情

根据ID获取知识条目详情

实现：[GetKnowledge](../../internal/handler/knowledge.go#L611)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 知识详情 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 知识不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-270"></a>

### PUT `/api/v1/knowledge/{id}`

更新知识

部分更新知识条目（标题/描述/自定义元数据）；未传字段保持不变，显式传空 description 可清空摘要

实现：[UpdateKnowledge](../../internal/handler/knowledge.go#L2161)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |
| request | body | 是 | [internal_handler.UpdateKnowledgeRequest](#model-067) | 更新字段（均可选） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-271"></a>

### POST `/api/v1/knowledge/{id}/cancel-parse`

取消知识解析

取消进行中的知识解析任务。当前已写入的 chunk / 索引保留，可通过 reparse 接口重新触发解析。已完成 / 已失败 / 删除中的知识不支持取消。

实现：[CancelKnowledgeParse](../../internal/handler/knowledge.go#L2399)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 取消已提交 | object |
| 400 | 状态不支持取消 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |
| 404 | 知识不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-272"></a>

### GET `/api/v1/knowledge/{id}/chunks/{chunk_id}/details`

获取文档分块的完整内容和索引详情

实现：[GetChunkDetail](../../internal/handler/chunk_detail.go#L16)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |
| chunk_id | path | 是 | string | 分块ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-273"></a>

### GET `/api/v1/knowledge/{id}/download`

下载知识文件

下载知识条目关联的原始文件

实现：[DownloadKnowledgeFile](../../internal/handler/knowledge.go#L1843)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/octet-stream`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 文件内容 | file |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-274"></a>

### GET `/api/v1/knowledge/{id}/index-status`

获取文档各类索引的构建状态与实际数量

实现：[GetKnowledgeIndexStatus](../../internal/handler/knowledge_index_status.go#L16)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-275"></a>

### GET `/api/v1/knowledge/{id}/preview`

预览知识文件

返回知识条目关联的原始文件，Content-Type 根据文件类型设置，用于浏览器内嵌预览

实现：[PreviewKnowledgeFile](../../internal/handler/knowledge.go#L1893)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/pdf`, `image/jpeg`, `image/png`, `text/plain`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 文件内容 | file |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-276"></a>

### POST `/api/v1/knowledge/{id}/reparse`

重新解析知识

删除知识中现有的文档内容并重新解析，使用异步任务方式处理

实现：[ReparseKnowledge](../../internal/handler/knowledge.go#L2330)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |
| body | body | 否 | object | 可选的处理配置覆盖：{\ |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 重新解析任务已提交 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="api-277"></a>

### GET `/api/v1/knowledge/{id}/spans`

获取知识文档解析的 Span 树（含历史尝试）

返回该知识在解析流水线的 trace tree（root → stage → subspan）：每段状态、耗时、input/output、错误码、langfuse_trace_id。支持 ?attempt=N 查看历史尝试；不传则返回最新尝试。前端用于渲染时间线 + 多模态/embedding 子节点 + 一键跳转 Langfuse。

实现：[GetKnowledgeSpans](../../internal/handler/knowledge.go#L658)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 知识ID |
| attempt | query | 否 | integer | 指定尝试号；省略=最新 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-32) · [返回总目录](#index)

<a id="group-33"></a>

## 空间成员

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/tenants/{id}/leave`](#api-278) | 退出当前空间 |
| GET | [`/api/v1/tenants/{id}/members`](#api-279) | 列出空间成员 |
| POST | [`/api/v1/tenants/{id}/members`](#api-280) | 直接添加空间成员（直加路径） |
| DELETE | [`/api/v1/tenants/{id}/members/{user_id}`](#api-281) | 移除空间成员 |
| PUT | [`/api/v1/tenants/{id}/members/{user_id}`](#api-282) | 修改空间成员角色 |

<a id="api-278"></a>

### POST `/api/v1/tenants/{id}/leave`

退出当前空间

调用方主动退出当前空间。等价于以自己的 user_id 调 RemoveMember，

实现：[LeaveTenant](../../internal/handler/tenant_member.go#L402)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-33) · [返回总目录](#index)

<a id="api-279"></a>

### GET `/api/v1/tenants/{id}/members`

列出空间成员

分页返回当前空间内 active 成员（含每位成员的角色、邮箱、头像）；支持 q 按邮箱/用户名筛选

实现：[ListMembers](../../internal/handler/tenant_member.go#L88)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |
| q | query | 否 | string | 按邮箱/用户名模糊筛选 |
| page | query | 否 | integer | 页码（从 1 起）；默认值：1 |
| page_size | query | 否 | integer | 每页数量（最大 100）；默认值：20 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-33) · [返回总目录](#index)

<a id="api-280"></a>

### POST `/api/v1/tenants/{id}/members`

直接添加空间成员（直加路径）

实现：[AddMember](../../internal/handler/tenant_member.go#L165)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |
| request | body | 是 | [internal_handler.addMemberRequest](#model-073) | 邀请请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | object |

[返回模块目录](#group-33) · [返回总目录](#index)

<a id="api-281"></a>

### DELETE `/api/v1/tenants/{id}/members/{user_id}`

移除空间成员

Owner 将某位成员从当前空间中移除（软删除 tenant_members 行）；不能移除最后一位 Owner

实现：[RemoveMember](../../internal/handler/tenant_member.go#L363)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |
| user_id | path | 是 | string | 用户 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-33) · [返回总目录](#index)

<a id="api-282"></a>

### PUT `/api/v1/tenants/{id}/members/{user_id}`

修改空间成员角色

Owner 修改某位成员在当前空间内的角色；不能将最后一位 Owner 降级

实现：[UpdateMemberRole](../../internal/handler/tenant_member.go#L308)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |
| user_id | path | 是 | string | 用户 ID |
| request | body | 是 | [internal_handler.updateMemberRoleRequest](#model-093) | 目标角色 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-33) · [返回总目录](#index)

<a id="group-34"></a>

## 空间管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/tenants`](#api-283) | 获取空间列表 |
| POST | [`/api/v1/tenants`](#api-284) | 创建空间 |
| GET | [`/api/v1/tenants/all`](#api-285) | 获取所有空间列表 |
| GET | [`/api/v1/tenants/kv/prompt-templates`](#api-286) | 获取提示词模板 |
| GET | [`/api/v1/tenants/kv/web-search-config`](#api-287) | 获取空间网络搜索配置 |
| GET | [`/api/v1/tenants/kv/{key}`](#api-288) | 获取空间KV配置 |
| PUT | [`/api/v1/tenants/kv/{key}`](#api-289) | 更新空间KV配置 |
| GET | [`/api/v1/tenants/search`](#api-290) | 搜索空间 |
| DELETE | [`/api/v1/tenants/{id}`](#api-291) | 删除空间 |
| GET | [`/api/v1/tenants/{id}`](#api-292) | 获取空间详情 |
| PUT | [`/api/v1/tenants/{id}`](#api-293) | 更新空间 |
| GET | [`/api/v1/tenants/{id}/api-principal-config`](#api-294) | 获取空间 API Key 用户身份配置 |
| PUT | [`/api/v1/tenants/{id}/api-principal-config`](#api-295) | 更新空间 API Key 用户身份配置 |
| POST | [`/api/v1/tenants/{id}/api-principal-test-token`](#api-296) | 生成 API Playground 测试 JWT |

<a id="api-283"></a>

### GET `/api/v1/tenants`

获取空间列表

获取当前用户可访问的空间列表

实现：[ListTenants](../../internal/handler/tenant.go#L1155)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 空间列表 | object |
| 500 | 服务器错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-284"></a>

### POST `/api/v1/tenants`

创建空间

创建新的空间。任意已登录用户均可调用以建立自己的新工作区，
调用方会被自动设为该空间的 Owner。跨空间超管仍可像以前一样
通过本接口创建任意空间。
当 tenant.auto_create_api_key（或 WEKNORA_TENANT_AUTO_CREATE_API_KEY）
开启时，会自动创建一个 full_access API Key，并在响应体的 data.api_key 字段返回其明文 token。

实现：[CreateTenant](../../internal/handler/tenant.go#L211)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.createTenantRequest](#model-081) | 空间信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | 创建的空间（可选含 api_key） | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-285"></a>

### GET `/api/v1/tenants/all`

获取所有空间列表

获取系统中所有空间（需要跨空间访问权限）

实现：[ListAllTenants](../../internal/handler/tenant.go#L1182)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 所有空间列表 | object |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-286"></a>

### GET `/api/v1/tenants/kv/prompt-templates`

获取提示词模板

获取系统配置的提示词模板列表

实现：[GetPromptTemplates](../../internal/handler/tenant.go#L1594)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 提示词模板配置 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-287"></a>

### GET `/api/v1/tenants/kv/web-search-config`

获取空间网络搜索配置

获取空间的网络搜索配置

实现：[GetTenantWebSearchConfig](../../internal/handler/tenant.go#L1442)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 网络搜索配置 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-288"></a>

### GET `/api/v1/tenants/kv/{key}`

获取空间KV配置

获取空间级别的KV配置（支持web-search-config、prompt-templates、parser-engine-config、storage-engine-config、chat-history-config、retrieval-config）

实现：[GetTenantKV](../../internal/handler/tenant.go#L1292)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| key | path | 是 | string | 配置键名 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 配置值 | object |
| 400 | 不支持的键 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-289"></a>

### PUT `/api/v1/tenants/kv/{key}`

更新空间KV配置

更新空间级别的KV配置（支持web-search-config、parser-engine-config、storage-engine-config、chat-history-config、retrieval-config）

实现：[UpdateTenantKV](../../internal/handler/tenant.go#L1345)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| key | path | 是 | string | 配置键名 |
| request | body | 是 | object | 配置值 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新成功 | object |
| 400 | 不支持的键 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-290"></a>

### GET `/api/v1/tenants/search`

搜索空间

分页搜索空间（需要跨空间访问权限）

实现：[SearchTenants](../../internal/handler/tenant.go#L1219)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| keyword | query | 否 | string | 搜索关键词 |
| tenant_id | query | 否 | integer | 空间ID筛选 |
| page | query | 否 | integer | 页码；默认值：1 |
| page_size | query | 否 | integer | 每页数量；默认值：20 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 搜索结果 | object |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-291"></a>

### DELETE `/api/v1/tenants/{id}`

删除空间

删除指定的空间

实现：[DeleteTenant](../../internal/handler/tenant.go#L1112)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer | 空间ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-292"></a>

### GET `/api/v1/tenants/{id}`

获取空间详情

根据ID获取空间详情

实现：[GetTenant](../../internal/handler/tenant.go#L528)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer | 空间ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 空间详情 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 空间不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-293"></a>

### PUT `/api/v1/tenants/{id}`

更新空间

更新空间信息

实现：[UpdateTenant](../../internal/handler/tenant.go#L569)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer | 空间ID |
| request | body | 是 | [types.Tenant](#model-225) | 空间信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的空间 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-294"></a>

### GET `/api/v1/tenants/{id}/api-principal-config`

获取空间 API Key 用户身份配置

返回 X-API-Key 请求如何映射为终端 Principal 的配置（Owner）

实现：[GetAPIPrincipalConfig](../../internal/handler/tenant.go#L886)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer | 空间ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | API principal 配置 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-295"></a>

### PUT `/api/v1/tenants/{id}/api-principal-config`

更新空间 API Key 用户身份配置

配置 X-API-Key 请求如何映射为终端 Principal（Owner）

实现：[UpdateAPIPrincipalConfig](../../internal/handler/tenant.go#L920)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer | 空间ID |
| request | body | 是 | [internal_handler.apiPrincipalConfigRequest](#model-075) | API principal 配置 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的配置 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="api-296"></a>

### POST `/api/v1/tenants/{id}/api-principal-test-token`

生成 API Playground 测试 JWT

使用空间已保存的 HMAC 密钥签发短期外部用户 JWT（Owner）

实现：[CreateAPIPrincipalTestToken](../../internal/handler/tenant.go#L1008)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | integer | 空间ID |
| request | body | 是 | [internal_handler.apiPrincipalTestTokenRequest](#model-076) | 测试 Token 参数 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 短期 JWT | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 权限不足 | [errors.AppError](#model-005) |

[返回模块目录](#group-34) · [返回总目录](#index)

<a id="group-35"></a>

## 空间邀请

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/tenants/{id}/invitations`](#api-297) | 列出空间邀请 |
| POST | [`/api/v1/tenants/{id}/invitations`](#api-298) | 发出空间邀请 |
| DELETE | [`/api/v1/tenants/{id}/invitations/{inv_id}`](#api-299) | 撤销待接受邀请 |
| POST | [`/api/v1/tenants/{id}/invite-links`](#api-300) | 生成共享邀请链接 |

<a id="api-297"></a>

### GET `/api/v1/tenants/{id}/invitations`

列出空间邀请

按空间列出待接受 / 历史邀请。query include_terminal=true 时附带 accepted/declined/revoked/expired。

实现：[ListTenantInvitations](../../internal/handler/tenant_invitation.go#L197)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |
| include_terminal | query | 否 | boolean | 是否包含终止态行（默认 false） |
| page | query | 否 | integer | 页码（从 1 起）；默认值：1 |
| page_size | query | 否 | integer | 每页数量；默认值：20 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-35) · [返回总目录](#index)

<a id="api-298"></a>

### POST `/api/v1/tenants/{id}/invitations`

发出空间邀请

Owner 通过邮箱邀请已注册用户加入空间。开启 tenant.auto_accept_invitation 后被邀请人立即自动加入（响应为成员结构），否则需在 /me/invitations 接受后成为成员。

实现：[CreateInvitation](../../internal/handler/tenant_invitation.go#L254)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |
| request | body | 是 | [internal_handler.createInvitationRequest](#model-079) | 邀请请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | object |

[返回模块目录](#group-35) · [返回总目录](#index)

<a id="api-299"></a>

### DELETE `/api/v1/tenants/{id}/invitations/{inv_id}`

撤销待接受邀请

Owner 取消一条还在 pending 的邀请；已 accepted/declined/revoked/expired 的行不可再撤销。

实现：[RevokeInvitation](../../internal/handler/tenant_invitation.go#L386)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |
| inv_id | path | 是 | string | 邀请 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-35) · [返回总目录](#index)

<a id="api-300"></a>

### POST `/api/v1/tenants/{id}/invite-links`

生成共享邀请链接

生成一条多次使用的共享邀请链接：谁拿到链接谁就能注册并加入当前空间。
链接持续有效，直到过期或被撤销。

实现：[CreateInviteLink](../../internal/handler/tenant_invite_link.go#L57)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 空间 ID |
| request | body | 是 | [internal_handler.createInviteLinkRequest](#model-080) | 共享链接配置 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | object |

[返回模块目录](#group-35) · [返回总目录](#index)

<a id="group-36"></a>

## 系统

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/system/capabilities`](#api-301) | 获取部署能力清单 |
| POST | [`/api/v1/system/docreader/reconnect`](#api-302) | 重连文档解析服务 |
| GET | [`/api/v1/system/info`](#api-303) | 获取系统信息 |
| GET | [`/api/v1/system/parser-engines`](#api-304) | 列出可用的文档解析引擎 |
| POST | [`/api/v1/system/parser-engines/check`](#api-305) | 使用当前参数检测解析引擎可用性 |
| POST | [`/api/v1/system/sandbox-check`](#api-306) | 测试沙箱连通性 |
| POST | [`/api/v1/system/storage-engine-check`](#api-307) | 测试存储引擎连通性 |
| GET | [`/api/v1/system/storage-engine-status`](#api-308) | 获取存储引擎状态 |

<a id="api-301"></a>

### GET `/api/v1/system/capabilities`

获取部署能力清单

返回当前部署版本及实际注册的后端路由所对应的功能能力；仅 supported=false 表示入口应隐藏

实现：[GetDeploymentCapabilities](../../internal/handler/deployment_capabilities.go#L130)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 标准 code/msg/data 包装，data 为 DeploymentCapabilitiesData | object |

[返回模块目录](#group-36) · [返回总目录](#index)

<a id="api-302"></a>

### POST `/api/v1/system/docreader/reconnect`

重连文档解析服务

实现：[ReconnectDocReader](../../internal/handler/system.go#L426)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | DocReader 地址 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | 未声明 |

[返回模块目录](#group-36) · [返回总目录](#index)

<a id="api-303"></a>

### GET `/api/v1/system/info`

获取系统信息

获取系统版本、构建信息和引擎配置

实现：[GetSystemInfo](../../internal/handler/system.go#L314)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 系统信息 | [internal_handler.GetSystemInfoResponse](#model-031) |

[返回模块目录](#group-36) · [返回总目录](#index)

<a id="api-304"></a>

### GET `/api/v1/system/parser-engines`

列出可用的文档解析引擎

实现：[ListParserEngines](../../internal/handler/system.go#L395)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 解析引擎列表 | object |

[返回模块目录](#group-36) · [返回总目录](#index)

<a id="api-305"></a>

### POST `/api/v1/system/parser-engines/check`

使用当前参数检测解析引擎可用性

实现：[CheckParserEngines](../../internal/handler/system.go#L487)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| body | body | 是 | object | 解析引擎配置（与保存接口同结构） |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | 未声明 |

[返回模块目录](#group-36) · [返回总目录](#index)

<a id="api-306"></a>

### POST `/api/v1/system/sandbox-check`

测试沙箱连通性

使用当前填写的参数测试沙箱后端，不保存配置；deep=true 会执行临时脚本，远端后端还会创建并销毁一个沙箱

实现：[CheckSandboxConfig](../../internal/handler/sandbox_check.go#L88)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| body | body | 是 | [internal_handler.SandboxCheckRequest](#model-053) | 沙箱配置 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.SandboxCheckResponse](#model-054) |

[返回模块目录](#group-36) · [返回总目录](#index)

<a id="api-307"></a>

### POST `/api/v1/system/storage-engine-check`

测试存储引擎连通性

使用当前填写的参数测试 MinIO/COS 连通性，不保存配置

实现：[CheckStorageEngine](../../internal/handler/system.go#L908)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| body | body | 是 | [internal_handler.StorageCheckRequest](#model-057) | 存储引擎配置 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.StorageCheckResponse](#model-058) |

[返回模块目录](#group-36) · [返回总目录](#index)

<a id="api-308"></a>

### GET `/api/v1/system/storage-engine-status`

获取存储引擎状态

返回 Local、MinIO、COS 各存储引擎的可用状态及说明，供全局设置与知识库选择使用

实现：[GetStorageEngineStatus](../../internal/handler/system.go#L793)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.GetStorageEngineStatusResponse](#model-030) |

[返回模块目录](#group-36) · [返回总目录](#index)

<a id="group-37"></a>

## 系统管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/system/admin/runtime/queues`](#api-309) | 获取解析任务队列运行时状态 |

<a id="api-309"></a>

### GET `/api/v1/system/admin/runtime/queues`

获取解析任务队列运行时状态

返回各 asynq 队列的实时深度（pending/active/scheduled/retry 等）与 worker 并发配置，仅系统管理员可见

实现：[GetRuntimeQueues](../../internal/handler/system.go#L1782)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.RuntimeQueuesResponse](#model-049) |

[返回模块目录](#group-37) · [返回总目录](#index)

<a id="group-38"></a>

## 组织

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| DELETE | [`/api/v1/agents/{id}/shares/{share_id}`](#api-310) | 取消智能体共享 |
| GET | [`/api/v1/organizations/{id}/agent-shares`](#api-311) | 获取共享到本组织的智能体 |
| GET | [`/api/v1/shared-agents`](#api-312) | 获取我可访问的共享智能体 |

<a id="api-310"></a>

### DELETE `/api/v1/agents/{id}/shares/{share_id}`

取消智能体共享

从智能体的共享列表中移除指定共享关系

实现：[RemoveAgentShare](../../internal/handler/organization.go#L1461)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 智能体 ID |
| share_id | path | 是 | string | 共享记录 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | success: true | object |
| 403 | 无权限 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-38) · [返回总目录](#index)

<a id="api-311"></a>

### GET `/api/v1/organizations/{id}/agent-shares`

获取共享到本组织的智能体

返回所有被共享到指定组织的智能体（含我的有效权限）

实现：[ListOrgAgentShares](../../internal/handler/organization.go#L1491)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 智能体共享列表 + total | object |
| 403 | 非组织成员 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-38) · [返回总目录](#index)

<a id="api-312"></a>

### GET `/api/v1/shared-agents`

获取我可访问的共享智能体

返回所有共享给当前用户所在组织的智能体

实现：[ListSharedAgents](../../internal/handler/organization.go#L1567)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 智能体列表 + total | object |
| 500 | 服务器错误 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-38) · [返回总目录](#index)

<a id="group-39"></a>

## 组织管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/organizations`](#api-313) | 获取我的组织列表 |
| POST | [`/api/v1/organizations`](#api-314) | 创建组织 |
| POST | [`/api/v1/organizations/join`](#api-315) | 通过邀请码加入组织 |
| POST | [`/api/v1/organizations/join-by-id`](#api-316) | 通过空间 ID 加入（可搜索空间） |
| POST | [`/api/v1/organizations/join-request`](#api-317) | 提交加入申请 |
| GET | [`/api/v1/organizations/preview/{code}`](#api-318) | 通过邀请码预览组织 |
| GET | [`/api/v1/organizations/search`](#api-319) | 搜索可加入的空间 |
| DELETE | [`/api/v1/organizations/{id}`](#api-320) | 删除组织 |
| GET | [`/api/v1/organizations/{id}`](#api-321) | 获取组织详情 |
| PUT | [`/api/v1/organizations/{id}`](#api-322) | 更新组织 |
| POST | [`/api/v1/organizations/{id}/invite`](#api-323) | 邀请成员 |
| POST | [`/api/v1/organizations/{id}/invite-code`](#api-324) | 生成邀请码 |
| GET | [`/api/v1/organizations/{id}/join-requests`](#api-325) | 获取待审核加入申请列表 |
| PUT | [`/api/v1/organizations/{id}/join-requests/{request_id}/review`](#api-326) | 审核加入申请 |
| POST | [`/api/v1/organizations/{id}/leave`](#api-327) | 退出组织 |
| GET | [`/api/v1/organizations/{id}/members`](#api-328) | 获取组织成员列表 |
| DELETE | [`/api/v1/organizations/{id}/members/{tenant_id}`](#api-329) | 移除成员 |
| PUT | [`/api/v1/organizations/{id}/members/{tenant_id}`](#api-330) | 更新成员角色 |
| POST | [`/api/v1/organizations/{id}/request-upgrade`](#api-331) | 申请权限升级 |
| GET | [`/api/v1/organizations/{id}/search-tenants`](#api-332) | Resolve a workspace ID for invitation |
| GET | [`/api/v1/organizations/{id}/shared-agents`](#api-333) | 获取空间内全部智能体（含我共享的） |
| GET | [`/api/v1/organizations/{id}/shared-knowledge-bases`](#api-334) | 获取空间内全部知识库（含我共享的、含智能体携带的） |
| GET | [`/api/v1/organizations/{id}/shares`](#api-335) | 获取组织的共享知识库列表 |

<a id="api-313"></a>

### GET `/api/v1/organizations`

获取我的组织列表

获取当前空间所属的所有组织，并附带各空间内知识库/智能体数量

实现：[ListMyOrganizations](../../internal/handler/organization.go#L149)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.ListOrganizationsResponse](#model-158) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-314"></a>

### POST `/api/v1/organizations`

创建组织

创建新的组织，创建者自动成为管理员

实现：[CreateOrganization](../../internal/handler/organization.go#L62)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.CreateOrganizationRequest](#model-117) | 组织信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | object |
| 400 | Bad Request | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-315"></a>

### POST `/api/v1/organizations/join`

通过邀请码加入组织

使用邀请码加入组织

实现：[JoinByInviteCode](../../internal/handler/organization.go#L614)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.JoinOrganizationRequest](#model-147) | 邀请码 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-316"></a>

### POST `/api/v1/organizations/join-by-id`

通过空间 ID 加入（可搜索空间）

加入已开放可被搜索的空间，无需邀请码

实现：[JoinByOrganizationID](../../internal/handler/organization.go#L761)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.JoinByOrganizationIDRequest](#model-146) | 空间 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-317"></a>

### POST `/api/v1/organizations/join-request`

提交加入申请

对需要审核的组织提交加入申请

实现：[SubmitJoinRequest](../../internal/handler/organization.go#L655)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.SubmitJoinRequestRequest](#model-220) | 申请信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 400 | Bad Request | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-318"></a>

### GET `/api/v1/organizations/preview/{code}`

通过邀请码预览组织

通过邀请码获取组织基本信息（不加入）

实现：[PreviewByInviteCode](../../internal/handler/organization.go#L559)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| code | path | 是 | string | 邀请码 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-319"></a>

### GET `/api/v1/organizations/search`

搜索可加入的空间

搜索已开放可被搜索的空间，用于发现并加入

实现：[SearchOrganizations](../../internal/handler/organization.go#L728)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| q | query | 否 | string | 搜索关键词（空间名称或描述） |
| limit | query | 否 | integer | 返回数量限制；默认值：20 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-320"></a>

### DELETE `/api/v1/organizations/{id}`

删除组织

删除组织（仅组织创建者可操作）

实现：[DeleteOrganization](../../internal/handler/organization.go#L344)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-321"></a>

### GET `/api/v1/organizations/{id}`

获取组织详情

根据ID获取组织详情

实现：[GetOrganization](../../internal/handler/organization.go#L104)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-322"></a>

### PUT `/api/v1/organizations/{id}`

更新组织

更新组织信息（需要管理员权限）

实现：[UpdateOrganization](../../internal/handler/organization.go#L298)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |
| request | body | 是 | [types.UpdateOrganizationRequest](#model-233) | 更新信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-323"></a>

### POST `/api/v1/organizations/{id}/invite`

邀请成员

管理员直接添加用户为组织成员

实现：[InviteMember](../../internal/handler/organization.go#L1978)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |
| request | body | 是 | [types.InviteMemberRequest](#model-145) | 邀请信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 400 | Bad Request | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-324"></a>

### POST `/api/v1/organizations/{id}/invite-code`

生成邀请码

生成新的组织邀请码（需要管理员权限）

实现：[GenerateInviteCode](../../internal/handler/organization.go#L524)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-325"></a>

### GET `/api/v1/organizations/{id}/join-requests`

获取待审核加入申请列表

获取组织的待审核加入申请（仅管理员）

实现：[ListJoinRequests](../../internal/handler/organization.go#L922)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-326"></a>

### PUT `/api/v1/organizations/{id}/join-requests/{request_id}/review`

审核加入申请

通过或拒绝加入申请（仅管理员）

实现：[ReviewJoinRequest](../../internal/handler/organization.go#L989)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |
| request_id | path | 是 | string | 申请ID |
| request | body | 是 | [types.ReviewJoinRequestRequest](#model-203) | 审核结果 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-327"></a>

### POST `/api/v1/organizations/{id}/leave`

退出组织

退出指定组织

实现：[LeaveOrganization](../../internal/handler/organization.go#L877)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-328"></a>

### GET `/api/v1/organizations/{id}/members`

获取组织成员列表

获取组织的所有成员（按空间）

实现：[ListMembers](../../internal/handler/organization.go#L372)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.ListMembersResponse](#model-157) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-329"></a>

### DELETE `/api/v1/organizations/{id}/members/{tenant_id}`

移除成员

从组织中移除成员空间（需要管理员权限）

实现：[RemoveMember](../../internal/handler/organization.go#L489)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：注释未声明。

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |
| tenant_id | path | 是 | string | 成员空间ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-330"></a>

### PUT `/api/v1/organizations/{id}/members/{tenant_id}`

更新成员角色

更新组织成员（空间）的角色（需要管理员权限）

实现：[UpdateMemberRole](../../internal/handler/organization.go#L445)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |
| tenant_id | path | 是 | string | 成员空间ID |
| request | body | 是 | [types.UpdateMemberRoleRequest](#model-232) | 角色信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 403 | Forbidden | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-331"></a>

### POST `/api/v1/organizations/{id}/request-upgrade`

申请权限升级

现有成员申请更高权限

实现：[RequestRoleUpgrade](../../internal/handler/organization.go#L816)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |
| request | body | 是 | [types.RequestRoleUpgradeRequest](#model-196) | 申请信息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 400 | Bad Request | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-332"></a>

### GET `/api/v1/organizations/{id}/search-tenants`

Resolve a workspace ID for invitation

实现：[SearchTenantsForInvite](../../internal/handler/organization.go#L1905)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |
| q | query | 是 | string | Exact workspace ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-333"></a>

### GET `/api/v1/organizations/{id}/shared-agents`

获取空间内全部智能体（含我共享的）

获取指定空间下所有共享智能体，包含他人共享的与我共享的，用于列表页空间视角

实现：[ListOrganizationSharedAgents](../../internal/handler/organization.go#L1765)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-334"></a>

### GET `/api/v1/organizations/{id}/shared-knowledge-bases`

获取空间内全部知识库（含我共享的、含智能体携带的）

获取指定空间下所有共享知识库，包含直接共享的与通过共享智能体可见的，用于列表页空间视角

实现：[ListOrganizationSharedKnowledgeBases](../../internal/handler/organization.go#L1715)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="api-335"></a>

### GET `/api/v1/organizations/{id}/shares`

获取组织的共享知识库列表

获取共享到指定组织的所有知识库

实现：[ListOrgShares](../../internal/handler/organization.go#L1264)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 组织ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.ListSharesResponse](#model-159) |

[返回模块目录](#group-39) · [返回总目录](#index)

<a id="group-40"></a>

## 网络搜索

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/web-search-providers/test`](#api-336) | 使用原始凭证测试 Provider（不落库） |
| GET | [`/api/v1/web-search-providers/types`](#api-337) | 获取网络搜索 Provider 类型元数据 |
| DELETE | [`/api/v1/web-search-providers/{id}`](#api-338) | 删除网络搜索 Provider |
| GET | [`/api/v1/web-search-providers/{id}`](#api-339) | 获取网络搜索 Provider 详情 |
| PUT | [`/api/v1/web-search-providers/{id}`](#api-340) | 更新网络搜索 Provider |
| POST | [`/api/v1/web-search-providers/{id}/test`](#api-341) | 测试已保存的 Provider |
| GET | [`/api/v1/web-search/providers`](#api-342) | 获取可用网络搜索 Provider 列表 |

<a id="api-336"></a>

### POST `/api/v1/web-search-providers/test`

使用原始凭证测试 Provider（不落库）

使用前端表单中尚未保存的凭证测试连通性，用于"测试连接"按钮

实现：[TestProviderRaw](../../internal/handler/web_search_provider.go#L373)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.TestProviderRequest](#model-061) | {provider, parameters} |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 测试结果 | object |
| 400 | 请求参数错误 | object |

[返回模块目录](#group-40) · [返回总目录](#index)

<a id="api-337"></a>

### GET `/api/v1/web-search-providers/types`

获取网络搜索 Provider 类型元数据

返回 UI 表单需要的 provider 类型及参数定义

实现：[ListProviderTypes](../../internal/handler/web_search_provider.go#L311)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | provider 类型列表 | object |

[返回模块目录](#group-40) · [返回总目录](#index)

<a id="api-338"></a>

### DELETE `/api/v1/web-search-providers/{id}`

删除网络搜索 Provider

删除指定 provider 配置

实现：[DeleteProvider](../../internal/handler/web_search_provider.go#L272)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Provider ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | success: true | object |
| 404 | Provider 不存在 | object |

[返回模块目录](#group-40) · [返回总目录](#index)

<a id="api-339"></a>

### GET `/api/v1/web-search-providers/{id}`

获取网络搜索 Provider 详情

根据 ID 获取指定 provider 配置

实现：[GetProvider](../../internal/handler/web_search_provider.go#L141)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Provider ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | Provider 详情 | [types.WebSearchProviderEntity](#model-243) |
| 404 | Provider 不存在 | object |

[返回模块目录](#group-40) · [返回总目录](#index)

<a id="api-340"></a>

### PUT `/api/v1/web-search-providers/{id}`

更新网络搜索 Provider

更新指定 provider 的名称/描述/参数/是否默认

实现：[UpdateProvider](../../internal/handler/web_search_provider.go#L176)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Provider ID |
| request | body | 是 | [internal_handler.UpdateProviderRequest](#model-069) | 更新字段 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的 Provider | [types.WebSearchProviderEntity](#model-243) |
| 400 | 请求参数错误 | object |
| 404 | Provider 不存在 | object |

[返回模块目录](#group-40) · [返回总目录](#index)

<a id="api-341"></a>

### POST `/api/v1/web-search-providers/{id}/test`

测试已保存的 Provider

使用数据库中已保存的凭证测试连通性

实现：[TestProviderByID](../../internal/handler/web_search_provider.go#L329)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | Provider ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 测试结果 | object |
| 404 | Provider 不存在 | object |

[返回模块目录](#group-40) · [返回总目录](#index)

<a id="api-342"></a>

### GET `/api/v1/web-search/providers`

获取可用网络搜索 Provider 列表

返回所有已注册的网络搜索 provider（含元数据）

实现：[GetProviders](../../internal/handler/web_search.go#L18)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | provider 列表 | object |

[返回模块目录](#group-40) · [返回总目录](#index)

<a id="group-41"></a>

## 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/auth/auto-setup`](#api-343) | 自动初始化（Lite 桌面版） |
| POST | [`/api/v1/auth/change-password`](#api-344) | 修改密码 |
| GET | [`/api/v1/auth/config`](#api-345) | 获取认证配置 |
| POST | [`/api/v1/auth/invitations/lookup`](#api-346) | 解析共享邀请链接 token |
| POST | [`/api/v1/auth/login`](#api-347) | 用户登录 |
| POST | [`/api/v1/auth/logout`](#api-348) | 用户登出 |
| GET | [`/api/v1/auth/me`](#api-349) | 获取当前用户信息 |
| PUT | [`/api/v1/auth/me/preferences`](#api-350) | 更新当前用户的个性化设置 |
| GET | [`/api/v1/auth/oidc/callback`](#api-351) | OIDC登录重定向回调 |
| GET | [`/api/v1/auth/oidc/config`](#api-352) | 获取OIDC登录配置 |
| GET | [`/api/v1/auth/oidc/start`](#api-353) | 发起 OIDC 登录（直接 302） |
| GET | [`/api/v1/auth/oidc/url`](#api-354) | 获取OIDC授权地址 |
| POST | [`/api/v1/auth/refresh`](#api-355) | 刷新令牌 |
| POST | [`/api/v1/auth/register`](#api-356) | 用户注册 |
| POST | [`/api/v1/auth/register-by-invite`](#api-357) | 使用共享链接注册 |
| POST | [`/api/v1/auth/switch-tenant`](#api-358) | 切换激活空间 |
| GET | [`/api/v1/auth/validate`](#api-359) | 验证令牌 |

<a id="api-343"></a>

### POST `/api/v1/auth/auto-setup`

自动初始化（Lite 桌面版）

Lite 版专用：首次启动时自动创建默认用户和空间并返回令牌，首次及后续启动均须通过桌面原生凭据认证，免除手动注册/登录流程

实现：[AutoSetup](../../internal/handler/auth.go#L891)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.LoginResponse](#model-162) |
| 403 | 非 Lite 版本 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-344"></a>

### POST `/api/v1/auth/change-password`

修改密码

修改当前用户的登录密码。新密码须满足 8–32 位且同时包含字母与数字；开启复杂密码后还需包含大小写与特殊字符。成功后所有会话被撤销，需重新登录。

实现：[ChangePassword](../../internal/handler/auth.go#L739)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 密码修改请求 |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "new_password": {
      "type": "string"
    },
    "old_password": {
      "type": "string"
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 修改成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-345"></a>

### GET `/api/v1/auth/config`

获取认证配置

返回当前部署的注册模式与密码复杂度开关，供前端决定是否展示注册入口以及密码校验规则

实现：[GetAuthConfig](../../internal/handler/auth.go#L812)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 认证配置 | object |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-346"></a>

### POST `/api/v1/auth/invitations/lookup`

解析共享邀请链接 token

根据邀请链接中的 token 返回邀请上下文（空间名 / 角色 / 过期时间），
供注册页展示。无认证；token 无效或被撤销返回 410。
使用 POST + body 而非 GET + path，避免 token 落入访问日志 / 浏览器历史 / tracing。

实现：[LookupInvitationByToken](../../internal/handler/auth_register_by_invite.go#L52)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.invitationLookupRequest](#model-082) | 邀请 token |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [internal_handler.invitationLookupResponse](#model-083) |
| 410 | 链接无效或已撤销 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-347"></a>

### POST `/api/v1/auth/login`

用户登录

用户登录并获取访问令牌

实现：[Login](../../internal/handler/auth.go#L249)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.LoginRequest](#model-161) | 登录请求参数 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.LoginResponse](#model-162) |
| 401 | 认证失败 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-348"></a>

### POST `/api/v1/auth/logout`

用户登出

撤销当前访问令牌并登出

实现：[Logout](../../internal/handler/auth.go#L511)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 登出成功 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-349"></a>

### GET `/api/v1/auth/me`

获取当前用户信息

获取当前登录用户的详细信息

实现：[GetCurrentUser](../../internal/handler/auth.go#L607)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 用户信息 | object |
| 401 | 未授权 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-350"></a>

### PUT `/api/v1/auth/me/preferences`

更新当前用户的个性化设置

按 PATCH 语义合并用户偏好（仅覆盖请求体里出现的字段，其余字段保持不变），
数据存放在 users.preferences (JSON)，跨设备/浏览器自动同步。

实现：[UpdateMyPreferences](../../internal/handler/auth.go#L691)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.updateMyPreferencesRequest](#model-094) | Preferences patch |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的偏好 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 401 | 未授权 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-351"></a>

### GET `/api/v1/auth/oidc/callback`

OIDC登录重定向回调

接收OIDC provider回调并由后端完成code交换，随后重定向回前端登录页

实现：[OIDCRedirectCallback](../../internal/handler/auth.go#L406)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| code | query | 否 | string | OIDC授权码 |
| state | query | 否 | string | OIDC状态 |
| error | query | 否 | string | OIDC错误码 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 302 | Found | 未声明 |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-352"></a>

### GET `/api/v1/auth/oidc/config`

获取OIDC登录配置

返回OIDC是否启用以及provider展示名称，供前端决定是否展示OIDC登录入口

实现：[GetOIDCConfig](../../internal/handler/auth.go#L382)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.OIDCConfigResponse](#model-183) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-353"></a>

### GET `/api/v1/auth/oidc/start`

发起 OIDC 登录（直接 302）

与 /auth/oidc/url 不同，此端点直接 302 重定向到 OIDC Provider 的授权页，
无需前端 JS 介入。适用于外部平台（如企业门户）直接给出一个链接即可
触发 OIDC 授权码流程，借助 IdP 的 SSO session 实现免再次输密码。

实现：[OIDCStart](../../internal/handler/auth.go#L361)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：注释未声明。
响应格式：注释未声明。

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 302 | Found | 未声明 |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-354"></a>

### GET `/api/v1/auth/oidc/url`

获取OIDC授权地址

根据后端OIDC配置生成第三方登录跳转地址

实现：[GetOIDCAuthorizationURL](../../internal/handler/auth.go#L303)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| redirect_uri | query | 是 | string | OIDC回调地址 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.OIDCAuthURLResponse](#model-182) |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | OIDC未启用 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-355"></a>

### POST `/api/v1/auth/refresh`

刷新令牌

使用刷新令牌获取新的访问令牌

实现：[RefreshToken](../../internal/handler/auth.go#L563)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 刷新令牌 |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "refreshToken": {
      "type": "string"
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 新令牌 | object |
| 401 | 令牌无效 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-356"></a>

### POST `/api/v1/auth/register`

用户注册

注册新用户账号

实现：[Register](../../internal/handler/auth.go#L168)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [types.RegisterRequest](#model-194) | 注册请求参数 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | [types.RegisterResponse](#model-195) |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 403 | 注册功能已禁用 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-357"></a>

### POST `/api/v1/auth/register-by-invite`

使用共享链接注册

通过共享邀请链接 token 完成注册，仅 self_serve / invite_register 模式允许。
注册者自填邮箱（与 token 不绑定）；注册成功后自动加入对应空间。

实现：[RegisterByInvite](../../internal/handler/auth_register_by_invite.go#L109)。

认证：注释未声明；请以路由、中间件和 Handler 的鉴权为准。

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.registerByInviteRequest](#model-086) | 邀请注册请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 201 | Created | [types.LoginResponse](#model-162) |
| 400 | 请求参数错误 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 403 | 注册功能已禁用 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 409 | 邮箱已注册 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |
| 410 | 链接无效或已撤销 | [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-358"></a>

### POST `/api/v1/auth/switch-tenant`

切换激活空间

为当前用户在目标空间重新签发访问令牌；要求该用户在目标空间存在 active 成员关系（跨租户超级用户除外）。
成功换签会把目标空间写入「最近活跃租户」偏好，下次登录与 refresh 都落在该空间（refresh JWT 不含 tenant_id）。
该偏好是账号级的：一次换签会改变该用户所有设备的下次登录/refresh 落点。偏好写入失败则整次换签失败，不会发出新 token。

实现：[SwitchTenant](../../internal/handler/auth.go#L842)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 切换请求 |

内联请求结构（`request`）：

```json
{
  "type": "object",
  "properties": {
    "refresh_token": {
      "type": "string"
    },
    "tenant_id": {
      "type": "integer"
    }
  }
}
```

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | [types.LoginResponse](#model-162) |
| 400 | 参数错误 | [errors.AppError](#model-005) |
| 403 | 无该空间成员关系或偏好写入失败 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="api-359"></a>

### GET `/api/v1/auth/validate`

验证令牌

验证访问令牌是否有效

实现：[ValidateToken](../../internal/handler/auth.go#L984)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 令牌有效 | object |
| 401 | 令牌无效 | [errors.AppError](#model-005) |

[返回模块目录](#group-41) · [返回总目录](#index)

<a id="group-42"></a>

## 评估

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | [`/api/v1/evaluation/`](#api-360) | 获取评估结果 |
| POST | [`/api/v1/evaluation/`](#api-361) | 执行评估 |

<a id="api-360"></a>

### GET `/api/v1/evaluation/`

获取评估结果

根据任务ID获取评估结果

实现：[GetEvaluationResult](../../internal/handler/evaluation.go#L95)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| task_id | query | 是 | string | 评估任务ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 评估结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-42) · [返回总目录](#index)

<a id="api-361"></a>

### POST `/api/v1/evaluation/`

执行评估

对知识库进行评估测试

实现：[Evaluation](../../internal/handler/evaluation.go#L32)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler.EvaluationRequest](#model-028) | 评估请求参数 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 评估任务 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-42) · [返回总目录](#index)

<a id="group-43"></a>

## 长期记忆

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/memory/consolidate`](#api-362) | 立刻整理我的记忆 |
| GET | [`/api/v1/memory/documents`](#api-363) | 列出常用资料 |
| DELETE | [`/api/v1/memory/documents/{id}`](#api-364) | 停止用某份文档做个性化检索 |
| GET | [`/api/v1/memory/export`](#api-365) | 导出我的记忆 |
| DELETE | [`/api/v1/memory/items`](#api-366) | 清空我的记忆 |
| GET | [`/api/v1/memory/items`](#api-367) | 列出我的记忆 |
| POST | [`/api/v1/memory/items`](#api-368) | 新增一条记忆 |
| DELETE | [`/api/v1/memory/items/{id}`](#api-369) | 删除一条记忆 |
| PUT | [`/api/v1/memory/items/{id}`](#api-370) | 修改一条记忆 |
| POST | [`/api/v1/memory/items/{id}/confirm`](#api-371) | 确认一条推断出的记忆 |
| POST | [`/api/v1/memory/items/{id}/reject`](#api-372) | 否决一条推断出的记忆 |
| GET | [`/api/v1/memory/settings`](#api-373) | 获取我的记忆设置 |
| PUT | [`/api/v1/memory/settings`](#api-374) | 更新我的记忆设置 |
| GET | [`/api/v1/memory/topics`](#api-375) | 列出正在观察的主题 |
| DELETE | [`/api/v1/memory/topics/{id}`](#api-376) | 停止跟踪一个主题 |
| POST | [`/api/v1/memory/topics/{id}/promote`](#api-377) | 立即记为长期关注 |

<a id="api-362"></a>

### POST `/api/v1/memory/consolidate`

立刻整理我的记忆

合并意思接近的条目、归档到期事项，不等待每日后台整理

实现：[Consolidate](../../internal/handler/memory.go#L429)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 整理结果 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-363"></a>

### GET `/api/v1/memory/documents`

列出常用资料

返回当前用户回答里反复引用的文档，次数未达习惯门槛的不展示

实现：[ListDocuments](../../internal/handler/memory.go#L203)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| limit | query | 否 | integer | 每页条数；默认值：50 |
| offset | query | 否 | integer | 偏移量 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 文档列表 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-364"></a>

### DELETE `/api/v1/memory/documents/{id}`

停止用某份文档做个性化检索

删除一条文档亲和度计数，之后检索不再因为这份文档而加权

实现：[DeleteDocument](../../internal/handler/memory.go#L228)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 亲和度 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-365"></a>

### GET `/api/v1/memory/export`

导出我的记忆

以 JSON 导出当前用户的全部记忆

实现：[Export](../../internal/handler/memory.go#L384)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 记忆导出 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-366"></a>

### DELETE `/api/v1/memory/items`

清空我的记忆

永久删除当前用户的全部记忆

实现：[Clear](../../internal/handler/memory.go#L366)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 清空成功 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-367"></a>

### GET `/api/v1/memory/items`

列出我的记忆

分页返回当前用户的记忆条目，可按状态过滤

实现：[ListItems](../../internal/handler/memory.go#L86)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| status | query | 否 | string | 状态过滤；可选值：["active", "superseded", "archived", "pending"] |
| limit | query | 否 | integer | 每页条数；默认值：50 |
| offset | query | 否 | integer | 偏移量 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 记忆列表 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-368"></a>

### POST `/api/v1/memory/items`

新增一条记忆

手动添加一条长期记忆

实现：[CreateItem](../../internal/handler/memory.go#L252)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 记忆内容 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 新增的记忆 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-369"></a>

### DELETE `/api/v1/memory/items/{id}`

删除一条记忆

永久删除一条记忆

实现：[DeleteItem](../../internal/handler/memory.go#L308)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 记忆ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-370"></a>

### PUT `/api/v1/memory/items/{id}`

修改一条记忆

修改记忆内容与重要度，修改后该条记忆不会被后台抽取覆盖

实现：[UpdateItem](../../internal/handler/memory.go#L282)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 记忆ID |
| request | body | 是 | object | 记忆内容 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的记忆 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-371"></a>

### POST `/api/v1/memory/items/{id}/confirm`

确认一条推断出的记忆

接受系统推断的记忆，使其开始生效

实现：[ConfirmItem](../../internal/handler/memory.go#L326)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 记忆 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 确认成功 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-372"></a>

### POST `/api/v1/memory/items/{id}/reject`

否决一条推断出的记忆

拒绝系统推断的记忆，并记住这次拒绝

实现：[RejectItem](../../internal/handler/memory.go#L348)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 记忆 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 否决成功 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-373"></a>

### GET `/api/v1/memory/settings`

获取我的记忆设置

返回合并后的记忆开关状态（空间级 + 个人级）与记忆条数

实现：[GetSettings](../../internal/handler/memory.go#L31)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

请求参数：注释未声明。

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 记忆设置 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-374"></a>

### PUT `/api/v1/memory/settings`

更新我的记忆设置

开启或关闭当前用户自己的长期记忆

实现：[UpdateSettings](../../internal/handler/memory.go#L53)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | object | 设置 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 更新后的设置 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-375"></a>

### GET `/api/v1/memory/topics`

列出正在观察的主题

返回已计数、尚未提升为长期关注的主题，以及距离阈值还差几次

实现：[ListTopics](../../internal/handler/memory.go#L141)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| limit | query | 否 | integer | 每页条数；默认值：50 |
| offset | query | 否 | integer | 偏移量 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 主题列表 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-376"></a>

### DELETE `/api/v1/memory/topics/{id}`

停止跟踪一个主题

删除尚未提升的主题计数，并记住这次拒绝，之后不会再自动记为长期关注

实现：[DeleteTopic](../../internal/handler/memory.go#L185)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 主题 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 删除成功 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="api-377"></a>

### POST `/api/v1/memory/topics/{id}/promote`

立即记为长期关注

不等待剩余次数，把正在观察的主题提升为一条长期关注记忆

实现：[PromoteTopic](../../internal/handler/memory.go#L166)。

认证：Bearer（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 主题 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 新增的记忆 | object |

[返回模块目录](#group-43) · [返回总目录](#index)

<a id="group-44"></a>

## 问答

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | [`/api/v1/agent-chat/{session_id}`](#api-378) | Agent问答 |
| POST | [`/api/v1/knowledge-chat/{session_id}`](#api-379) | 知识问答 |
| POST | [`/api/v1/knowledge-search`](#api-380) | 知识搜索 |
| GET | [`/api/v1/sessions/continue-stream/{session_id}`](#api-381) | 继续流式响应 |
| GET | [`/api/v1/sessions/{id}/steer`](#api-382) | 列出当前运行中尚未消费的排队消息 |
| DELETE | [`/api/v1/sessions/{id}/steer/{steer_id}`](#api-383) | 删除一条排队中的消息 |
| POST | [`/api/v1/sessions/{session_id}/steer`](#api-384) | 向运行中的对话追加消息 |
| POST | [`/api/v1/sessions/{session_id}/steer/{steer_id}/inject`](#api-385) | 将排队消息改为立即注入 |
| POST | [`/api/v1/sessions/{session_id}/stop`](#api-386) | 停止生成 |

<a id="api-378"></a>

### POST `/api/v1/agent-chat/{session_id}`

Agent问答

基于Agent的智能问答，支持多轮对话和SSE流式响应

实现：[AgentQA](../../internal/handler/session/qa.go#L1009)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`text/event-stream`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |
| request | body | 是 | [internal_handler_session.CreateKnowledgeQARequest](#model-096) | 问答请求 |
| resource_urls | query | 否 | string | 文件引用形式，public 返回可加载直链；可选值：["handle", "public"]；默认值："handle" |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 问答结果（SSE流） | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="api-379"></a>

### POST `/api/v1/knowledge-chat/{session_id}`

知识问答

基于知识库的问答（使用LLM总结），支持SSE流式响应

实现：[KnowledgeQA](../../internal/handler/session/qa.go#L979)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`text/event-stream`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |
| request | body | 是 | [internal_handler_session.CreateKnowledgeQARequest](#model-096) | 问答请求 |
| resource_urls | query | 否 | string | 文件引用形式，public 返回可加载直链；可选值：["handle", "public"]；默认值："handle" |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 问答结果（SSE流） | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="api-380"></a>

### POST `/api/v1/knowledge-search`

知识搜索

在知识库中搜索（不使用LLM总结）。与产品内问答使用同一检索流程（召回、rerank、合并），外部检索首选；可覆盖召回参数与 rerank

实现：[SearchKnowledge](../../internal/handler/session/qa.go#L841)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| request | body | 是 | [internal_handler_session.SearchKnowledgeRequest](#model-103) | 搜索请求 |
| resource_urls | query | 否 | string | 文件引用形式，public 返回可加载直链；可选值：["handle", "public"]；默认值："handle" |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 搜索结果 | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="api-381"></a>

### GET `/api/v1/sessions/continue-stream/{session_id}`

继续流式响应

继续获取正在进行的流式响应

实现：[ContinueStream](../../internal/handler/session/stream.go#L21)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`text/event-stream`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |
| message_id | query | 是 | string | 消息ID |
| resource_urls | query | 否 | string | 文件引用形式，public 返回可加载直链；可选值：["handle", "public"]；默认值："handle" |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 流式响应 | object |
| 404 | 会话或消息不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="api-382"></a>

### GET `/api/v1/sessions/{id}/steer`

列出当前运行中尚未消费的排队消息

刷新页面后用来恢复输入框上方的队列。没有正在运行的 turn 时返回空列表。

实现：[ListSteerMessages](../../internal/handler/session/steer.go#L681)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 会话 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [errors.AppError](#model-005) |
| 503 | 活 turn 查询失败，可重试 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="api-383"></a>

### DELETE `/api/v1/sessions/{id}/steer/{steer_id}`

删除一条排队中的消息

从当前运行的排队列表里去掉一条，不再注入也不再作为 follow-up 发出。

实现：[DeleteSteerMessage](../../internal/handler/session/steer.go#L733)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| id | path | 是 | string | 会话 ID |
| steer_id | path | 是 | string | 排队消息 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | OK | object |
| 404 | Not Found | [errors.AppError](#model-005) |
| 503 | 活 turn 查询失败，可重试 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="api-384"></a>

### POST `/api/v1/sessions/{session_id}/steer`

向运行中的对话追加消息

向运行中的 agent turn 追加用户消息（after 排队 / inject 注入）。无活 turn 时返回 new_run。

实现：[SteerMessage](../../internal/handler/session/steer.go#L446)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话 ID |
| request | body | 是 | [internal_handler_session.SteerMessageRequest](#model-104) | 追加消息 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | queued &#124; new_run | object |
| 400 | 请求参数错误 | [errors.AppError](#model-005) |
| 404 | 会话不存在 | [errors.AppError](#model-005) |
| 503 | 活 turn 查询失败，可重试 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="api-385"></a>

### POST `/api/v1/sessions/{session_id}/steer/{steer_id}/inject`

将排队消息改为立即注入

把一条 delivery=after 的排队消息改为 inject，运行中的 agent 会在下一轮边界读到它。

实现：[PromoteSteerMessage](../../internal/handler/session/steer.go#L600)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：注释未声明。
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话 ID |
| steer_id | path | 是 | string | 排队消息 ID |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | queued &#124; new_run | object |
| 400 | Bad Request | [errors.AppError](#model-005) |
| 404 | Not Found | [errors.AppError](#model-005) |
| 503 | 活 turn 查询失败，可重试 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="api-386"></a>

### POST `/api/v1/sessions/{session_id}/stop`

停止生成

停止当前正在进行的生成任务

实现：[StopSession](../../internal/handler/session/stream.go#L207)。

认证：Bearer 或 ApiKeyAuth（同时受资源权限与 API Key capability 限制）

请求格式：`application/json`
响应格式：`application/json`

| 参数 | 位置 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- | --- |
| session_id | path | 是 | string | 会话ID |
| request | body | 是 | [internal_handler_session.StopSessionRequest](#model-105) | 停止请求 |

| HTTP 状态 | 说明 | 响应类型 |
| --- | --- | --- |
| 200 | 停止成功 | object |
| 404 | 会话或消息不存在 | [errors.AppError](#model-005) |

[返回模块目录](#group-44) · [返回总目录](#index)

<a id="unannotated"></a>

## 未被 Swagger 覆盖的路由

以下是从路由源码提取、但没有对应详细说明的声明。保留 Gin 的 `:参数` / `*通配符` 写法；这些不是额外固定前缀。部分路由仅在依赖或功能启用时注册。这里只列事实，不猜测请求体、响应或权限。点击处理函数或注册位置查看实现。

| 方法 | 路径 | 处理函数 | 注册位置 |
| --- | --- | --- | --- |
| GET | `/api/v1/agents/:id/embed-channels` | embedHandler.ListEmbedChannels | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L272) |
| POST | `/api/v1/agents/:id/embed-channels` | embedHandler.CreateEmbedChannel | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L271) |
| GET | `/api/v1/agents/:id/im-channels` | imHandler.ListIMChannels | [RegisterIMChannelRoutes](../../internal/router/routes_agent.go#L306) |
| POST | `/api/v1/agents/:id/im-channels` | imHandler.CreateIMChannel | [RegisterIMChannelRoutes](../../internal/router/routes_agent.go#L305) |
| GET | `/api/v1/agents/:id/shares` | orgHandler.ListAgentShares | [RegisterOrganizationRoutes](../../internal/router/routes_agent.go#L206) |
| POST | `/api/v1/agents/:id/shares` | orgHandler.ShareAgent | [RegisterOrganizationRoutes](../../internal/router/routes_agent.go#L205) |
| POST | `/api/v1/chunks/:knowledge_id/:id/revert` | handler.RevertChunk | [RegisterChunkRoutes](../../internal/router/routes_knowledge.go#L45) |
| GET | `/api/v1/chunks/:knowledge_id/:id/revisions` | handler.ListChunkRevisions | [RegisterChunkRoutes](../../internal/router/routes_knowledge.go#L38) |
| PUT | `/api/v1/chunks/by-id/:id/questions` | handler.UpsertGeneratedQuestion | [RegisterChunkRoutes](../../internal/router/routes_knowledge.go#L52) |
| POST | `/api/v1/chunks/by-id/:id/questions/regenerate` | handler.RegenerateGeneratedQuestions | [RegisterChunkRoutes](../../internal/router/routes_knowledge.go#L53) |
| PUT | `/api/v1/datasource/:id/credentials` | credHandler.Put | [RegisterDataSourceRoutes](../../internal/router/routes_infra.go#L322) |
| DELETE | `/api/v1/datasource/:id/credentials/:field` | credHandler.DeleteField | [RegisterDataSourceRoutes](../../internal/router/routes_infra.go#L323) |
| GET | `/api/v1/embed-channels` | embedHandler.ListAllEmbedChannels | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L276) |
| DELETE | `/api/v1/embed-channels/:channel_id` | embedHandler.DeleteEmbedChannel | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L279) |
| GET | `/api/v1/embed-channels/:channel_id` | embedHandler.GetEmbedChannel | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L277) |
| PUT | `/api/v1/embed-channels/:channel_id` | embedHandler.UpdateEmbedChannel | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L278) |
| POST | `/api/v1/embed-channels/:channel_id/preview-session` | embedHandler.IssuePreviewSession | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L281) |
| POST | `/api/v1/embed-channels/:channel_id/rotate-token` | embedHandler.RotateEmbedToken | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L280) |
| GET | `/api/v1/embed-channels/:channel_id/stats` | embedHandler.GetEmbedChannelStats | [RegisterEmbedChannelRoutes](../../internal/router/routes_agent.go#L282) |
| GET | `/api/v1/embed-frame-policy` | embedFramePolicyHandler(embedService) | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L236) |
| POST | `/api/v1/embed/:channel_id/agent-chat/:session_id` | embedHandler.EmbedAgentChat | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L245) |
| GET | `/api/v1/embed/:channel_id/chunks/:chunk_id` | embedHandler.GetEmbedChunk | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L242) |
| GET | `/api/v1/embed/:channel_id/config` | embedHandler.GetEmbedConfig | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L240) |
| POST | `/api/v1/embed/:channel_id/exchange` | embedHandler.ExchangeEmbedSession | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L239) |
| GET | `/api/v1/embed/:channel_id/files` | newFileServeHandler(fileService, storageResolver, resourceCatalogs...) | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L260) |
| POST | `/api/v1/embed/:channel_id/knowledge-chat/:session_id` | embedHandler.EmbedKnowledgeChat | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L244) |
| GET | `/api/v1/embed/:channel_id/messages/:session_id/load` | embedHandler.EmbedLoadMessages | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L246) |
| POST | `/api/v1/embed/:channel_id/sessions` | embedHandler.CreateEmbedSession | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L243) |
| POST | `/api/v1/embed/:channel_id/sessions/:session_id/events` | embedHandler.EmbedRelayWebhookEvent | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L251) |
| POST | `/api/v1/embed/:channel_id/sessions/:session_id/mcp-oauth-resolutions/:pending_id` | embedHandler.EmbedResolveMCPOAuth | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L252) |
| POST | `/api/v1/embed/:channel_id/sessions/:session_id/mcp-oauth-resolutions/:pending_id/cancel` | embedHandler.EmbedCancelMCPOAuth | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L253) |
| POST | `/api/v1/embed/:channel_id/sessions/:session_id/mcp-services/:id/oauth/authorize-url` | embedHandler.EmbedMCPOAuthAuthorizeURL | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L254) |
| GET | `/api/v1/embed/:channel_id/sessions/:session_id/mcp-services/:id/oauth/status` | embedHandler.EmbedMCPOAuthStatus | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L255) |
| GET | `/api/v1/embed/:channel_id/sessions/:session_id/messages/:message_id/suggestions` | embedHandler.EmbedGetMessageSuggestions | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L248) |
| POST | `/api/v1/embed/:channel_id/sessions/:session_id/messages/:message_id/suggestions` | embedHandler.EmbedEnsureMessageSuggestions | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L249) |
| POST | `/api/v1/embed/:channel_id/sessions/:session_id/stop` | embedHandler.EmbedStopSession | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L247) |
| POST | `/api/v1/embed/:channel_id/sessions/:session_id/suggestion-events` | embedHandler.EmbedRecordSuggestionEvent | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L250) |
| POST | `/api/v1/embed/:channel_id/sessions/:session_id/tool-approvals/:pending_id` | embedHandler.EmbedResolveToolApproval | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L256) |
| GET | `/api/v1/embed/:channel_id/suggested-questions` | embedHandler.GetEmbedSuggestedQuestions | [RegisterEmbedPublicRoutes](../../internal/router/routes_agent.go#L241) |
| GET | `/api/v1/files/presigned` | handler | [servePresignedFiles](../../internal/router/files.go#L536) |
| HEAD | `/api/v1/files/presigned` | handler | [servePresignedFiles](../../internal/router/files.go#L537) |
| GET | `/api/v1/im-channels` | imHandler.ListAllIMChannels | [RegisterIMChannelRoutes](../../internal/router/routes_agent.go#L312) |
| GET | `/api/v1/image-attrs/schema` | handler.GetImageAttrsSchema | [RegisterImageAttrRoutes](../../internal/router/routes_knowledge.go#L279) |
| GET | `/api/v1/knowledge-bases/:id/files` | inline handler | [serveKBScopedFiles](../../internal/router/files.go#L336) |
| GET | `/api/v1/knowledge-bases/:id/gallery-config` | handler.GetGalleryConfig | [RegisterKnowledgeRoutes](../../internal/router/routes_knowledge.go#L97) |
| POST | `/api/v1/knowledge/:id/regenerate-summary` | handler.RegenerateKnowledgeSummary | [RegisterKnowledgeRoutes](../../internal/router/routes_knowledge.go#L126) |
| GET | `/api/v1/knowledge/:id/stages` | handler.GetKnowledgeSpans | [RegisterKnowledgeRoutes](../../internal/router/routes_knowledge.go#L120) |
| GET | `/api/v1/local-browser/extension` | params.SessionHandler.BrowserSkillExtension | [NewRouter](../../internal/router/router.go#L203) |
| POST | `/api/v1/local-browser/extension/authorize` | params.SessionHandler.BrowserSkillAuthorize | [NewRouter](../../internal/router/router.go#L204) |
| POST | `/api/v1/local-browser/internal` | params.SessionHandler.BrowserSkillInternal | [NewRouter](../../internal/router/router.go#L205) |
| GET | `/api/v1/mcp-services/:id/tool-approvals` | handler.ListMCPToolApprovals | [RegisterMCPServiceRoutes](../../internal/router/routes_infra.go#L183) |
| POST | `/api/v1/mcp-services/:id/usage-instructions/generate` | handler.GenerateMCPUsageInstructions | [RegisterMCPServiceRoutes](../../internal/router/routes_infra.go#L175) |
| GET | `/api/v1/me/browser` | params.SessionHandler.BrowserSkillAccount | [NewRouter](../../internal/router/router.go#L305) |
| POST | `/api/v1/me/browser` | params.SessionHandler.BrowserSkillAccount | [NewRouter](../../internal/router/router.go#L307) |
| GET | `/api/v1/me/browser/extension` | params.SessionHandler.BrowserSkillDownload | [NewRouter](../../internal/router/router.go#L306) |
| PUT | `/api/v1/models/:id/credentials` | credHandler.Put | [RegisterModelRoutes](../../internal/router/routes_infra.go#L43) |
| DELETE | `/api/v1/models/:id/credentials/:field` | credHandler.DeleteField | [RegisterModelRoutes](../../internal/router/routes_infra.go#L44) |
| POST | `/api/v1/models/:id/debug` | handler.DebugModel | [RegisterModelRoutes](../../internal/router/routes_infra.go#L33) |
| POST | `/api/v1/models/catalog/resolve` | handler.ResolveModelCatalog | [RegisterModelRoutes](../../internal/router/routes_infra.go#L27) |
| GET | `/api/v1/sandbox-configs/:id/skills/:skillId/guidance` | skills.InstallGuidance | [RegisterSandboxConfigRoutes](../../internal/router/routes_infra.go#L76) |
| POST | `/api/v1/sandbox-configs/:id/skills/:skillId/guidance` | skills.SteerInstall | [RegisterSandboxConfigRoutes](../../internal/router/routes_infra.go#L77) |
| POST | `/api/v1/sandbox-configs/templates/query` | h.QueryTemplates | [RegisterSandboxConfigRoutes](../../internal/router/routes_infra.go#L61) |
| PUT | `/api/v1/sandbox-configs/workspace-policy` | h.SetWorkspacePolicy | [RegisterSandboxConfigRoutes](../../internal/router/routes_infra.go#L60) |
| GET | `/api/v1/sessions/:id/attachments` | handler.ListTemporaryDocuments | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L63) |
| DELETE | `/api/v1/sessions/:id/attachments/:attachment_id` | handler.DeleteTemporaryDocument | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L66) |
| GET | `/api/v1/sessions/:id/attachments/:attachment_id` | handler.GetTemporaryDocument | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L64) |
| GET | `/api/v1/sessions/:id/attachments/:attachment_id/preview` | handler.PreviewTemporaryDocument | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L65) |
| GET | `/api/v1/sessions/:id/local-browser` | handler.BrowserSkillConnection | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L73) |
| GET | `/api/v1/sessions/:id/messages/:message_id/files` | inline handler | [serveMessageScopedFiles](../../internal/router/files.go#L498) |
| GET | `/api/v1/sessions/:id/sandbox/desktop` | sessionHandler.SandboxDesktopWS | [RegisterSandboxDesktopRoutes](../../internal/router/routes_chat.go#L182) |
| GET | `/api/v1/sessions/:id/sandbox/terminal` | sessionHandler.SandboxTerminalWS | [RegisterSandboxTerminalRoutes](../../internal/router/routes_chat.go#L169) |
| POST | `/api/v1/sessions/:session_id/attachments` | handler.UploadTemporaryDocument | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L62) |
| POST | `/api/v1/sessions/:session_id/local-browser` | handler.BrowserSkillConnection | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L74) |
| POST | `/api/v1/sessions/:session_id/sandbox/desktop-ticket` | handler.IssueSandboxDesktopTicket | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L71) |
| POST | `/api/v1/sessions/:session_id/sandbox/desktop/activity` | handler.ReportSandboxDesktopActivity | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L72) |
| POST | `/api/v1/sessions/:session_id/sandbox/terminal-ticket` | handler.IssueSandboxTerminalTicket | [RegisterSessionRoutes](../../internal/router/routes_chat.go#L70) |
| POST | `/api/v1/shared-agents/disabled` | orgHandler.SetSharedAgentDisabledByMe | [RegisterOrganizationRoutes](../../internal/router/routes_agent.go#L217) |
| GET | `/api/v1/system/admin/model-catalog` | handler.GetModelCatalog | [RegisterSystemAdminRoutes](../../internal/router/routes_auth_tenant.go#L293) |
| PUT | `/api/v1/system/admin/model-catalog` | handler.PublishModelCatalog | [RegisterSystemAdminRoutes](../../internal/router/routes_auth_tenant.go#L295) |
| POST | `/api/v1/system/admin/model-catalog/preview` | handler.PreviewModelCatalog | [RegisterSystemAdminRoutes](../../internal/router/routes_auth_tenant.go#L294) |
| GET | `/api/v1/system/admin/settings` | handler.ListSystemSettings | [RegisterSystemAdminRoutes](../../internal/router/routes_auth_tenant.go#L311) |
| POST | `/api/v1/system/host-project-dir` | handler.PickHostProjectDir | [RegisterSystemRoutes](../../internal/router/routes_auth_tenant.go#L250) |
| GET | `/api/v1/tenants/:id/api-keys` | handler.ListAPIKeys | [RegisterTenantRoutes](../../internal/router/routes_auth_tenant.go#L97) |
| POST | `/api/v1/tenants/:id/api-keys` | handler.CreateAPIKey | [RegisterTenantRoutes](../../internal/router/routes_auth_tenant.go#L98) |
| DELETE | `/api/v1/tenants/:id/api-keys/:key_id` | handler.DeleteAPIKey | [RegisterTenantRoutes](../../internal/router/routes_auth_tenant.go#L100) |
| PUT | `/api/v1/tenants/:id/api-keys/:key_id` | handler.UpdateAPIKey | [RegisterTenantRoutes](../../internal/router/routes_auth_tenant.go#L99) |
| GET | `/api/v1/web-search-providers` | h.ListProviders | [RegisterWebSearchProviderRoutes](../../internal/router/routes_infra.go#L240) |
| POST | `/api/v1/web-search-providers` | h.CreateProvider | [RegisterWebSearchProviderRoutes](../../internal/router/routes_infra.go#L239) |
| PUT | `/api/v1/web-search-providers/:id/credentials` | credHandler.Put | [RegisterWebSearchProviderRoutes](../../internal/router/routes_infra.go#L245) |
| DELETE | `/api/v1/web-search-providers/:id/credentials/:field` | credHandler.DeleteField | [RegisterWebSearchProviderRoutes](../../internal/router/routes_infra.go#L246) |
| GET | `/health` | inline handler | [NewRouter](../../internal/router/router.go#L145) |
| DELETE | `/mcp/:endpoint_id` | h | [RegisterMCPServerRoutes](../../internal/router/routes_mcp_endpoint.go#L46) |
| GET | `/mcp/:endpoint_id` | h | [RegisterMCPServerRoutes](../../internal/router/routes_mcp_endpoint.go#L46) |
| POST | `/mcp/:endpoint_id` | h | [RegisterMCPServerRoutes](../../internal/router/routes_mcp_endpoint.go#L46) |
| GET | `/r/:token` | handler | [serveResourceGrants](../../internal/router/files.go#L312) |
| HEAD | `/r/:token` | handler | [serveResourceGrants](../../internal/router/files.go#L313) |

<a id="drift"></a>

## 源码与接口注释的路径差异

下列差异通过注释所对应的 Handler 与实际路由关联核对，本页的详细接口采用实际路径；没有修改后端行为或仓库的 Swagger 生成物。

| 方法 | 注释路径 | 实际路由 | 实现 |
| --- | --- | --- | --- |
| GET | /api/v1/mcp-services/oauth/callback | /api/v1/mcp-oauth/callback | [Callback](../../internal/handler/mcp_oauth.go#L123) |
| POST | /api/v1/sessions/{session_id}/title | /api/v1/sessions/:session_id/generate_title | [GenerateTitle](../../internal/handler/session/title.go#L12) |

另外，少数注释路径已写入 `/api/v1`，本页避免重复追加；`/evaluation/` 的尾斜杠按注释保留；`/tenants/kv/prompt-templates` 和 `/tenants/kv/web-search-config` 是通用 `/tenants/kv/:key` 路由的具体 key 示例。

<a id="models"></a>

## 数据结构目录

`Study.*` 为按对话链路源码补充的说明名称，其余名称来自 Swagger。字段名为 JSON 名称；“未声明”表示注释没有更精确的类型信息。

- [Study.ChatTrace](#model-001)
- [Study.ChatTraceResponse](#model-002)
- [Study.ChatTraceResult](#model-003)
- [Study.TraceObservation](#model-004)
- [errors.AppError](#model-005)
- [github_com_Tencent_WeKnora_internal_application_service.WikiLintIssue](#model-006)
- [github_com_Tencent_WeKnora_internal_application_service.WikiLintIssueSeverity](#model-007)
- [github_com_Tencent_WeKnora_internal_application_service.WikiLintIssueType](#model-008)
- [github_com_Tencent_WeKnora_internal_application_service.WikiLintReport](#model-009)
- [github_com_Tencent_WeKnora_internal_datasource.ConnectorMetadata](#model-010)
- [github_com_Tencent_WeKnora_internal_errors.AppError](#model-011)
- [github_com_Tencent_WeKnora_internal_infrastructure_chunker.DocProfile](#model-012)
- [github_com_Tencent_WeKnora_internal_infrastructure_chunker.StrategyTier](#model-013)
- [github_com_Tencent_WeKnora_internal_infrastructure_chunker.TierRejection](#model-014)
- [github_com_Tencent_WeKnora_internal_models_limiter.RuntimeStat](#model-015)
- [gorm.DeletedAt](#model-016)
- [internal_handler.AddFavoriteRequest](#model-017)
- [internal_handler.BatchDeleteKnowledgeRequest](#model-018)
- [internal_handler.BatchDownloadKnowledgeRequest](#model-019)
- [internal_handler.CopyKnowledgeBaseRequest](#model-020)
- [internal_handler.CopyModelRequest](#model-021)
- [internal_handler.CreateAgentRequest](#model-022)
- [internal_handler.CreateModelRequest](#model-023)
- [internal_handler.CreateStoreRequest](#model-024)
- [internal_handler.CreateSystemUserResponse](#model-025)
- [internal_handler.DeleteTagRequest](#model-026)
- [internal_handler.EnsureMessageSuggestionsRequest](#model-027)
- [internal_handler.EvaluationRequest](#model-028)
- [internal_handler.FabriTextRequest](#model-029)
- [internal_handler.GetStorageEngineStatusResponse](#model-030)
- [internal_handler.GetSystemInfoResponse](#model-031)
- [internal_handler.InitializationRequest](#model-032)
- [internal_handler.KBModelConfigRequest](#model-033)
- [internal_handler.ListSystemAdminsResponse](#model-034)
- [internal_handler.ModelTestRequest](#model-035)
- [internal_handler.MoveKnowledgeRequest](#model-036)
- [internal_handler.MoveKnowledgeResponse](#model-037)
- [internal_handler.MoveKnowledgeToFolderRequest](#model-038)
- [internal_handler.PreviewChunkResult](#model-039)
- [internal_handler.PreviewChunkingPayload](#model-040)
- [internal_handler.PreviewChunkingRequest](#model-041)
- [internal_handler.PreviewChunkingResponse](#model-042)
- [internal_handler.PreviewChunkingStats](#model-043)
- [internal_handler.PromoteUserToSystemAdminRequest](#model-044)
- [internal_handler.RemoteModelCheckRequest](#model-045)
- [internal_handler.RenameKnowledgeFolderRequest](#model-046)
- [internal_handler.ResetUserPasswordRequest](#model-047)
- [internal_handler.RevokeSystemAdminRequest](#model-048)
- [internal_handler.RuntimeQueuesResponse](#model-049)
- [internal_handler.RuntimeTasksResponse](#model-050)
- [internal_handler.RuntimeWorkerPool](#model-051)
- [internal_handler.SandboxCheckItem](#model-052)
- [internal_handler.SandboxCheckRequest](#model-053)
- [internal_handler.SandboxCheckResponse](#model-054)
- [internal_handler.SearchMessagesRequest](#model-055)
- [internal_handler.SearchWikiRequest](#model-056)
- [internal_handler.StorageCheckRequest](#model-057)
- [internal_handler.StorageCheckResponse](#model-058)
- [internal_handler.StorageEngineStatusItem](#model-059)
- [internal_handler.SuggestionEventRequest](#model-060)
- [internal_handler.TestProviderRequest](#model-061)
- [internal_handler.TestStoreRequest](#model-062)
- [internal_handler.TextRelationExtractionRequest](#model-063)
- [internal_handler.UpdateAgentRequest](#model-064)
- [internal_handler.UpdateChunkRequest](#model-065)
- [internal_handler.UpdateKnowledgeBaseRequest](#model-066)
- [internal_handler.UpdateKnowledgeRequest](#model-067)
- [internal_handler.UpdateModelRequest](#model-068)
- [internal_handler.UpdateProviderRequest](#model-069)
- [internal_handler.UpdateStoreRequest](#model-070)
- [internal_handler.UpdateSystemSettingRequest](#model-071)
- [internal_handler.acceptInvitationByTokenRequest](#model-072)
- [internal_handler.addMemberRequest](#model-073)
- [internal_handler.addSimilarQuestionsRequest](#model-074)
- [internal_handler.apiPrincipalConfigRequest](#model-075)
- [internal_handler.apiPrincipalTestTokenRequest](#model-076)
- [internal_handler.auditLogListResponse](#model-077)
- [internal_handler.batchReparseKnowledgeRequest](#model-078)
- [internal_handler.createInvitationRequest](#model-079)
- [internal_handler.createInviteLinkRequest](#model-080)
- [internal_handler.createTenantRequest](#model-081)
- [internal_handler.invitationLookupRequest](#model-082)
- [internal_handler.invitationLookupResponse](#model-083)
- [internal_handler.meEnvVarRequest](#model-084)
- [internal_handler.platformAPIKeyCreateRequest](#model-085)
- [internal_handler.registerByInviteRequest](#model-086)
- [internal_handler.resolveAncestorsRequest](#model-087)
- [internal_handler.sandboxConfigRequest](#model-088)
- [internal_handler.skillPatchRequest](#model-089)
- [internal_handler.skillSourceRequest](#model-090)
- [internal_handler.storageBackendRequest](#model-091)
- [internal_handler.updateLastFAQImportResultDisplayStatusRequest](#model-092)
- [internal_handler.updateMemberRoleRequest](#model-093)
- [internal_handler.updateMyPreferencesRequest](#model-094)
- [internal_handler_session.AttachmentUpload](#model-095)
- [internal_handler_session.CreateKnowledgeQARequest](#model-096)
- [internal_handler_session.CreateSessionRequest](#model-097)
- [internal_handler_session.ForkSessionRequest](#model-098)
- [internal_handler_session.GenerateTitleRequest](#model-099)
- [internal_handler_session.ImageAttachment](#model-100)
- [internal_handler_session.MentionedItemRequest](#model-101)
- [internal_handler_session.RewindSessionRequest](#model-102)
- [internal_handler_session.SearchKnowledgeRequest](#model-103)
- [internal_handler_session.SteerMessageRequest](#model-104)
- [internal_handler_session.StopSessionRequest](#model-105)
- [internal_handler_session.batchDeleteRequest](#model-106)
- [types.ASRConfig](#model-107)
- [types.AdminCreateUserRequest](#model-108)
- [types.AgentStep](#model-109)
- [types.AuditLog](#model-110)
- [types.AutoTagConfig](#model-111)
- [types.COSEngineConfig](#model-112)
- [types.ChatHistoryConfig](#model-113)
- [types.ChunkingConfig](#model-114)
- [types.ConnectionConfig](#model-115)
- [types.ContextConfig](#model-116)
- [types.CreateOrganizationRequest](#model-117)
- [types.CredentialsConfig](#model-118)
- [types.CubeEgressRule](#model-119)
- [types.CubeHeaderInject](#model-120)
- [types.CubeSandboxConfig](#model-121)
- [types.CustomAgentConfig](#model-122)
- [types.DataSource](#model-123)
- [types.DockerSandboxConfig](#model-124)
- [types.E2BHostRule](#model-125)
- [types.E2BSandboxConfig](#model-126)
- [types.EmbeddingParameters](#model-127)
- [types.ExtractConfig](#model-128)
- [types.FAQBatchUpsertPayload](#model-129)
- [types.FAQConfig](#model-130)
- [types.FAQEntryFieldsBatchUpdate](#model-131)
- [types.FAQEntryFieldsUpdate](#model-132)
- [types.FAQEntryPayload](#model-133)
- [types.FAQSearchRequest](#model-134)
- [types.FollowUpSuggestionConfig](#model-135)
- [types.GalleryUserPrefs](#model-136)
- [types.GraphNode](#model-137)
- [types.GraphRelation](#model-138)
- [types.ImageActionsConfig](#model-139)
- [types.ImageAttrCondition](#model-140)
- [types.ImageOCRAction](#model-141)
- [types.ImageProcessingConfig](#model-142)
- [types.IndexConfig](#model-143)
- [types.IndexingStrategy](#model-144)
- [types.InviteMemberRequest](#model-145)
- [types.JoinByOrganizationIDRequest](#model-146)
- [types.JoinOrganizationRequest](#model-147)
- [types.KS3EngineConfig](#model-148)
- [types.KnowledgeBase](#model-149)
- [types.KnowledgeBaseConfig](#model-150)
- [types.KnowledgeBaseProfile](#model-151)
- [types.KnowledgeBaseProfileConfig](#model-152)
- [types.KnowledgeBaseProfileStats](#model-153)
- [types.KnowledgeBaseShareResponse](#model-154)
- [types.KnowledgeMoveProgress](#model-155)
- [types.KnowledgeProcessOverrides](#model-156)
- [types.ListMembersResponse](#model-157)
- [types.ListOrganizationsResponse](#model-158)
- [types.ListSharesResponse](#model-159)
- [types.LocalEngineConfig](#model-160)
- [types.LoginRequest](#model-161)
- [types.LoginResponse](#model-162)
- [types.MCPAdvancedConfig](#model-163)
- [types.MCPAuthConfig](#model-164)
- [types.MCPEnvVars](#model-165)
- [types.MCPHeaders](#model-166)
- [types.MCPService](#model-167)
- [types.MCPStdioConfig](#model-168)
- [types.ManualKnowledgePayload](#model-169)
- [types.Membership](#model-170)
- [types.MemoryConfig](#model-171)
- [types.MentionedItem](#model-172)
- [types.Message](#model-173)
- [types.MessageArtifact](#model-174)
- [types.MessageAttachment](#model-175)
- [types.MessageImage](#model-176)
- [types.MinIOEngineConfig](#model-177)
- [types.ModelParameters](#model-178)
- [types.ModelSpecOverride](#model-179)
- [types.NamedCount](#model-180)
- [types.OBSEngineConfig](#model-181)
- [types.OIDCAuthURLResponse](#model-182)
- [types.OIDCConfigResponse](#model-183)
- [types.OSSEngineConfig](#model-184)
- [types.OrganizationMemberResponse](#model-185)
- [types.OrganizationResponse](#model-186)
- [types.ParserEngineConfig](#model-187)
- [types.ParserEngineRule](#model-188)
- [types.ProviderMetadata](#model-189)
- [types.QuestionGenerationConfig](#model-190)
- [types.QuestionOrigin](#model-191)
- [types.QuestionSuggestionConfig](#model-192)
- [types.QueueStat](#model-193)
- [types.RegisterRequest](#model-194)
- [types.RegisterResponse](#model-195)
- [types.RequestRoleUpgradeRequest](#model-196)
- [types.RerankOptions](#model-197)
- [types.Resource](#model-198)
- [types.ResourceCountsByOrgResponse](#model-199)
- [types.RetrievalConfig](#model-200)
- [types.RetrieverEngineParams](#model-201)
- [types.RetrieverEngines](#model-202)
- [types.ReviewJoinRequestRequest](#model-203)
- [types.RuntimeTaskInfo](#model-204)
- [types.S3EngineConfig](#model-205)
- [types.SandboxCheckpoint](#model-206)
- [types.SandboxNetworkPolicy](#model-207)
- [types.SearchParams](#model-208)
- [types.SearchResult](#model-209)
- [types.Session](#model-210)
- [types.SessionLastRequestState](#model-211)
- [types.ShareKnowledgeBaseRequest](#model-212)
- [types.SkillImageConfig](#model-213)
- [types.SourceLocator](#model-214)
- [types.StarterSuggestionConfig](#model-215)
- [types.StorageBackendConfig](#model-216)
- [types.StorageConfig](#model-217)
- [types.StorageEngineConfig](#model-218)
- [types.StorageProviderConfig](#model-219)
- [types.SubmitJoinRequestRequest](#model-220)
- [types.SuggestionAttribution](#model-221)
- [types.SyncLog](#model-222)
- [types.SystemSetting](#model-223)
- [types.TOSEngineConfig](#model-224)
- [types.Tenant](#model-225)
- [types.TenantSandboxConfig](#model-226)
- [types.TokenUsage](#model-227)
- [types.ToolCall](#model-228)
- [types.ToolCallMetadata](#model-229)
- [types.ToolCallTarget](#model-230)
- [types.ToolResult](#model-231)
- [types.UpdateMemberRoleRequest](#model-232)
- [types.UpdateOrganizationRequest](#model-233)
- [types.UpdateSharePermissionRequest](#model-234)
- [types.UsedMemory](#model-235)
- [types.User](#model-236)
- [types.UserInfo](#model-237)
- [types.UserPreferences](#model-238)
- [types.VLMConfig](#model-239)
- [types.VolumeMountConfig](#model-240)
- [types.WeKnoraCloudCredentials](#model-241)
- [types.WebSearchConfig](#model-242)
- [types.WebSearchProviderEntity](#model-243)
- [types.WebSearchProviderParameters](#model-244)
- [types.WikiConfig](#model-245)
- [types.WikiFolder](#model-246)
- [types.WikiFolderCreateRequest](#model-247)
- [types.WikiFolderListResponse](#model-248)
- [types.WikiFolderNode](#model-249)
- [types.WikiFolderUpdateRequest](#model-250)
- [types.WikiGraphData](#model-251)
- [types.WikiGraphEdge](#model-252)
- [types.WikiGraphMeta](#model-253)
- [types.WikiGraphNode](#model-254)
- [types.WikiIndexEntry](#model-255)
- [types.WikiIndexGroup](#model-256)
- [types.WikiIndexResponse](#model-257)
- [types.WikiPage](#model-258)
- [types.WikiPageIssue](#model-259)
- [types.WikiPageListResponse](#model-260)
- [types.WikiPageMoveRequest](#model-261)
- [types.WikiPageRevertRequest](#model-262)
- [types.WikiPageRevision](#model-263)
- [types.WikiPageRevisionListResponse](#model-264)
- [types.WikiPageUpdateRequest](#model-265)
- [types.WikiStats](#model-266)

<a id="model-001"></a>

### Study.ChatTrace

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| id | 否 | string | — |
| name | 否 | string | — |
| sessionId | 否 | string | — |
| timestamp | 否 | string | — |
| latency | 否 | number | 总耗时，单位秒 |
| input | 否 | 未声明 | 任意 JSON 值 |
| output | 否 | 未声明 | 任意 JSON 值 |
| metadata | 否 | object | — |
| observations | 否 | array&lt;[Study.TraceObservation](#model-004)&gt; | — |

[返回数据结构目录](#models)

<a id="model-002"></a>

### Study.ChatTraceResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| success | 否 | boolean | — |
| data | 否 | [Study.ChatTraceResult](#model-003) | — |

[返回数据结构目录](#models)

<a id="model-003"></a>

### Study.ChatTraceResult

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| state | 否 | string | 可选值：["ready", "disabled", "not_found"] |
| session_id | 否 | string | — |
| message_id | 否 | string | — |
| request_id | 否 | string | — |
| trace | 否 | [Study.ChatTrace](#model-001) | disabled / not_found 时为 null |

[返回数据结构目录](#models)

<a id="model-004"></a>

### Study.TraceObservation

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| id | 否 | string | — |
| parentObservationId | 否 | string | — |
| name | 否 | string | — |
| type | 否 | string | — |
| startTime | 否 | string | — |
| endTime | 否 | string | — |
| completionStartTime | 否 | string | — |
| model | 否 | string | — |
| level | 否 | string | — |
| statusMessage | 否 | string | — |
| metadata | 否 | object | — |
| usageDetails | 否 | object | — |
| input | 否 | 未声明 | 任意 JSON 值 |
| output | 否 | 未声明 | 任意 JSON 值 |

[返回数据结构目录](#models)

<a id="model-005"></a>

### errors.AppError

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| code | 否 | integer | — |
| details | 否 | 未声明 | — |
| message | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-006"></a>

### github_com_Tencent_WeKnora_internal_application_service.WikiLintIssue

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| auto_fixable | 否 | boolean | — |
| description | 否 | string | — |
| page_slug | 否 | string | — |
| severity | 否 | [github_com_Tencent_WeKnora_internal_application_service.WikiLintIssueSeverity](#model-007) | — |
| target_slug | 否 | string | TargetSlug identifies the other page involved in the issue (e.g. the<br>broken link target, or the entity slug for a missing cross-ref). It is<br>the structured field used by AutoFix instead of parsing Description. |
| type | 否 | [github_com_Tencent_WeKnora_internal_application_service.WikiLintIssueType](#model-008) | — |

[返回数据结构目录](#models)

<a id="model-007"></a>

### github_com_Tencent_WeKnora_internal_application_service.WikiLintIssueSeverity

类型：string

可选值：["info", "warning", "error"]

[返回数据结构目录](#models)

<a id="model-008"></a>

### github_com_Tencent_WeKnora_internal_application_service.WikiLintIssueType

类型：string

可选值：["orphan_page", "broken_link", "stale_ref", "missing_cross_ref", "empty_content", "duplicate_slug"]

[返回数据结构目录](#models)

<a id="model-009"></a>

### github_com_Tencent_WeKnora_internal_application_service.WikiLintReport

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| health_score | 否 | integer | 0-100 |
| issues | 否 | array&lt;[github_com_Tencent_WeKnora_internal_application_service.WikiLintIssue](#model-006)&gt; | — |
| knowledge_base_id | 否 | string | — |
| stats | 否 | [types.WikiStats](#model-266) | — |
| summary | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-010"></a>

### github_com_Tencent_WeKnora_internal_datasource.ConnectorMetadata

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| auth_type | 否 | string | "oauth2", "api_key", "token", etc. |
| capabilities | 否 | array&lt;string&gt; | "incremental", "webhook", "deletion_sync", etc. |
| description | 否 | string | — |
| icon | 否 | string | — |
| name | 否 | string | — |
| priority | 否 | integer | Priority order for UI display (lower = higher priority) |
| type | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-011"></a>

### github_com_Tencent_WeKnora_internal_errors.AppError

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| code | 否 | integer | — |
| details | 否 | 未声明 | — |
| message | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-012"></a>

### github_com_Tencent_WeKnora_internal_infrastructure_chunker.DocProfile

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| all_caps_short_line_count | 否 | integer | — |
| avg_line_len | 否 | number | — |
| blank_paragraph_breaks | 否 | integer | — |
| chinese_chapter_count | 否 | integer | — |
| code_ratio | 否 | number | — |
| detected_langs | 否 | array&lt;string&gt; | Detected language hints (best-effort) |
| english_chapter_count | 否 | integer | — |
| form_feed_count | 否 | integer | — |
| german_chapter_count | 否 | integer | — |
| has_code | 否 | boolean | — |
| has_tables | 否 | boolean | Content characteristics |
| md_heading_counts | 否 | map&lt;string, integer&gt; | Markdown structure |
| md_heading_total | 否 | integer | — |
| numbered_section_count | 否 | integer | Heuristic indicators |
| repeated_footer_count | 否 | integer | — |
| std_line_len | 否 | number | — |
| total_chars | 否 | integer | — |
| total_lines | 否 | integer | — |
| visual_sep_count | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-013"></a>

### github_com_Tencent_WeKnora_internal_infrastructure_chunker.StrategyTier

类型：string

可选值：["heading", "heuristic", "legacy"]

[返回数据结构目录](#models)

<a id="model-014"></a>

### github_com_Tencent_WeKnora_internal_infrastructure_chunker.TierRejection

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| reason | 否 | string | — |
| tier | 否 | [github_com_Tencent_WeKnora_internal_infrastructure_chunker.StrategyTier](#model-013) | — |

[返回数据结构目录](#models)

<a id="model-015"></a>

### github_com_Tencent_WeKnora_internal_models_limiter.RuntimeStat

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| active | 否 | integer | — |
| limit | 否 | integer | — |
| model_id | 否 | string | — |
| name | 否 | string | — |
| waiting | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-016"></a>

### gorm.DeletedAt

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| time | 否 | string | — |
| valid | 否 | boolean | Valid is true if Time is not NULL |

[返回数据结构目录](#models)

<a id="model-017"></a>

### internal_handler.AddFavoriteRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| id | 否 | string | — |
| type | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-018"></a>

### internal_handler.BatchDeleteKnowledgeRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| ids | 是 | array&lt;string&gt; | — |
| kb_id | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-019"></a>

### internal_handler.BatchDownloadKnowledgeRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| ids | 是 | array&lt;string&gt; | 最少项数：1；最多项数：200 |

[返回数据结构目录](#models)

<a id="model-020"></a>

### internal_handler.CopyKnowledgeBaseRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| source_id | 是 | string | — |
| target_id | 否 | string | — |
| task_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-021"></a>

### internal_handler.CopyModelRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| display_name | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-022"></a>

### internal_handler.CreateAgentRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| avatar | 否 | string | — |
| config | 否 | [types.CustomAgentConfig](#model-122) | — |
| description | 否 | string | — |
| name | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-023"></a>

### internal_handler.CreateModelRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| description | 否 | string | — |
| display_name | 否 | string | — |
| name | 是 | string | — |
| parameters | 是 | [types.ModelParameters](#model-178) | — |
| source | 是 | string | — |
| type | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-024"></a>

### internal_handler.CreateStoreRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| connection_config | 是 | [types.ConnectionConfig](#model-115) | — |
| engine_type | 是 | string | — |
| index_config | 否 | [types.IndexConfig](#model-143) | — |
| name | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-025"></a>

### internal_handler.CreateSystemUserResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| generated_password | 否 | string | GeneratedPassword is the plaintext password when the server<br>auto-generated one. Absent when the caller supplied the password. |
| user | 否 | [types.UserInfo](#model-237) | — |

[返回数据结构目录](#models)

<a id="model-026"></a>

### internal_handler.DeleteTagRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| exclude_ids | 否 | array&lt;integer&gt; | Chunk seq_ids to exclude from deletion |

[返回数据结构目录](#models)

<a id="model-027"></a>

### internal_handler.EnsureMessageSuggestionsRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| regenerate | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-028"></a>

### internal_handler.EvaluationRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| chat_id | 否 | string | ID of chat model to use |
| dataset_id | 否 | string | ID of dataset to evaluate |
| knowledge_base_id | 否 | string | ID of knowledge base to use |
| rerank_id | 否 | string | ID of rerank model to use |

[返回数据结构目录](#models)

<a id="model-029"></a>

### internal_handler.FabriTextRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| model_id | 是 | string | — |
| tags | 否 | array&lt;string&gt; | — |

[返回数据结构目录](#models)

<a id="model-030"></a>

### internal_handler.GetStorageEngineStatusResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| allowed_providers | 否 | array&lt;string&gt; | — |
| engines | 否 | array&lt;[internal_handler.StorageEngineStatusItem](#model-059)&gt; | — |
| minio_env_available | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-031"></a>

### internal_handler.GetSystemInfoResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| build_time | 否 | string | — |
| commit_id | 否 | string | — |
| db_migration_error | 否 | string | DBMigrationError carries the human-readable error message recorded when<br>the most recent startup migration attempt failed. Empty when migrations<br>succeeded; non-empty values let the frontend surface a troubleshooting<br>banner instead of silently hiding the DB version row (see issue #1319). |
| db_version | 否 | string | — |
| edition | 否 | string | — |
| go_version | 否 | string | — |
| graph_database_engine | 否 | string | — |
| keyword_index_engine | 否 | string | — |
| minio_enabled | 否 | boolean | — |
| started_at | 否 | string | StartedAt is the server process boot time (RFC3339, UTC). |
| uptime_seconds | 否 | integer | UptimeSeconds is seconds elapsed since process start. |
| vector_store_engine | 否 | string | — |
| version | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-032"></a>

### internal_handler.InitializationRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| documentSplitting | 是 | object | — |
| embedding | 是 | object | — |
| llm | 是 | object | — |
| multimodal | 否 | object | — |
| nodeExtract | 否 | object | — |
| questionGeneration | 否 | object | — |
| rerank | 否 | object | — |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "required": [
    "documentSplitting",
    "embedding",
    "llm"
  ],
  "properties": {
    "documentSplitting": {
      "type": "object",
      "required": [
        "chunkSize",
        "separators"
      ],
      "properties": {
        "chunkOverlap": {
          "type": "integer",
          "minimum": 0
        },
        "chunkSize": {
          "type": "integer",
          "maximum": 10000,
          "minimum": 100
        },
        "separators": {
          "type": "array",
          "minItems": 1,
          "items": {
            "type": "string"
          }
        }
      }
    },
    "embedding": {
      "type": "object",
      "required": [
        "modelName",
        "source"
      ],
      "properties": {
        "apiKey": {
          "type": "string"
        },
        "baseUrl": {
          "type": "string"
        },
        "dimension": {
          "description": "添加embedding维度字段",
          "type": "integer"
        },
        "modelName": {
          "type": "string"
        },
        "source": {
          "type": "string"
        }
      }
    },
    "llm": {
      "type": "object",
      "required": [
        "modelName",
        "source"
      ],
      "properties": {
        "apiKey": {
          "type": "string"
        },
        "baseUrl": {
          "type": "string"
        },
        "modelName": {
          "type": "string"
        },
        "source": {
          "type": "string"
        }
      }
    },
    "multimodal": {
      "type": "object",
      "properties": {
        "cos": {
          "type": "object",
          "properties": {
            "appId": {
              "type": "string"
            },
            "bucketName": {
              "type": "string"
            },
            "pathPrefix": {
              "type": "string"
            },
            "region": {
              "type": "string"
            },
            "secretId": {
              "type": "string"
            },
            "secretKey": {
              "type": "string"
            }
          }
        },
        "enabled": {
          "type": "boolean"
        },
        "minio": {
          "type": "object",
          "properties": {
            "bucketName": {
              "type": "string"
            },
            "pathPrefix": {
              "type": "string"
            }
          }
        },
        "storageType": {
          "type": "string"
        },
        "vlm": {
          "type": "object",
          "properties": {
            "apiKey": {
              "type": "string"
            },
            "baseUrl": {
              "type": "string"
            },
            "interfaceType": {
              "description": "\"ollama\" or \"openai\"",
              "type": "string"
            },
            "modelName": {
              "type": "string"
            }
          }
        }
      }
    },
    "nodeExtract": {
      "type": "object",
      "properties": {
        "enabled": {
          "type": "boolean"
        },
        "nodes": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "attributes": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "name": {
                "type": "string"
              }
            }
          }
        },
        "relations": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "node1": {
                "type": "string"
              },
              "node2": {
                "type": "string"
              },
              "type": {
                "type": "string"
              }
            }
          }
        },
        "tags": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "text": {
          "type": "string"
        }
      }
    },
    "questionGeneration": {
      "type": "object",
      "properties": {
        "enabled": {
          "type": "boolean"
        },
        "questionCount": {
          "type": "integer"
        }
      }
    },
    "rerank": {
      "type": "object",
      "properties": {
        "apiKey": {
          "type": "string"
        },
        "baseUrl": {
          "type": "string"
        },
        "enabled": {
          "type": "boolean"
        },
        "modelName": {
          "type": "string"
        }
      }
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-033"></a>

### internal_handler.KBModelConfigRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| asr_config | 否 | [types.ASRConfig](#model-107) | — |
| documentSplitting | 否 | object | 文档分块配置 |
| embeddingModelId | 否 | string | optional when RAG indexing is disabled |
| llmModelId | 是 | string | — |
| multimodal | 否 | object | 多模态配置（仅模型相关；存储引擎在 storageProvider 中配置） |
| nodeExtract | 否 | object | 知识图谱配置 |
| questionGeneration | 否 | object | 问题生成配置 |
| storageBackendId | 否 | string | — |
| storageProvider | 否 | string | 存储引擎选择（"local" &#124; "minio" &#124; "cos"），影响文档上传与文档内图片存储，参数从全局设置读取 |
| vlm_config | 否 | [types.VLMConfig](#model-239) | — |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "required": [
    "llmModelId"
  ],
  "properties": {
    "asr_config": {
      "$ref": "#/definitions/types.ASRConfig"
    },
    "documentSplitting": {
      "description": "文档分块配置",
      "type": "object",
      "properties": {
        "childChunkSize": {
          "type": "integer"
        },
        "chunkOverlap": {
          "type": "integer"
        },
        "chunkSize": {
          "type": "integer"
        },
        "enableParentChild": {
          "type": "boolean"
        },
        "languages": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "parentChunkSize": {
          "type": "integer"
        },
        "parserEngineRules": {
          "type": "array",
          "items": {
            "$ref": "#/definitions/types.ParserEngineRule"
          }
        },
        "separators": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "strategy": {
          "description": "Strategy / TokenLimit / Languages use pointer types so the\nhandler can distinguish \"field absent in payload\" (no change)\nfrom \"field present with empty/zero value\" (clear / disable).\nWithout that distinction, users could set strategy=\"auto\" once\nbut never reset it back to legacy / unset.",
          "type": "string"
        },
        "tableMetadataInstructions": {
          "type": "string"
        },
        "tokenLimit": {
          "type": "integer"
        }
      }
    },
    "embeddingModelId": {
      "description": "optional when RAG indexing is disabled",
      "type": "string"
    },
    "llmModelId": {
      "type": "string"
    },
    "multimodal": {
      "description": "多模态配置（仅模型相关；存储引擎在 storageProvider 中配置）",
      "type": "object",
      "properties": {
        "enabled": {
          "type": "boolean"
        }
      }
    },
    "nodeExtract": {
      "description": "知识图谱配置",
      "type": "object",
      "properties": {
        "customInstructions": {
          "type": "string"
        },
        "enabled": {
          "type": "boolean"
        },
        "nodes": {
          "type": "array",
          "items": {
            "$ref": "#/definitions/types.GraphNode"
          }
        },
        "relations": {
          "type": "array",
          "items": {
            "$ref": "#/definitions/types.GraphRelation"
          }
        },
        "tags": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "text": {
          "type": "string"
        }
      }
    },
    "questionGeneration": {
      "description": "问题生成配置",
      "type": "object",
      "properties": {
        "customInstructions": {
          "type": "string"
        },
        "enabled": {
          "type": "boolean"
        },
        "questionCount": {
          "type": "integer"
        }
      }
    },
    "storageBackendId": {
      "type": "string"
    },
    "storageProvider": {
      "description": "存储引擎选择（\"local\" | \"minio\" | \"cos\"），影响文档上传与文档内图片存储，参数从全局设置读取",
      "type": "string"
    },
    "vlm_config": {
      "$ref": "#/definitions/types.VLMConfig"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-034"></a>

### internal_handler.ListSystemAdminsResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| admins | 否 | array&lt;[types.UserInfo](#model-237)&gt; | — |
| total | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-035"></a>

### internal_handler.ModelTestRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| apiKey | 否 | string | — |
| appSecret | 否 | string | AppSecret 用于 LKEAP / Volcengine Rerank 等需要第二段密钥的场景（对应模型 Parameters.AppSecret）。 |
| baseUrl | 否 | string | — |
| customHeaders | 否 | map&lt;string, string&gt; | — |
| dimension | 否 | integer | — |
| extraConfig | 否 | map&lt;string, string&gt; | — |
| interfaceType | 否 | string | — |
| modelId | 否 | string | ModelID, when set, instructs the handler to substitute any missing<br>secrets (APIKey, AppSecret via ExtraConfig) from the stored model<br>record before assembling the test client. This lets the "Test<br>connection" button work on existing models without making the<br>frontend reload — and ship — the plaintext API key. Other fields<br>(BaseURL, ModelName, etc.) on this request still override the<br>stored values, so a user can validate a new endpoint against the<br>existing credentials in one click. |
| modelName | 是 | string | — |
| provider | 否 | string | — |
| source | 否 | string | 为空时按需默认为 "remote" |
| spec | 否 | [types.ModelSpecOverride](#model-179) | — |
| supportsDimensionOverride | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-036"></a>

### internal_handler.MoveKnowledgeRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| knowledge_ids | 是 | array&lt;string&gt; | 最少项数：1 |
| mode | 是 | string | 可选值：["reuse_vectors", "reparse"] |
| source_kb_id | 是 | string | — |
| target_kb_id | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-037"></a>

### internal_handler.MoveKnowledgeResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| knowledge_count | 否 | integer | — |
| message | 否 | string | — |
| source_kb_id | 否 | string | — |
| target_kb_id | 否 | string | — |
| task_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-038"></a>

### internal_handler.MoveKnowledgeToFolderRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| folder_path | 否 | string | FolderPath is the destination folder; the empty string is the knowledge<br>base top level. It is deliberately not `binding:"required"` so documents<br>can be moved back out of every folder. |
| kb_id | 是 | string | — |
| knowledge_ids | 是 | array&lt;string&gt; | — |

[返回数据结构目录](#models)

<a id="model-039"></a>

### internal_handler.PreviewChunkResult

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| content | 否 | string | — |
| context_header | 否 | string | — |
| end | 否 | integer | — |
| seq | 否 | integer | — |
| size_chars | 否 | integer | — |
| size_tokens_approx | 否 | integer | — |
| start | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-040"></a>

### internal_handler.PreviewChunkingPayload

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| child_chunk_size | 否 | integer | — |
| chunk_overlap | 否 | integer | — |
| chunk_size | 否 | integer | — |
| enable_parent_child | 否 | boolean | — |
| languages | 否 | array&lt;string&gt; | — |
| parent_chunk_size | 否 | integer | — |
| separators | 否 | array&lt;string&gt; | — |
| strategy | 否 | string | — |
| token_limit | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-041"></a>

### internal_handler.PreviewChunkingRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| chunking_config | 否 | [internal_handler.PreviewChunkingPayload](#model-040) | — |
| text | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-042"></a>

### internal_handler.PreviewChunkingResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| chunks | 否 | array&lt;[internal_handler.PreviewChunkResult](#model-039)&gt; | — |
| profile | 否 | [github_com_Tencent_WeKnora_internal_infrastructure_chunker.DocProfile](#model-012) | — |
| rejected | 否 | array&lt;[github_com_Tencent_WeKnora_internal_infrastructure_chunker.TierRejection](#model-014)&gt; | — |
| selected_tier | 否 | [github_com_Tencent_WeKnora_internal_infrastructure_chunker.StrategyTier](#model-013) | — |
| stats | 否 | [internal_handler.PreviewChunkingStats](#model-043) | — |
| tier_chain | 否 | array&lt;[github_com_Tencent_WeKnora_internal_infrastructure_chunker.StrategyTier](#model-013)&gt; | — |

[返回数据结构目录](#models)

<a id="model-043"></a>

### internal_handler.PreviewChunkingStats

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| avg_chars | 否 | integer | — |
| count | 否 | integer | — |
| max_chars | 否 | integer | — |
| min_chars | 否 | integer | — |
| stddev_chars | 否 | integer | — |
| truncated_to | 否 | integer | TruncatedTo, when set, is the original chunk count before the<br>response was truncated to previewMaxChunks for transport. |

[返回数据结构目录](#models)

<a id="model-044"></a>

### internal_handler.PromoteUserToSystemAdminRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| email | 否 | string | — |
| user_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-045"></a>

### internal_handler.RemoteModelCheckRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| apiKey | 否 | string | — |
| appSecret | 否 | string | AppSecret 用于 LKEAP / Volcengine Rerank 等需要第二段密钥的场景（对应模型 Parameters.AppSecret）。 |
| baseUrl | 否 | string | — |
| customHeaders | 否 | map&lt;string, string&gt; | — |
| dimension | 否 | integer | — |
| extraConfig | 否 | map&lt;string, string&gt; | — |
| interfaceType | 否 | string | — |
| modelId | 否 | string | ModelID, when set, instructs the handler to substitute any missing<br>secrets (APIKey, AppSecret via ExtraConfig) from the stored model<br>record before assembling the test client. This lets the "Test<br>connection" button work on existing models without making the<br>frontend reload — and ship — the plaintext API key. Other fields<br>(BaseURL, ModelName, etc.) on this request still override the<br>stored values, so a user can validate a new endpoint against the<br>existing credentials in one click. |
| modelName | 是 | string | — |
| provider | 否 | string | — |
| source | 否 | string | 为空时按需默认为 "remote" |
| spec | 否 | [types.ModelSpecOverride](#model-179) | — |
| supportsDimensionOverride | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-046"></a>

### internal_handler.RenameKnowledgeFolderRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| from | 是 | string | — |
| to | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-047"></a>

### internal_handler.ResetUserPasswordRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| email | 是 | string | — |
| new_password | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-048"></a>

### internal_handler.RevokeSystemAdminRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| user_id | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-049"></a>

### internal_handler.RuntimeQueuesResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| available | 否 | boolean | — |
| model_limiter_available | 否 | boolean | — |
| models | 否 | array&lt;[github_com_Tencent_WeKnora_internal_models_limiter.RuntimeStat](#model-015)&gt; | — |
| parse_concurrency | 否 | integer | compatibility alias for upstream_concurrency |
| pools | 否 | array&lt;[internal_handler.RuntimeWorkerPool](#model-051)&gt; | — |
| queues | 否 | array&lt;[types.QueueStat](#model-193)&gt; | — |
| timestamp | 否 | integer | — |
| upstream_concurrency | 否 | integer | — |
| wiki_concurrency | 否 | integer | compatibility field |

[返回数据结构目录](#models)

<a id="model-050"></a>

### internal_handler.RuntimeTasksResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| available | 否 | boolean | — |
| has_more | 否 | boolean | — |
| next_cursor | 否 | string | — |
| page_size | 否 | integer | — |
| tasks | 否 | array&lt;[types.RuntimeTaskInfo](#model-204)&gt; | — |

[返回数据结构目录](#models)

<a id="model-051"></a>

### internal_handler.RuntimeWorkerPool

Return every row in the system_settings table (system-scope, not workspace-scoped). SystemAdmin only.

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| active | 否 | integer | live workers assigned to this pool |
| cluster_capacity | 否 | integer | sum of live server concurrency |
| concurrency | 否 | integer | configured per instance |
| instances | 否 | integer | live asynq server heartbeats |
| name | 否 | string | — |
| queue_count | 否 | integer | — |
| utilization | 否 | number | Active / ClusterCapacity |

[返回数据结构目录](#models)

<a id="model-052"></a>

### internal_handler.SandboxCheckItem

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| latency_ms | 否 | integer | — |
| message | 否 | string | Message carries free-form provider detail for an executed probe. |
| name | 否 | string | — |
| ok | 否 | boolean | — |
| reason | 否 | string | Reason is a stable code explaining why a probe was skipped. It exists so<br>the UI can phrase the skip in the operator's language instead of echoing<br>a server-side sentence. |

[返回数据结构目录](#models)

<a id="model-053"></a>

### internal_handler.SandboxCheckRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| config | 否 | [types.TenantSandboxConfig](#model-226) | — |
| config_id | 否 | string | ConfigID lets an edit form test stored credentials while overriding only<br>the fields the admin changed in the drawer. |
| deep | 否 | boolean | Deep additionally runs a throwaway script. For remote backends this also<br>creates and destroys one sandbox, which is the only way to validate the<br>template ID, data plane, in-sandbox execution, and outbound egress. It may<br>consume real sandbox time, so it is opt-in. |

[返回数据结构目录](#models)

<a id="model-054"></a>

### internal_handler.SandboxCheckResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| capabilities | 否 | map&lt;string, boolean&gt; | — |
| checks | 否 | array&lt;[internal_handler.SandboxCheckItem](#model-052)&gt; | — |
| ok | 否 | boolean | — |
| provider | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-055"></a>

### internal_handler.SearchMessagesRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| limit | 否 | integer | Maximum number of results to return (default: 20) |
| mode | 否 | string | Search mode: "keyword", "vector", "hybrid" (default: "hybrid") |
| query | 是 | string | Query text for search |
| session_ids | 否 | array&lt;string&gt; | Filter by specific session IDs (optional) |

[返回数据结构目录](#models)

<a id="model-056"></a>

### internal_handler.SearchWikiRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| knowledge_base_id | 否 | string | — |
| knowledge_base_ids | 否 | array&lt;string&gt; | — |
| limit | 否 | integer | — |
| query | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-057"></a>

### internal_handler.StorageCheckRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| cos | 否 | [types.COSEngineConfig](#model-112) | — |
| ks3 | 否 | [types.KS3EngineConfig](#model-148) | — |
| minio | 否 | [types.MinIOEngineConfig](#model-177) | — |
| obs | 否 | [types.OBSEngineConfig](#model-181) | — |
| oss | 否 | [types.OSSEngineConfig](#model-184) | — |
| provider | 否 | string | "minio", "cos", "tos", "s3", "oss", "ks3", "obs" |
| s3 | 否 | [types.S3EngineConfig](#model-205) | — |
| tos | 否 | [types.TOSEngineConfig](#model-224) | — |

[返回数据结构目录](#models)

<a id="model-058"></a>

### internal_handler.StorageCheckResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| bucket_created | 否 | boolean | — |
| message | 否 | string | — |
| ok | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-059"></a>

### internal_handler.StorageEngineStatusItem

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| allowed | 否 | boolean | — |
| available | 否 | boolean | whether the engine can be used |
| description | 否 | string | short description for UI |
| name | 否 | string | "local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs" |

[返回数据结构目录](#models)

<a id="model-060"></a>

### internal_handler.SuggestionEventRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| event_type | 是 | string | — |
| question_id | 否 | string | — |
| suggestion_set_id | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-061"></a>

### internal_handler.TestProviderRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| parameters | 否 | [types.WebSearchProviderParameters](#model-244) | — |
| provider | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-062"></a>

### internal_handler.TestStoreRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| connection_config | 是 | [types.ConnectionConfig](#model-115) | — |
| engine_type | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-063"></a>

### internal_handler.TextRelationExtractionRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| model_id | 是 | string | — |
| tags | 是 | array&lt;string&gt; | — |
| text | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-064"></a>

### internal_handler.UpdateAgentRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| avatar | 否 | string | Avatar travels as a pointer so an omitted field can be told apart from<br>an explicit clear: nil keeps the stored avatar, a pointer to "" wipes<br>it. As a plain string the two cases were indistinguishable, so a caller<br>that PUT only a config silently zeroed the avatar and still got a 200. |
| config | 否 | [types.CustomAgentConfig](#model-122) | — |
| description | 否 | string | — |
| name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-065"></a>

### internal_handler.UpdateChunkRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| content | 否 | string | — |
| expected_revision | 否 | integer | — |
| is_enabled | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-066"></a>

### internal_handler.UpdateKnowledgeBaseRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| config | 否 | [types.KnowledgeBaseConfig](#model-150) | — |
| description | 否 | string | — |
| name | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-067"></a>

### internal_handler.UpdateKnowledgeRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| custom_metadata | 否 | array&lt;integer&gt; | — |
| description | 否 | string | — |
| title | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-068"></a>

### internal_handler.UpdateModelRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| description | 否 | string | — |
| display_name | 否 | string | — |
| name | 否 | string | — |
| parameters | 否 | [types.ModelParameters](#model-178) | — |
| source | 否 | string | — |
| type | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-069"></a>

### internal_handler.UpdateProviderRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| description | 否 | string | — |
| is_default | 否 | boolean | — |
| name | 否 | string | — |
| parameters | 否 | [types.WebSearchProviderParameters](#model-244) | — |

[返回数据结构目录](#models)

<a id="model-070"></a>

### internal_handler.UpdateStoreRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| name | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-071"></a>

### internal_handler.UpdateSystemSettingRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| value | 否 | 未声明 | Value is intentionally `any` (decoded as float64 / string / bool /<br>etc. by the JSON unmarshaller). Service.encodeForType normalises<br>these against the registry's declared type and rejects mismatches. |

[返回数据结构目录](#models)

<a id="model-072"></a>

### internal_handler.acceptInvitationByTokenRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| token | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-073"></a>

### internal_handler.addMemberRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| email | 是 | string | — |
| role | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-074"></a>

### internal_handler.addSimilarQuestionsRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| similar_questions | 是 | array&lt;string&gt; | 最少项数：1 |

[返回数据结构目录](#models)

<a id="model-075"></a>

### internal_handler.apiPrincipalConfigRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| direct_header_name | 否 | string | — |
| hmac_secret | 否 | string | — |
| mode | 否 | string | — |
| require_direct_header | 否 | boolean | — |
| signed_token_header_name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-076"></a>

### internal_handler.apiPrincipalTestTokenRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| expires_in_seconds | 否 | integer | — |
| external_user_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-077"></a>

### internal_handler.auditLogListResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| data | 否 | array&lt;[types.AuditLog](#model-110)&gt; | — |
| next_cursor | 否 | integer | — |
| success | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-078"></a>

### internal_handler.batchReparseKnowledgeRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| ids | 是 | array&lt;string&gt; | — |
| kb_id | 是 | string | — |
| process_config | 否 | [types.KnowledgeProcessOverrides](#model-156) | — |

[返回数据结构目录](#models)

<a id="model-079"></a>

### internal_handler.createInvitationRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| email | 是 | string | — |
| message | 否 | string | — |
| role | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-080"></a>

### internal_handler.createInviteLinkRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| message | 否 | string | — |
| role | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-081"></a>

### internal_handler.createTenantRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| description | 否 | string | 最大长度：512 |
| name | 是 | string | 最短长度：1；最大长度：128 |

[返回数据结构目录](#models)

<a id="model-082"></a>

### internal_handler.invitationLookupRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| token | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-083"></a>

### internal_handler.invitationLookupResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| expires_at | 否 | string | — |
| role | 否 | string | — |
| tenant_id | 否 | integer | — |
| tenant_name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-084"></a>

### internal_handler.meEnvVarRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| name | 否 | string | — |
| sandbox_config_id | 否 | string | — |
| skill_id | 否 | string | — |
| value | 否 | string | Value is unused by the delete endpoints. Clearing a value is a delete<br>rather than a write of "", so a member always has one unambiguous way to<br>revoke. |

[返回数据结构目录](#models)

<a id="model-085"></a>

### internal_handler.platformAPIKeyCreateRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| capabilities | 否 | array&lt;string&gt; | — |
| expires_at_unix | 否 | integer | — |
| name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-086"></a>

### internal_handler.registerByInviteRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| email | 是 | string | — |
| password | 是 | string | 最短长度：6 |
| token | 是 | string | — |
| username | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-087"></a>

### internal_handler.resolveAncestorsRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| resource_ids | 否 | array&lt;string&gt; | — |

[返回数据结构目录](#models)

<a id="model-088"></a>

### internal_handler.sandboxConfigRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| config | 否 | [types.TenantSandboxConfig](#model-226) | — |
| description | 否 | string | — |
| name | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-089"></a>

### internal_handler.skillPatchRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| enabled | 否 | boolean | Enabled is a pointer because its absence is not a request to disable the<br>skill; a body may carry envs instead. |
| envs | 否 | map&lt;string, string&gt; | Envs is a pointer to a map because "sent an empty object" and "did not<br>mention envs" are different requests: the first clears what it names,<br>the second must leave every stored value alone. |

[返回数据结构目录](#models)

<a id="model-090"></a>

### internal_handler.skillSourceRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| source | 否 | string | Source is exactly one of: "@owner/slug" or a slash-free slug (ClawHub),<br>a github.com / gitlab.com / skills.sh / clawhub / skillhub page URL, a<br>ClawHub skills-sh catalog page or "skills-sh:owner/repo/slug" locator, or<br>a direct zip/SKILL.md URL. Bare "owner/slug" is rejected: it is both a<br>ClawHub id and a GitHub repo. The fetch carries no credential. |

[返回数据结构目录](#models)

<a id="model-091"></a>

### internal_handler.storageBackendRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| config | 否 | [types.StorageBackendConfig](#model-216) | — |
| name | 是 | string | — |
| provider | 是 | string | — |
| status | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-092"></a>

### internal_handler.updateLastFAQImportResultDisplayStatusRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| display_status | 是 | string | 可选值：["open", "close"] |

[返回数据结构目录](#models)

<a id="model-093"></a>

### internal_handler.updateMemberRoleRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| role | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-094"></a>

### internal_handler.updateMyPreferencesRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| browser_search_instructions | 否 | string | 最大长度：4000 |
| last_active_tenant_id | 否 | integer | LastActiveTenantID lets clients persist "after a fresh login,<br>drop me back into this workspace" across devices. The SPA sends<br>this after every tenant switch; POST /auth/switch-tenant records<br>the same preference server-side. Send a positive workspace id to<br>set / replace, or 0 to clear. Membership is validated at next<br>login, not here. Nil = field omitted from the PATCH and stays<br>untouched. |

[返回数据结构目录](#models)

<a id="model-095"></a>

### internal_handler_session.AttachmentUpload

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| data | 否 | string | Base64-encoded file content |
| file_name | 否 | string | Original filename |
| file_size | 否 | integer | File size in bytes |

[返回数据结构目录](#models)

<a id="model-096"></a>

### internal_handler_session.CreateKnowledgeQARequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| agent_enabled | 否 | boolean | Whether agent mode is enabled for this request |
| agent_id | 否 | string | Selected custom agent ID (backend resolves shared agent and its workspace from share relation) |
| agent_source_tenant_id | 否 | integer | Optional disambiguator; backend still verifies the share relation |
| attachment_ids | 否 | array&lt;string&gt; | Pre-uploaded session-scoped document IDs |
| attachment_uploads | 否 | array&lt;[internal_handler_session.AttachmentUpload](#model-095)&gt; | Attached files (documents, audio, etc.) |
| channel | 否 | string | Source channel: "web", "api", "im", etc. |
| disable_title | 否 | boolean | Whether to disable auto title generation |
| images | 否 | array&lt;[internal_handler_session.ImageAttachment](#model-100)&gt; | Attached images for multimodal chat |
| knowledge_base_ids | 否 | array&lt;string&gt; | Selected knowledge base ID for this request |
| knowledge_ids | 否 | array&lt;string&gt; | Selected knowledge ID for this request |
| local_browser_enabled | 否 | boolean | Browser source |
| mcp_service_ids | 否 | array&lt;string&gt; | Per-request MCP services selected via @mention |
| mentioned_items | 否 | array&lt;[internal_handler_session.MentionedItemRequest](#model-101)&gt; | @mentioned knowledge bases and files |
| query | 否 | string | Query text; may be empty only when an image or file is attached |
| question_origin | 否 | allOf([types.QuestionOrigin](#model-191)) | QuestionOrigin is the knowledge source of a picked suggested question. |
| reasoning_effort | 否 | string | ReasoningEffort overrides thinking for this request; empty inherits the agent configuration. |
| skill_names | 否 | array&lt;string&gt; | Per-request Skills selected via @mention |
| suggestion_attribution | 否 | [types.SuggestionAttribution](#model-221) | — |
| summary_model_id | 否 | string | Optional summary model ID for this request (overrides session default) |
| tag_ids | 否 | array&lt;string&gt; | @mentioned tag IDs (display/debug; scoped via MentionedItems) |
| web_search_enabled | 否 | boolean | Whether web search is enabled for this request |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "agent_enabled": {
      "description": "Whether agent mode is enabled for this request",
      "type": "boolean"
    },
    "agent_id": {
      "description": "Selected custom agent ID (backend resolves shared agent and its workspace from share relation)",
      "type": "string"
    },
    "agent_source_tenant_id": {
      "description": "Optional disambiguator; backend still verifies the share relation",
      "type": "integer"
    },
    "attachment_ids": {
      "description": "Pre-uploaded session-scoped document IDs",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "attachment_uploads": {
      "description": "Attached files (documents, audio, etc.)",
      "type": "array",
      "items": {
        "$ref": "#/definitions/internal_handler_session.AttachmentUpload"
      }
    },
    "channel": {
      "description": "Source channel: \"web\", \"api\", \"im\", etc.",
      "type": "string"
    },
    "disable_title": {
      "description": "Whether to disable auto title generation",
      "type": "boolean"
    },
    "images": {
      "description": "Attached images for multimodal chat",
      "type": "array",
      "items": {
        "$ref": "#/definitions/internal_handler_session.ImageAttachment"
      }
    },
    "knowledge_base_ids": {
      "description": "Selected knowledge base ID for this request",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "knowledge_ids": {
      "description": "Selected knowledge ID for this request",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "local_browser_enabled": {
      "description": "Browser source",
      "type": "boolean"
    },
    "mcp_service_ids": {
      "description": "Per-request MCP services selected via @mention",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "mentioned_items": {
      "description": "@mentioned knowledge bases and files",
      "type": "array",
      "items": {
        "$ref": "#/definitions/internal_handler_session.MentionedItemRequest"
      }
    },
    "query": {
      "description": "Query text; may be empty only when an image or file is attached",
      "type": "string"
    },
    "question_origin": {
      "description": "QuestionOrigin is the knowledge source of a picked suggested question.",
      "allOf": [
        {
          "$ref": "#/definitions/types.QuestionOrigin"
        }
      ]
    },
    "reasoning_effort": {
      "description": "ReasoningEffort overrides thinking for this request; empty inherits the agent configuration.",
      "type": "string"
    },
    "skill_names": {
      "description": "Per-request Skills selected via @mention",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "suggestion_attribution": {
      "$ref": "#/definitions/types.SuggestionAttribution"
    },
    "summary_model_id": {
      "description": "Optional summary model ID for this request (overrides session default)",
      "type": "string"
    },
    "tag_ids": {
      "description": "@mentioned tag IDs (display/debug; scoped via MentionedItems)",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "web_search_enabled": {
      "description": "Whether web search is enabled for this request",
      "type": "boolean"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-097"></a>

### internal_handler_session.CreateSessionRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| description | 否 | string | Description for the session (optional) |
| project_dir | 否 | string | ProjectDir is an optional Lite host-sandbox binding. When set it must<br>be an absolute path already present in the user-approved ProjectDirs<br>list. Empty means the session gets an auto-allocated workspace. |
| title | 否 | string | Title for the session (optional) |

[返回数据结构目录](#models)

<a id="model-098"></a>

### internal_handler_session.ForkSessionRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| message_id | 是 | string | MessageID is the message to branch at. A user message copies history<br>strictly before it (the client prefills that question). An assistant<br>message copies history through that answer so the branch continues after it. |
| title | 否 | string | Title is optional. Empty falls back to the source title plus a suffix. |

[返回数据结构目录](#models)

<a id="model-099"></a>

### internal_handler_session.GenerateTitleRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| messages | 是 | array&lt;[types.Message](#model-173)&gt; | Messages to use as context for title generation |

[返回数据结构目录](#models)

<a id="model-100"></a>

### internal_handler_session.ImageAttachment

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| caption | 否 | string | VLM analysis result (context-aware, single call) |
| data | 否 | string | base64 data URI from frontend (data:image/png;base64,...) |
| url | 否 | string | serving URL after saving to storage |

[返回数据结构目录](#models)

<a id="model-101"></a>

### internal_handler_session.MentionedItemRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| id | 否 | string | — |
| kb_id | 否 | string | Parent knowledge base for file/tag mentions |
| kb_name | 否 | string | Display name for parent KB |
| kb_type | 否 | string | "document" or "faq" (only for kb type) |
| name | 否 | string | — |
| service_id | 否 | string | Parent MCP service for MCP tool mentions |
| skill_name | 否 | string | Preloaded agent skill name |
| type | 否 | string | "kb", "file", "tag", "mcp", "skill" |

[返回数据结构目录](#models)

<a id="model-102"></a>

### internal_handler_session.RewindSessionRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| message_id | 是 | string | MessageID is the message to rewind to. A user message deletes itself and<br>everything after (the client prefills that question). An assistant<br>message keeps itself and deletes everything after. |

[返回数据结构目录](#models)

<a id="model-103"></a>

### internal_handler_session.SearchKnowledgeRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| disable_keywords_match | 否 | boolean | Vector recall only |
| disable_vector_match | 否 | boolean | Keyword recall only |
| keyword_threshold | 否 | number | Minimum keyword score |
| knowledge_base_id | 否 | string | Single knowledge base ID (for backward compatibility) |
| knowledge_base_ids | 否 | array&lt;string&gt; | IDs of knowledge bases to search (multi-KB support) |
| knowledge_ids | 否 | array&lt;string&gt; | IDs of specific knowledge (files) to search |
| match_count | 否 | integer | Number of results to return |
| mentioned_items | 否 | array&lt;[internal_handler_session.MentionedItemRequest](#model-101)&gt; | Optional scoped tag mentions |
| query | 是 | string | Query text to search for |
| rerank | 否 | allOf([types.RerankOptions](#model-197)) | Rerank override |
| tag_ids | 否 | array&lt;string&gt; | Tag IDs for filtering within a single KB |
| vector_threshold | 否 | number | Optional overrides of the tenant retrieval config. Omitted fields keep it. |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "required": [
    "query"
  ],
  "properties": {
    "disable_keywords_match": {
      "description": "Vector recall only",
      "type": "boolean"
    },
    "disable_vector_match": {
      "description": "Keyword recall only",
      "type": "boolean"
    },
    "keyword_threshold": {
      "description": "Minimum keyword score",
      "type": "number"
    },
    "knowledge_base_id": {
      "description": "Single knowledge base ID (for backward compatibility)",
      "type": "string"
    },
    "knowledge_base_ids": {
      "description": "IDs of knowledge bases to search (multi-KB support)",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "knowledge_ids": {
      "description": "IDs of specific knowledge (files) to search",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "match_count": {
      "description": "Number of results to return",
      "type": "integer"
    },
    "mentioned_items": {
      "description": "Optional scoped tag mentions",
      "type": "array",
      "items": {
        "$ref": "#/definitions/internal_handler_session.MentionedItemRequest"
      }
    },
    "query": {
      "description": "Query text to search for",
      "type": "string"
    },
    "rerank": {
      "description": "Rerank override",
      "allOf": [
        {
          "$ref": "#/definitions/types.RerankOptions"
        }
      ]
    },
    "tag_ids": {
      "description": "Tag IDs for filtering within a single KB",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "vector_threshold": {
      "description": "Optional overrides of the tenant retrieval config. Omitted fields keep it.",
      "type": "number"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-104"></a>

### internal_handler_session.SteerMessageRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| channel | 否 | string | — |
| delivery | 否 | string | Delivery is "after" (default) or "inject". See the constants above. |
| expected_assistant_message_id | 否 | string | Optional for older clients. New clients pin delivery to the run they see<br>and supply a stable ID so a consume event may precede the HTTP response. |
| mentioned_items | 否 | array&lt;[internal_handler_session.MentionedItemRequest](#model-101)&gt; | — |
| query | 是 | string | — |
| steer_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-105"></a>

### internal_handler_session.StopSessionRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| message_id | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-106"></a>

### internal_handler_session.batchDeleteRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| delete_all | 否 | boolean | — |
| ids | 否 | array&lt;string&gt; | — |

[返回数据结构目录](#models)

<a id="model-107"></a>

### types.ASRConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| enabled | 否 | boolean | — |
| language | 否 | string | optional: language hint for transcription |
| model_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-108"></a>

### types.AdminCreateUserRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| email | 是 | string | — |
| password | 否 | string | — |
| username | 是 | string | 最短长度：2；最大长度：50 |

[返回数据结构目录](#models)

<a id="model-109"></a>

### types.AgentStep

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| intermediate_answer | 否 | boolean | IntermediateAnswer preserves a plain answer followed by a loop-end steer.<br>The canonical final answer is still stored in Message.Content. |
| iteration | 否 | integer | Iteration number (0-indexed) |
| reasoning_content | 否 | string | ReasoningContent stores the OpenAI-protocol reasoning_content emitted by the<br>model in this round. Persisted on AgentStep so cross-turn replay can put it<br>back on the assistant message — required by MiMo / DeepSeek V3.2+ thinking<br>mode, ignored by providers that don't recognize the field. |
| reasoning_metadata | 否 | [types.ProviderMetadata](#model-189) | — |
| reasoning_signature | 否 | string | ReasoningSignature / ReasoningMetadata are the provider artifacts that<br>must accompany ReasoningContent on replay (Anthropic signatures,<br>OpenAI Responses encrypted reasoning items). |
| thought | 否 | string | LLM's reasoning/thinking (Think phase) |
| timestamp | 否 | string | When this step occurred |
| tool_calls | 否 | array&lt;[types.ToolCall](#model-228)&gt; | Tools called in this step (Act phase) |
| truncated | 否 | boolean | Truncated marks the round the completion-token cap cut off. It rides in<br>the agent_steps JSON so a reloaded, shared or re-opened conversation can<br>still show that the answer stops mid-sentence by design, rather than<br>looking finished. Live streaming carries the same fact on the answer<br>event; this is what survives the round trip. |
| user_messages_before | 否 | array&lt;string&gt; | UserMessagesBefore records consumed steer rows in delivery order, before<br>this model response. Unlike timestamps, this remains unambiguous on replay. |

[返回数据结构目录](#models)

<a id="model-110"></a>

### types.AuditLog

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| action | 否 | string | — |
| actor_role | 否 | string | — |
| actor_user_id | 否 | string | — |
| created_at | 否 | string | — |
| details | 否 | array&lt;integer&gt; | — |
| id | 否 | integer | — |
| outcome | 否 | string | — |
| request_method | 否 | string | — |
| request_path | 否 | string | — |
| scope_id | 否 | string | — |
| scope_type | 否 | string | — |
| target_id | 否 | string | — |
| target_type | 否 | string | — |
| target_user_id | 否 | string | — |
| tenant_id | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-111"></a>

### types.AutoTagConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| enabled | 否 | boolean | — |
| max_tags | 否 | integer | — |
| model_id | 否 | string | — |
| skip_if_tagged | 否 | boolean | SkipIfTagged leaves documents that already carry tags untouched, so a<br>deliberate manual classification is not diluted by model guesses. It is<br>a pointer because the default is true: rows written before this field<br>existed decode to nil and must not silently flip to "always append". |

[返回数据结构目录](#models)

<a id="model-112"></a>

### types.COSEngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| app_id | 否 | string | — |
| bucket_name | 否 | string | — |
| path_prefix | 否 | string | — |
| region | 否 | string | — |
| secret_id | 否 | string | — |
| secret_key | 否 | string | — |
| temp_bucket_name | 否 | string | — |
| temp_region | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-113"></a>

### types.ChatHistoryConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| embedding_model_id | 否 | string | EmbeddingModelID is the ID of the embedding model used for vectorizing chat messages.<br>Once messages have been indexed, the model cannot be changed (requires re-indexing). |
| enabled | 否 | boolean | Enabled controls whether chat history indexing is active |
| knowledge_base_id | 否 | string | KnowledgeBaseID is the auto-managed hidden knowledge base for chat history.<br>This is set internally when the feature is first enabled; users should not set this directly. |

[返回数据结构目录](#models)

<a id="model-114"></a>

### types.ChunkingConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| child_chunk_size | 否 | integer | ChildChunkSize is the size of child chunks used for embedding (default: 384).<br>Only used when EnableParentChild is true. |
| chunk_overlap | 否 | integer | Chunk overlap |
| chunk_size | 否 | integer | Chunk size |
| enable_parent_child | 否 | boolean | EnableParentChild enables two-level parent-child chunking strategy.<br>When enabled, large parent chunks provide context while small child chunks<br>are used for vector matching. Retrieval matches on child but returns parent content. |
| languages | 否 | array&lt;string&gt; | Languages hints the heuristic patterns. Empty = auto-detect from content.<br>Examples: ["de"], ["en", "zh"]. |
| parent_chunk_size | 否 | integer | ParentChunkSize is the size of parent chunks (default: 4096).<br>Only used when EnableParentChild is true. |
| parser_engine_rules | 否 | array&lt;[types.ParserEngineRule](#model-188)&gt; | ParserEngineRules configures which parser engine to use for each file type.<br>When empty, DefaultParserEngine is used (builtin/simple routing, except<br>types that only a specific engine can parse: ppt/pptx fall back to<br>markitdown). A linked anydoc binding is preferred for every type it<br>converts. |
| separators | 否 | array&lt;string&gt; | Separators |
| strategy | 否 | string | Strategy selects the adaptive chunking tier. Empty / "legacy" preserves<br>the historical recursive splitter; "auto" lets a profiler pick between<br>heading-aware, heuristic and recursive tiers; "heading" / "heuristic" /<br>"recursive" pin the tier explicitly. |
| table_metadata_instructions | 否 | string | TableMetadataInstructions contains optional business guidance used when<br>generating searchable summaries for CSV/Excel tables. The system-owned<br>output contract remains fixed; these instructions only add domain context. |
| token_limit | 否 | integer | TokenLimit caps chunk size in approximate tokens. 0 = use ChunkSize<br>as a character count. |

[返回数据结构目录](#models)

<a id="model-115"></a>

### types.ConnectionConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| addr | 否 | string | Common |
| api_key | 否 | string | AES-GCM encrypted |
| database | 否 | string | Database name used by engines that support database-level namespaces<br>(currently Milvus, Tencent VectorDB, and Doris). |
| grpc_address | 否 | string | Weaviate |
| host | 否 | string | Qdrant |
| http_port | 否 | integer | Doris: HTTP port for Stream Load API (FE default 8030).<br>Addr is reused for the MySQL protocol "host:9030"; HTTPPort + the host of Addr<br>together form the FE HTTP endpoint used by Stream Load. |
| insecure_skip_verify | 否 | boolean | InsecureSkipVerify disables TLS certificate verification when<br>talking to the backing store over HTTPS. Defaults to false<br>(secure). Set to true ONLY for self-signed development clusters;<br>production deployments should provide trusted certificates via<br>the system CA pool. Cross-driver applicable but currently only<br>the OpenSearch driver (Phase 3) reads this field. Note: this<br>differs from the Qdrant-specific UseTLS below, which *enables*<br>TLS on gRPC connections — InsecureSkipVerify only controls<br>*verification* of an already-TLS connection. |
| password | 否 | string | AES-GCM encrypted |
| port | 否 | integer | — |
| scheme | 否 | string | — |
| use_default_connection | 否 | boolean | Postgres |
| use_tls | 否 | boolean | — |
| username | 否 | string | — |
| version | 否 | string | Version is the detected server version (e.g., "7.10.1", "16.2", "1.12.6").<br>Auto-populated by TestConnection on successful connectivity check. |

[返回数据结构目录](#models)

<a id="model-116"></a>

### types.ContextConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| compression_strategy | 否 | string | Compression strategy: "sliding_window" or "smart" |
| max_tokens | 否 | integer | Maximum tokens allowed in LLM context |
| recent_message_count | 否 | integer | For sliding_window: number of messages to keep<br>For smart: number of recent messages to keep uncompressed |
| summarize_threshold | 否 | integer | Summarize threshold: number of messages before summarization |

[返回数据结构目录](#models)

<a id="model-117"></a>

### types.CreateOrganizationRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| avatar | 否 | string | optional avatar URL；最大长度：512 |
| description | 否 | string | 最大长度：1000 |
| invite_code_validity_days | 否 | integer | optional: 0=never, 1, 7, 30; default 7 |
| member_limit | 否 | integer | optional: max members; 0=unlimited; default 50 |
| name | 是 | string | 最短长度：1；最大长度：255 |

[返回数据结构目录](#models)

<a id="model-118"></a>

### types.CredentialsConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| weknoracloud | 否 | [types.WeKnoraCloudCredentials](#model-241) | — |

[返回数据结构目录](#models)

<a id="model-119"></a>

### types.CubeEgressRule

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| audit | 否 | string | Audit is none &#124; metadata &#124; full. Empty uses the server default. |
| deny | 否 | boolean | Deny inverts the action, which defaults to allow. A deny rule still<br>needs Host or SNI: the target has to reach CubeEgress for it to answer<br>with a request-level 403 instead of the network layer dropping it. |
| host | 否 | string | — |
| inject | 否 | array&lt;[types.CubeHeaderInject](#model-120)&gt; | Inject adds credential headers on allowed HTTPS requests. |
| methods | 否 | array&lt;string&gt; | — |
| name | 否 | string | — |
| path | 否 | string | — |
| scheme | 否 | string | — |
| sni | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-120"></a>

### types.CubeHeaderInject

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| format | 否 | string | — |
| header | 否 | string | — |
| secret | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-121"></a>

### types.CubeSandboxConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| api_key | 否 | string | 加密 |
| api_url | 否 | string | — |
| cube_sandbox_ttl_seconds | 否 | integer | — |
| dns_servers | 否 | array&lt;string&gt; | DNSServers are Cube template nameserver IPs. Empty uses Cubelet's default. |
| http_timeout_sec | 否 | integer | HTTPTimeoutSec bounds each HTTP call to the sandbox control plane.<br>0 means use the built-in default (30s), never the deployment's value. |
| proxy_url | 否 | string | — |
| sandbox_domain | 否 | string | — |
| template_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-122"></a>

### types.CustomAgentConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| agent_mode | 否 | string | ===== Basic Settings =====<br>Agent mode: "quick-answer" for RAG mode, "smart-reasoning" for ReAct agent mode |
| agent_type | 否 | string | AgentType is a preset category under smart-reasoning mode that pre-fills<br>system prompt, allowed tools and recommended KB compatibility.<br>Valid values: "rag-qa", "wiki-qa", "hybrid-rag-wiki", "custom".<br>Empty / unknown values are treated as "custom" (no preset applied).<br>Ignored for quick-answer mode. |
| allowed_tools | 否 | array&lt;string&gt; | Allowed tools (only for agent type) |
| asr_model_id | 否 | string | ASR model ID for audio transcription (optional) |
| attachment_image_understanding | 否 | boolean | AttachmentImageUnderstanding enables VLM OCR fallback for image-only /<br>scanned documents (PDF/PPT whose pages are images). Disabled by default<br>because it materially increases parse latency; only triggers when the<br>extracted text is below a threshold and a VLM model is configured. |
| attachment_ocr_max_pages | 否 | integer | AttachmentOCRMaxPages caps how many pages of a scanned / image-only<br>document this agent sends to the VLM for OCR. 0 falls back to the global<br>default (WEKNORA_CHAT_ATTACHMENT_OCR_MAX_PAGES). More pages means higher<br>coverage but slower parsing and more VLM cost. |
| attachment_parse_wait_timeout_sec | 否 | integer | AttachmentParseWaitTimeoutSec bounds, in seconds, how long a chat turn<br>waits for this agent's still-parsing attachments before proceeding with<br>only the finished ones. 0 falls back to the global default<br>(WEKNORA_CHAT_ATTACHMENT_WAIT_TIMEOUT_SEC). |
| audio_upload_enabled | 否 | boolean | Whether audio upload (ASR transcription) is enabled for this agent (default: false) |
| chat_parser_engine_rules | 否 | array&lt;[types.ParserEngineRule](#model-188)&gt; | ===== Chat Attachment Parsing Settings =====<br>ChatParserEngineRules selects parser engines for session-scoped chat<br>attachments by file type. Takes precedence over the tenant-level<br>ParserEngineConfig.ChatParserEngineRules; an explicit per-request<br>parser_engine still overrides both. |
| citation_enabled | 否 | boolean | Whether final answers include knowledge/web source citations. Nil defaults to true<br>so agents saved before this option was introduced keep their existing behavior. |
| context_template | 否 | string | Context template for normal mode (how to format retrieved chunks) |
| context_template_id | 否 | string | ContextTemplateID references a template ID in prompt_templates/ YAML files.<br>If set and ContextTemplate is empty, the template content is resolved at request time for saved agents. |
| data_analysis_enabled | 否 | boolean | ===== Data Analysis Settings =====<br>Whether to run the legacy in-pipeline DuckDB SQL data-analysis stage when<br>the retrieved chunks include CSV/Excel files. This issues an extra LLM<br>call to generate a SQL query and is disabled by default because most<br>quick-answer / RAG-style agents do not want the added latency. |
| embedding_top_k | 否 | integer | ===== Retrieval Strategy Settings (for both modes) =====<br>Embedding/Vector retrieval top K |
| enable_query_expansion | 否 | boolean | ===== Advanced Settings (mainly for normal mode) =====<br>Whether to enable query expansion |
| enable_rewrite | 否 | boolean | Whether to enable query rewrite for multi-turn conversations |
| fallback_prompt | 否 | string | Fallback prompt (when FallbackStrategy is "model") |
| fallback_response | 否 | string | Fixed fallback response (when FallbackStrategy is "fixed") |
| fallback_strategy | 否 | string | Fallback strategy: "fixed" for fixed response, "model" for model generation |
| faq_direct_answer_threshold | 否 | number | FAQ direct answer threshold - if similarity &gt; this value, use FAQ answer directly |
| faq_priority_enabled | 否 | boolean | ===== FAQ Strategy Settings =====<br>Whether FAQ priority strategy is enabled (FAQ answers prioritized over document chunks) |
| faq_score_boost | 否 | number | FAQ score boost multiplier - FAQ results score multiplied by this factor |
| history_turns | 否 | integer | Number of history turns to keep in context. Quick-answer only; smart-reasoning sizes history by context window |
| image_storage_provider | 否 | string | Storage provider for image uploads: "local", "minio", "cos", "tos", "s3", "oss", "ks3".<br>Empty means use the global/workspace default provider. |
| image_upload_enabled | 否 | boolean | ===== Image Upload / Multimodal Settings =====<br>Whether image upload is enabled for this agent (default: false) |
| intent_prompts | 否 | map&lt;string, string&gt; | IntentPrompts holds per-intent system prompt overrides for non-retrieval<br>intents (greeting, chitchat, etc.). Empty values fall back to templates<br>under config/prompt_templates/intent_prompts.yaml. |
| kb_selection_mode | 否 | string | ===== Knowledge Base Settings =====<br>Knowledge base selection mode: "all" = all KBs, "selected" = specific KBs, "none" = no KB |
| keyword_threshold | 否 | number | Keyword retrieval threshold |
| knowledge_bases | 否 | array&lt;string&gt; | Associated knowledge base IDs (only used when KBSelectionMode is "selected") |
| llm_call_timeout | 否 | integer | Timeout for a single LLM call in seconds (0 = use global default) |
| max_completion_tokens | 否 | integer | Maximum completion tokens. Quick-answer uses this for the RAG answer.<br>Smart-reasoning ReAct rounds send this value as-is (zero becomes<br>DefaultMaxCompletionTokens at call time: 4096, or 24576 with a sandbox). |
| max_iterations | 否 | integer | ===== Agent Mode Settings =====<br>Maximum iterations for the ReAct loop. Zero is unset (filled with a<br>default). A negative value is unlimited: the loop runs until the model<br>stops, the user cancels, or another guard fires. |
| mcp_auth_wait_timeout | 否 | integer | MCPAuthWaitTimeout is how many seconds to wait for in-conversation OAuth<br>authorization before skipping. &lt;=0 uses the gate's configured timeout. |
| mcp_selection_mode | 否 | string | MCP service selection mode: "all" = all enabled MCP services, "selected" = specific services, "none" = no MCP |
| mcp_services | 否 | array&lt;string&gt; | Selected MCP service IDs (only used when MCPSelectionMode is "selected") |
| memory_enabled | 否 | boolean | Whether this agent may read the user's long-term memory. Nil inherits<br>the workspace setting; false opts a single agent out of memory even when<br>the workspace has it on. There is no "on" that overrides the workspace. |
| model_id | 否 | string | ===== Model Settings =====<br>Model ID to use for conversations |
| multi_turn_enabled | 否 | boolean | ===== Multi-turn Conversation Settings =====<br>Whether multi-turn conversation is enabled |
| query_understand_model_id | 否 | string | Dedicated chat model ID for the query-understanding (rewrite + intent) step.<br>When empty, the main conversation ModelID is used as a fallback. |
| question_suggestions | 否 | allOf([types.QuestionSuggestionConfig](#model-192)) | ===== Conversation Question Suggestions =====<br>QuestionSuggestions owns both the static/knowledge-backed prompts shown<br>before the first user turn and the contextual follow-up questions shown<br>after a completed assistant answer. |
| reasoning_effort | 否 | string | ReasoningEffort selects the thinking intensity (off &#124; auto &#124; minimal &#124;<br>low &#124; medium &#124; high &#124; xhigh &#124; max). Empty keeps the boolean Thinking<br>semantics: true means "auto". See internal/models/api.ReasoningEffort. |
| rerank_model_id | 否 | string | ReRank model ID for retrieval |
| rerank_threshold | 否 | number | Rerank threshold |
| rerank_top_k | 否 | integer | Rerank top K |
| retain_retrieval_history | 否 | boolean | Whether to retain retrieval history across turns |
| retrieve_kb_only_when_mentioned | 否 | boolean | Whether to retrieve knowledge base only when explicitly mentioned with @ (default: false)<br>When true, knowledge base retrieval only happens if user explicitly mentions KB/files with @<br>When false, knowledge base retrieval happens according to KBSelectionMode |
| rewrite_prompt_system | 否 | string | Rewrite prompt system message |
| rewrite_prompt_user | 否 | string | Rewrite prompt user message template |
| sandbox_config_id | 否 | string | ===== Sandbox Settings =====<br>SandboxConfigID selects which workspace sandbox config this agent's<br>skill scripts run on. Empty means sandbox execution is disabled.<br><br>This references the LOGICAL config, never a specific revision: keeping<br>the indirection here is what would let credential rotation happen<br>without re-pointing every agent (see the spec's §4.8). |
| selected_skills | 否 | array&lt;string&gt; | Selected skill names (only used when SkillsSelectionMode is "selected") |
| skills_selection_mode | 否 | string | ===== Skills Settings (only for smart-reasoning mode) =====<br>Skills selection mode: "all" = all installed skills, "selected" = specific skills, "none" = no skills |
| supported_file_types | 否 | array&lt;string&gt; | ===== File Type Restriction Settings =====<br>Supported file types for this agent (e.g., ["csv", "xlsx", "xls"])<br>Empty means all file types are supported<br>When set, only files with matching extensions can be used with this agent |
| system_prompt | 否 | string | System prompt for the agent (unified prompt, uses web_search_status placeholder for dynamic behavior) |
| system_prompt_id | 否 | string | SystemPromptID references a template ID in prompt_templates/ YAML files.<br>If set and SystemPrompt is empty, the template content is resolved at request time for saved agents. |
| temperature | 否 | number | Temperature for LLM (0-1) |
| thinking | 否 | boolean | Whether to enable thinking mode (for models that support extended thinking) |
| vector_threshold | 否 | number | Vector retrieval threshold |
| vlm_model_id | 否 | string | VLM model ID for image analysis (optional, falls back to workspace-level VLM) |
| web_fetch_enabled | 否 | boolean | Whether to auto-fetch full page content for reranked web search results |
| web_fetch_top_n | 否 | integer | Max number of pages to fetch after rerank (default: 3) |
| web_search_enabled | 否 | boolean | ===== Web Search Settings =====<br>Whether web search is enabled |
| web_search_max_results | 否 | integer | Maximum web search results |
| web_search_provider_id | 否 | string | WebSearchProviderID references a specific WebSearchProviderEntity.<br>If empty, the workspace's default provider (is_default=true) is used. |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "agent_mode": {
      "description": "===== Basic Settings =====\nAgent mode: \"quick-answer\" for RAG mode, \"smart-reasoning\" for ReAct agent mode",
      "type": "string"
    },
    "agent_type": {
      "description": "AgentType is a preset category under smart-reasoning mode that pre-fills\nsystem prompt, allowed tools and recommended KB compatibility.\nValid values: \"rag-qa\", \"wiki-qa\", \"hybrid-rag-wiki\", \"custom\".\nEmpty / unknown values are treated as \"custom\" (no preset applied).\nIgnored for quick-answer mode.",
      "type": "string"
    },
    "allowed_tools": {
      "description": "Allowed tools (only for agent type)",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "asr_model_id": {
      "description": "ASR model ID for audio transcription (optional)",
      "type": "string"
    },
    "attachment_image_understanding": {
      "description": "AttachmentImageUnderstanding enables VLM OCR fallback for image-only /\nscanned documents (PDF/PPT whose pages are images). Disabled by default\nbecause it materially increases parse latency; only triggers when the\nextracted text is below a threshold and a VLM model is configured.",
      "type": "boolean"
    },
    "attachment_ocr_max_pages": {
      "description": "AttachmentOCRMaxPages caps how many pages of a scanned / image-only\ndocument this agent sends to the VLM for OCR. 0 falls back to the global\ndefault (WEKNORA_CHAT_ATTACHMENT_OCR_MAX_PAGES). More pages means higher\ncoverage but slower parsing and more VLM cost.",
      "type": "integer"
    },
    "attachment_parse_wait_timeout_sec": {
      "description": "AttachmentParseWaitTimeoutSec bounds, in seconds, how long a chat turn\nwaits for this agent's still-parsing attachments before proceeding with\nonly the finished ones. 0 falls back to the global default\n(WEKNORA_CHAT_ATTACHMENT_WAIT_TIMEOUT_SEC).",
      "type": "integer"
    },
    "audio_upload_enabled": {
      "description": "Whether audio upload (ASR transcription) is enabled for this agent (default: false)",
      "type": "boolean"
    },
    "chat_parser_engine_rules": {
      "description": "===== Chat Attachment Parsing Settings =====\nChatParserEngineRules selects parser engines for session-scoped chat\nattachments by file type. Takes precedence over the tenant-level\nParserEngineConfig.ChatParserEngineRules; an explicit per-request\nparser_engine still overrides both.",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.ParserEngineRule"
      }
    },
    "citation_enabled": {
      "description": "Whether final answers include knowledge/web source citations. Nil defaults to true\nso agents saved before this option was introduced keep their existing behavior.",
      "type": "boolean"
    },
    "context_template": {
      "description": "Context template for normal mode (how to format retrieved chunks)",
      "type": "string"
    },
    "context_template_id": {
      "description": "ContextTemplateID references a template ID in prompt_templates/ YAML files.\nIf set and ContextTemplate is empty, the template content is resolved at request time for saved agents.",
      "type": "string"
    },
    "data_analysis_enabled": {
      "description": "===== Data Analysis Settings =====\nWhether to run the legacy in-pipeline DuckDB SQL data-analysis stage when\nthe retrieved chunks include CSV/Excel files. This issues an extra LLM\ncall to generate a SQL query and is disabled by default because most\nquick-answer / RAG-style agents do not want the added latency.",
      "type": "boolean"
    },
    "embedding_top_k": {
      "description": "===== Retrieval Strategy Settings (for both modes) =====\nEmbedding/Vector retrieval top K",
      "type": "integer"
    },
    "enable_query_expansion": {
      "description": "===== Advanced Settings (mainly for normal mode) =====\nWhether to enable query expansion",
      "type": "boolean"
    },
    "enable_rewrite": {
      "description": "Whether to enable query rewrite for multi-turn conversations",
      "type": "boolean"
    },
    "fallback_prompt": {
      "description": "Fallback prompt (when FallbackStrategy is \"model\")",
      "type": "string"
    },
    "fallback_response": {
      "description": "Fixed fallback response (when FallbackStrategy is \"fixed\")",
      "type": "string"
    },
    "fallback_strategy": {
      "description": "Fallback strategy: \"fixed\" for fixed response, \"model\" for model generation",
      "type": "string"
    },
    "faq_direct_answer_threshold": {
      "description": "FAQ direct answer threshold - if similarity > this value, use FAQ answer directly",
      "type": "number"
    },
    "faq_priority_enabled": {
      "description": "===== FAQ Strategy Settings =====\nWhether FAQ priority strategy is enabled (FAQ answers prioritized over document chunks)",
      "type": "boolean"
    },
    "faq_score_boost": {
      "description": "FAQ score boost multiplier - FAQ results score multiplied by this factor",
      "type": "number"
    },
    "history_turns": {
      "description": "Number of history turns to keep in context. Quick-answer only; smart-reasoning sizes history by context window",
      "type": "integer"
    },
    "image_storage_provider": {
      "description": "Storage provider for image uploads: \"local\", \"minio\", \"cos\", \"tos\", \"s3\", \"oss\", \"ks3\".\nEmpty means use the global/workspace default provider.",
      "type": "string"
    },
    "image_upload_enabled": {
      "description": "===== Image Upload / Multimodal Settings =====\nWhether image upload is enabled for this agent (default: false)",
      "type": "boolean"
    },
    "intent_prompts": {
      "description": "IntentPrompts holds per-intent system prompt overrides for non-retrieval\nintents (greeting, chitchat, etc.). Empty values fall back to templates\nunder config/prompt_templates/intent_prompts.yaml.",
      "type": "object",
      "additionalProperties": {
        "type": "string"
      }
    },
    "kb_selection_mode": {
      "description": "===== Knowledge Base Settings =====\nKnowledge base selection mode: \"all\" = all KBs, \"selected\" = specific KBs, \"none\" = no KB",
      "type": "string"
    },
    "keyword_threshold": {
      "description": "Keyword retrieval threshold",
      "type": "number"
    },
    "knowledge_bases": {
      "description": "Associated knowledge base IDs (only used when KBSelectionMode is \"selected\")",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "llm_call_timeout": {
      "description": "Timeout for a single LLM call in seconds (0 = use global default)",
      "type": "integer"
    },
    "max_completion_tokens": {
      "description": "Maximum completion tokens. Quick-answer uses this for the RAG answer.\nSmart-reasoning ReAct rounds send this value as-is (zero becomes\nDefaultMaxCompletionTokens at call time: 4096, or 24576 with a sandbox).",
      "type": "integer"
    },
    "max_iterations": {
      "description": "===== Agent Mode Settings =====\nMaximum iterations for the ReAct loop. Zero is unset (filled with a\ndefault). A negative value is unlimited: the loop runs until the model\nstops, the user cancels, or another guard fires.",
      "type": "integer"
    },
    "mcp_auth_wait_timeout": {
      "description": "MCPAuthWaitTimeout is how many seconds to wait for in-conversation OAuth\nauthorization before skipping. <=0 uses the gate's configured timeout.",
      "type": "integer"
    },
    "mcp_selection_mode": {
      "description": "MCP service selection mode: \"all\" = all enabled MCP services, \"selected\" = specific services, \"none\" = no MCP",
      "type": "string"
    },
    "mcp_services": {
      "description": "Selected MCP service IDs (only used when MCPSelectionMode is \"selected\")",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "memory_enabled": {
      "description": "Whether this agent may read the user's long-term memory. Nil inherits\nthe workspace setting; false opts a single agent out of memory even when\nthe workspace has it on. There is no \"on\" that overrides the workspace.",
      "type": "boolean"
    },
    "model_id": {
      "description": "===== Model Settings =====\nModel ID to use for conversations",
      "type": "string"
    },
    "multi_turn_enabled": {
      "description": "===== Multi-turn Conversation Settings =====\nWhether multi-turn conversation is enabled",
      "type": "boolean"
    },
    "query_understand_model_id": {
      "description": "Dedicated chat model ID for the query-understanding (rewrite + intent) step.\nWhen empty, the main conversation ModelID is used as a fallback.",
      "type": "string"
    },
    "question_suggestions": {
      "description": "===== Conversation Question Suggestions =====\nQuestionSuggestions owns both the static/knowledge-backed prompts shown\nbefore the first user turn and the contextual follow-up questions shown\nafter a completed assistant answer.",
      "allOf": [
        {
          "$ref": "#/definitions/types.QuestionSuggestionConfig"
        }
      ]
    },
    "reasoning_effort": {
      "description": "ReasoningEffort selects the thinking intensity (off | auto | minimal |\nlow | medium | high | xhigh | max). Empty keeps the boolean Thinking\nsemantics: true means \"auto\". See internal/models/api.ReasoningEffort.",
      "type": "string"
    },
    "rerank_model_id": {
      "description": "ReRank model ID for retrieval",
      "type": "string"
    },
    "rerank_threshold": {
      "description": "Rerank threshold",
      "type": "number"
    },
    "rerank_top_k": {
      "description": "Rerank top K",
      "type": "integer"
    },
    "retain_retrieval_history": {
      "description": "Whether to retain retrieval history across turns",
      "type": "boolean"
    },
    "retrieve_kb_only_when_mentioned": {
      "description": "Whether to retrieve knowledge base only when explicitly mentioned with @ (default: false)\nWhen true, knowledge base retrieval only happens if user explicitly mentions KB/files with @\nWhen false, knowledge base retrieval happens according to KBSelectionMode",
      "type": "boolean"
    },
    "rewrite_prompt_system": {
      "description": "Rewrite prompt system message",
      "type": "string"
    },
    "rewrite_prompt_user": {
      "description": "Rewrite prompt user message template",
      "type": "string"
    },
    "sandbox_config_id": {
      "description": "===== Sandbox Settings =====\nSandboxConfigID selects which workspace sandbox config this agent's\nskill scripts run on. Empty means sandbox execution is disabled.\n\nThis references the LOGICAL config, never a specific revision: keeping\nthe indirection here is what would let credential rotation happen\nwithout re-pointing every agent (see the spec's §4.8).",
      "type": "string"
    },
    "selected_skills": {
      "description": "Selected skill names (only used when SkillsSelectionMode is \"selected\")",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "skills_selection_mode": {
      "description": "===== Skills Settings (only for smart-reasoning mode) =====\nSkills selection mode: \"all\" = all installed skills, \"selected\" = specific skills, \"none\" = no skills",
      "type": "string"
    },
    "supported_file_types": {
      "description": "===== File Type Restriction Settings =====\nSupported file types for this agent (e.g., [\"csv\", \"xlsx\", \"xls\"])\nEmpty means all file types are supported\nWhen set, only files with matching extensions can be used with this agent",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "system_prompt": {
      "description": "System prompt for the agent (unified prompt, uses web_search_status placeholder for dynamic behavior)",
      "type": "string"
    },
    "system_prompt_id": {
      "description": "SystemPromptID references a template ID in prompt_templates/ YAML files.\nIf set and SystemPrompt is empty, the template content is resolved at request time for saved agents.",
      "type": "string"
    },
    "temperature": {
      "description": "Temperature for LLM (0-1)",
      "type": "number"
    },
    "thinking": {
      "description": "Whether to enable thinking mode (for models that support extended thinking)",
      "type": "boolean"
    },
    "vector_threshold": {
      "description": "Vector retrieval threshold",
      "type": "number"
    },
    "vlm_model_id": {
      "description": "VLM model ID for image analysis (optional, falls back to workspace-level VLM)",
      "type": "string"
    },
    "web_fetch_enabled": {
      "description": "Whether to auto-fetch full page content for reranked web search results",
      "type": "boolean"
    },
    "web_fetch_top_n": {
      "description": "Max number of pages to fetch after rerank (default: 3)",
      "type": "integer"
    },
    "web_search_enabled": {
      "description": "===== Web Search Settings =====\nWhether web search is enabled",
      "type": "boolean"
    },
    "web_search_max_results": {
      "description": "Maximum web search results",
      "type": "integer"
    },
    "web_search_provider_id": {
      "description": "WebSearchProviderID references a specific WebSearchProviderEntity.\nIf empty, the workspace's default provider (is_default=true) is used.",
      "type": "string"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-123"></a>

### types.DataSource

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| config | 否 | array&lt;integer&gt; | Encrypted configuration (API credentials, tokens, etc.)<br>Stored as JSON with AES-256-GCM encryption |
| conflict_strategy | 否 | string | Conflict resolution strategy: overwrite or skip |
| created_at | 否 | string | Creation timestamp |
| deleted_at | 否 | allOf([gorm.DeletedAt](#model-016)) | Soft delete timestamp |
| error_message | 否 | string | Error message if status is "error" |
| id | 否 | string | Unique identifier |
| knowledge_base_id | 否 | string | Target knowledge base ID |
| last_sync_at | 否 | string | Last successful sync timestamp |
| last_sync_cursor | 否 | array&lt;integer&gt; | Cursor or state for incremental sync (connector-specific) |
| last_sync_result | 否 | array&lt;integer&gt; | Summary of last sync result |
| latest_sync_log | 否 | allOf([types.SyncLog](#model-222)) | Latest sync log (not stored in DB, populated on query) |
| name | 否 | string | User-friendly name |
| status | 否 | string | Current status: active, paused, error |
| sync_deletions | 否 | boolean | Whether to sync deletions from source |
| sync_log_retention_days | 否 | integer | Number of days to keep sync logs (default: 30) |
| sync_mode | 否 | string | Sync mode: "incremental" (recommended) or "full" |
| sync_schedule | 否 | string | Cron expression for scheduled syncs (e.g., "0 */6 * * *" = every 6 hours) |
| tenant_id | 否 | integer | Workspace ID for multi-workspace isolation |
| total_items_synced | 否 | integer | Total items synced (not stored in DB, calculated on query) |
| type | 否 | string | Connector type (feishu, notion, confluence, etc.) |
| updated_at | 否 | string | Last update timestamp |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "config": {
      "description": "Encrypted configuration (API credentials, tokens, etc.)\nStored as JSON with AES-256-GCM encryption",
      "type": "array",
      "items": {
        "type": "integer"
      }
    },
    "conflict_strategy": {
      "description": "Conflict resolution strategy: overwrite or skip",
      "type": "string"
    },
    "created_at": {
      "description": "Creation timestamp",
      "type": "string"
    },
    "deleted_at": {
      "description": "Soft delete timestamp",
      "allOf": [
        {
          "$ref": "#/definitions/gorm.DeletedAt"
        }
      ]
    },
    "error_message": {
      "description": "Error message if status is \"error\"",
      "type": "string"
    },
    "id": {
      "description": "Unique identifier",
      "type": "string"
    },
    "knowledge_base_id": {
      "description": "Target knowledge base ID",
      "type": "string"
    },
    "last_sync_at": {
      "description": "Last successful sync timestamp",
      "type": "string"
    },
    "last_sync_cursor": {
      "description": "Cursor or state for incremental sync (connector-specific)",
      "type": "array",
      "items": {
        "type": "integer"
      }
    },
    "last_sync_result": {
      "description": "Summary of last sync result",
      "type": "array",
      "items": {
        "type": "integer"
      }
    },
    "latest_sync_log": {
      "description": "Latest sync log (not stored in DB, populated on query)",
      "allOf": [
        {
          "$ref": "#/definitions/types.SyncLog"
        }
      ]
    },
    "name": {
      "description": "User-friendly name",
      "type": "string"
    },
    "status": {
      "description": "Current status: active, paused, error",
      "type": "string"
    },
    "sync_deletions": {
      "description": "Whether to sync deletions from source",
      "type": "boolean"
    },
    "sync_log_retention_days": {
      "description": "Number of days to keep sync logs (default: 30)",
      "type": "integer"
    },
    "sync_mode": {
      "description": "Sync mode: \"incremental\" (recommended) or \"full\"",
      "type": "string"
    },
    "sync_schedule": {
      "description": "Cron expression for scheduled syncs (e.g., \"0 */6 * * *\" = every 6 hours)",
      "type": "string"
    },
    "tenant_id": {
      "description": "Workspace ID for multi-workspace isolation",
      "type": "integer"
    },
    "total_items_synced": {
      "description": "Total items synced (not stored in DB, calculated on query)",
      "type": "integer"
    },
    "type": {
      "description": "Connector type (feishu, notion, confluence, etc.)",
      "type": "string"
    },
    "updated_at": {
      "description": "Last update timestamp",
      "type": "string"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-124"></a>

### types.DockerSandboxConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| cpu_limit | 否 | number | CPULimit is the number of CPU cores one sandbox may use. 0 uses the<br>built-in default. |
| host | 否 | string | Host is the daemon endpoint in DOCKER_HOST form. Empty means the local<br>unix socket. |
| http_timeout_sec | 否 | integer | HTTPTimeoutSec bounds each Engine API call. 0 uses the built-in default. |
| idle_ttl_seconds | 否 | integer | IdleTTLSeconds is how long a session container may go unused before it<br>is reclaimed. The daemon has no idle timeout of its own, so this is what<br>stops an abandoned session from pinning host memory indefinitely. |
| image | 否 | string | — |
| memory_limit_mb | 否 | integer | MemoryLimitMB caps one sandbox's memory. 0 uses the built-in default. |
| network_mode | 否 | string | NetworkMode is the Docker network sandboxes join: "bridge" (default) or<br>"none" for no egress. Nothing else is accepted — host and container:<br>modes share another namespace outright, and a named network is usually<br>the deployment's own compose network, which would put the sandbox next<br>to Postgres and Redis. |
| pids_limit | 否 | integer | PidsLimit caps how many processes one sandbox may run. 0 uses the<br>built-in default. |
| runtime | 否 | string | Runtime selects an alternative OCI runtime such as "runsc" (gVisor).<br>Empty uses the daemon default. |
| tls_cert_path | 否 | string | TLSCertPath is a directory on the WeKnora host containing ca.pem,<br>cert.pem and key.pem. Required when Host is a TCP endpoint. |

[返回数据结构目录](#models)

<a id="model-125"></a>

### types.E2BHostRule

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| headers | 否 | map&lt;string, string&gt; | Headers values are credentials and are encrypted at rest; names stay<br>readable so operators can see which headers are injected. |
| host | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-126"></a>

### types.E2BSandboxConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| api_key | 否 | string | 加密 |
| api_url | 否 | string | — |
| e2b_sandbox_ttl_seconds | 否 | integer | — |
| http_timeout_sec | 否 | integer | HTTPTimeoutSec bounds each HTTP call to the sandbox control plane.<br>0 means use the built-in default (30s), never the deployment's value. |
| proxy_url | 否 | string | ProxyURL is the data-plane gateway that fronts envd. E2B Cloud resolves<br>"&lt;port&gt;-&lt;sandboxID&gt;.&lt;sandbox_domain&gt;" through public DNS and TLS, so it<br>needs no value here. Self-hosted E2B-compatible control planes usually<br>serve every sandbox from one gateway address and expect the sandbox<br>authority in the Host header; setting this makes WeKnora dial the<br>gateway directly instead of requiring wildcard DNS and a certificate<br>for the sandbox domain. An "http://" gateway also downgrades the<br>data-plane scheme, which the E2B SDK otherwise pins to https. |
| sandbox_domain | 否 | string | — |
| template_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-127"></a>

### types.EmbeddingParameters

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| dimension | 否 | integer | — |
| supports_dimension_override | 否 | boolean | — |
| truncate_prompt_tokens | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-128"></a>

### types.ExtractConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| custom_instructions | 否 | string | CustomInstructions adds domain-specific extraction guidance while the<br>system keeps ownership of the structured graph output protocol. |
| enabled | 否 | boolean | — |
| nodes | 否 | array&lt;[types.GraphNode](#model-137)&gt; | — |
| relations | 否 | array&lt;[types.GraphRelation](#model-138)&gt; | — |
| tags | 否 | array&lt;string&gt; | — |
| text | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-129"></a>

### types.FAQBatchUpsertPayload

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| dry_run | 否 | boolean | 仅验证，不实际导入 |
| entries | 是 | array&lt;[types.FAQEntryPayload](#model-133)&gt; | — |
| knowledge_id | 否 | string | — |
| mode | 否 | string | 可选值：["append", "replace"] |
| task_id | 否 | string | 可选，如果不传则自动生成UUID |

[返回数据结构目录](#models)

<a id="model-130"></a>

### types.FAQConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| index_mode | 否 | string | — |
| question_index_mode | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-131"></a>

### types.FAQEntryFieldsBatchUpdate

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| by_id | 否 | map&lt;string, [types.FAQEntryFieldsUpdate](#model-132)&gt; | ByID 按条目ID更新，key为条目ID (seq_id) |
| by_tag | 否 | map&lt;string, [types.FAQEntryFieldsUpdate](#model-132)&gt; | ByTag 按Tag批量更新，key为TagID (seq_id) |
| exclude_ids | 否 | array&lt;integer&gt; | ExcludeIDs 在ByTag操作中需要排除的ID列表 (seq_id) |

[返回数据结构目录](#models)

<a id="model-132"></a>

### types.FAQEntryFieldsUpdate

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| is_enabled | 否 | boolean | — |
| is_recommended | 否 | boolean | — |
| tag_id | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-133"></a>

### types.FAQEntryPayload

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| answer_strategy | 否 | string | — |
| answers | 否 | array&lt;string&gt; | — |
| id | 否 | integer | ID 可选，用于数据迁移时指定 seq_id（必须小于自增起始值 100000000） |
| is_enabled | 否 | boolean | — |
| is_recommended | 否 | boolean | — |
| negative_questions | 否 | array&lt;string&gt; | — |
| similar_questions | 否 | array&lt;string&gt; | — |
| standard_question | 是 | string | — |
| tag_id | 否 | integer | — |
| tag_name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-134"></a>

### types.FAQSearchRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| first_priority_tag_ids | 否 | array&lt;integer&gt; | 第一优先级标签ID列表，限定命中范围，优先级最高 |
| match_count | 否 | integer | — |
| only_recommended | 否 | boolean | 是否仅返回推荐的条目 |
| query_text | 是 | string | — |
| second_priority_tag_ids | 否 | array&lt;integer&gt; | 第二优先级标签ID列表，限定命中范围，优先级低于第一优先级 |
| vector_threshold | 否 | number | — |

[返回数据结构目录](#models)

<a id="model-135"></a>

### types.FollowUpSuggestionConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| additional_instruction | 否 | string | — |
| allow_regenerate | 否 | boolean | — |
| categories | 否 | array&lt;string&gt; | — |
| count | 否 | integer | — |
| enabled | 否 | boolean | — |
| knowledge_fallback | 否 | boolean | — |
| max_context_turns | 否 | integer | — |
| mode | 否 | string | — |
| model_id | 否 | string | — |
| suppress_on_fallback | 否 | boolean | — |
| suppress_when_answer_asks_question | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-136"></a>

### types.GalleryUserPrefs

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| mode | 否 | string | Mode is "all" (every search-eligible field active) or "custom"<br>(per-field Status governs). Empty = "all". |
| status | 否 | map&lt;string, string&gt; | Status maps a namespaced attribute id ("builtin:caption") to<br>"on"/"off". Only consulted in custom mode; an unrecorded field is<br>off. Source-declared on/off does not exist — activation is always<br>the user's own record. |

[返回数据结构目录](#models)

<a id="model-137"></a>

### types.GraphNode

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| attributes | 否 | array&lt;string&gt; | — |
| chunks | 否 | array&lt;string&gt; | — |
| name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-138"></a>

### types.GraphRelation

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| node1 | 否 | string | — |
| node2 | 否 | string | — |
| type | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-139"></a>

### types.ImageActionsConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| ocr | 否 | [types.ImageOCRAction](#model-141) | — |

[返回数据结构目录](#models)

<a id="model-140"></a>

### types.ImageAttrCondition

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| is | 否 | string | — |
| prop | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-141"></a>

### types.ImageOCRAction

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| on | 否 | array&lt;[types.ImageAttrCondition](#model-140)&gt; | On is the list of attribute conditions that enable OCR. Matching any one<br>turns OCR on. |
| on_unobserved | 否 | boolean | OnUnobserved is the OCR decision used when the observation does not<br>answer for one of the attributes the policy reads — the attribute is<br>absent because the model skipped the line, or because the value it wrote<br>was not one of the allowed ones. The default is true: an incomplete<br>observation keeps extracting, so a missed block of text costs one extra<br>call instead of being lost. Turn it off only with a model reliable enough<br>to answer every attribute line. |

[返回数据结构目录](#models)

<a id="model-142"></a>

### types.ImageProcessingConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| image_actions | 否 | allOf([types.ImageActionsConfig](#model-139)) | ImageActions overrides the built-in attribute→work table (see<br>DefaultImageActions). A nil value keeps the conservative default: run OCR<br>for block text, data visuals, and unobserved images. |
| image_attrs_enabled | 否 | boolean | ImageAttrsEnabled turns the attribute-observed image pipeline on for this<br>knowledge base. It is off by default on purpose, so upgrading an<br>existing deployment never changes what happens to documents already<br>being ingested: with it off every image is described and OCR'd exactly<br>as before. When on, the describe round also observes image attributes and<br>the attribute policy decides whether OCR runs for it. A single upload can<br>override it per document through<br>KnowledgeProcessOverrides.ImageAttrsEnabled. |
| model_id | 否 | string | Model ID |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "image_actions": {
      "description": "ImageActions overrides the built-in attribute→work table (see\nDefaultImageActions). A nil value keeps the conservative default: run OCR\nfor block text, data visuals, and unobserved images.",
      "allOf": [
        {
          "$ref": "#/definitions/types.ImageActionsConfig"
        }
      ]
    },
    "image_attrs_enabled": {
      "description": "ImageAttrsEnabled turns the attribute-observed image pipeline on for this\nknowledge base. It is off by default on purpose, so upgrading an\nexisting deployment never changes what happens to documents already\nbeing ingested: with it off every image is described and OCR'd exactly\nas before. When on, the describe round also observes image attributes and\nthe attribute policy decides whether OCR runs for it. A single upload can\noverride it per document through\nKnowledgeProcessOverrides.ImageAttrsEnabled.",
      "type": "boolean"
    },
    "model_id": {
      "description": "Model ID",
      "type": "string"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-143"></a>

### types.IndexConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| buckets_num | 否 | integer | Doris: number of buckets per table (DISTRIBUTED BY HASH ... BUCKETS N) |
| collection_name | 否 | string | Milvus |
| collection_prefix | 否 | string | Qdrant, Weaviate |
| desired_shard_count | 否 | integer | Weaviate: number of shards per collection |
| hnsw_ef_construction | 否 | integer | OpenSearch: HNSW index-build candidate list size |
| hnsw_ef_search | 否 | integer | OpenSearch: HNSW search candidate list size (faiss; lucene reads at query time) |
| hnsw_m | 否 | integer | --- OpenSearch k-NN HNSW fields ---<br>All omitempty so other engines' serialized IndexConfig is unchanged.<br>Zero / empty values fall back to the driver defaults in buildInternalCfg. |
| index_name | 否 | string | --- Existing fields --- |
| knn_engine | 否 | string | OpenSearch: k-NN backend ("lucene" &#124; "faiss") |
| number_of_replicas | 否 | integer | ES, OpenSearch |
| number_of_shards | 否 | integer | ES, OpenSearch |
| replica_number | 否 | integer | Milvus LoadCollection / Tencent VectorDB CreateCollection replicas |
| replication_factor | 否 | integer | Qdrant, Weaviate: number of replicas |
| replication_num | 否 | integer | Doris: replication_num PROPERTIES |
| shard_number | 否 | integer | --- Scalability fields --- |
| shards_num | 否 | integer | Milvus: number of shards per collection (CreateCollection) |

[返回数据结构目录](#models)

<a id="model-144"></a>

### types.IndexingStrategy

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| graph_enabled | 否 | boolean | GraphEnabled enables knowledge graph entity/relation extraction |
| keyword_enabled | 否 | boolean | KeywordEnabled enables keyword-based (BM25) search |
| vector_enabled | 否 | boolean | VectorEnabled enables semantic vector embedding and search |
| wiki_enabled | 否 | boolean | WikiEnabled enables automatic wiki page generation from documents |

[返回数据结构目录](#models)

<a id="model-145"></a>

### types.InviteMemberRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| representative_user_id | 否 | string | RepresentativeUserID is accepted for compatibility and ignored: a<br>direct add attaches no user of the enrolled workspace, since the<br>inviter must not choose whose details the roster shows. |
| role | 是 | string | Role to assign: admin/editor/viewer |
| tenant_id | 否 | integer | TenantID is the workspace to enrol as an org member. Preferred field. |
| user_id | 否 | string | UserID is retained for backward compatibility. When set without<br>TenantID, the handler resolves the user's TenantID. |

[返回数据结构目录](#models)

<a id="model-146"></a>

### types.JoinByOrganizationIDRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| message | 否 | string | Optional message for join request；最大长度：500 |
| organization_id | 是 | string | — |
| role | 否 | string | Optional: requested role (admin/editor/viewer); default viewer |

[返回数据结构目录](#models)

<a id="model-147"></a>

### types.JoinOrganizationRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| invite_code | 是 | string | 最短长度：8；最大长度：32 |

[返回数据结构目录](#models)

<a id="model-148"></a>

### types.KS3EngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| access_key | 否 | string | — |
| bucket_name | 否 | string | — |
| endpoint | 否 | string | — |
| path_prefix | 否 | string | — |
| region | 否 | string | — |
| secret_key | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-149"></a>

### types.KnowledgeBase

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| asr_config | 否 | allOf([types.ASRConfig](#model-107)) | ASR config (Automatic Speech Recognition) |
| auto_tag_config | 否 | allOf([types.AutoTagConfig](#model-111)) | AutoTagConfig controls asynchronous association of existing tags after parsing. |
| chunk_count | 否 | integer | Chunk count (not stored in database, calculated on query) |
| chunking_config | 否 | allOf([types.ChunkingConfig](#model-114)) | Chunking configuration |
| created_at | 否 | string | Creation time of the knowledge base |
| creator_id | 否 | string | CreatorID records the user ID of whoever originally created the KB.<br>Used by the workspace-level RBAC middleware to let Contributors edit<br>their own KBs without granting them access to everyone else's.<br>Nullable for backward compatibility with rows created before the<br>RBAC migration backfilled the column to the workspace Owner. |
| creator_name | 否 | string | CreatorName 是 CreatorID 对应用户的展示名（username / email 等），<br>仅在列表场景由 handler 批量回填，不落库；为空表示创建者无法解析（用户已删除、<br>CreatorID 为空的老数据等）。前端用它在卡片来源徽章上做 mine vs workspace 的二分。 |
| deleted_at | 否 | allOf([gorm.DeletedAt](#model-016)) | Deletion time of the knowledge base |
| description | 否 | string | Description of the knowledge base |
| embedding_model_id | 否 | string | ID of the embedding model |
| extract_config | 否 | allOf([types.ExtractConfig](#model-128)) | Extract config |
| faq_config | 否 | allOf([types.FAQConfig](#model-130)) | FAQConfig stores FAQ specific configuration such as indexing strategy |
| generated_profile | 否 | allOf([types.KnowledgeBaseProfile](#model-151)) | GeneratedProfile is the machine-generated description: a gist, merged<br>topics, typical questions and the aggregate snapshot they came from. It<br>never overwrites the user-authored Description; both are shown to agents. |
| id | 否 | string | Unique identifier of the knowledge base |
| image_processing_config | 否 | allOf([types.ImageProcessingConfig](#model-142)) | Image processing configuration |
| indexing_strategy | 否 | allOf([types.IndexingStrategy](#model-144)) | IndexingStrategy controls which indexing pipelines are active for this knowledge base.<br>Pipelines: vector search, keyword search, wiki generation, knowledge graph extraction. |
| is_pinned | 否 | boolean | IsPinned and PinnedAt are computed per-caller from user_kb_pins<br>(see migration 000050). They used to be stored on the row itself,<br>which made pinning a workspace-wide ordering decision gated behind<br>the kb-edit RBAC guard. The columns are still present in legacy<br>schemas for rollback safety but are no longer read or written by<br>the application — both fields are tagged `gorm:"-"` so GORM<br>ignores them on every CRUD call and the list handler stamps them<br>after enriching with the caller's pin set. |
| is_processing | 否 | boolean | IsProcessing indicates if there is a processing import task (for FAQ type knowledge bases) |
| is_temporary | 否 | boolean | Whether this knowledge base is temporary (ephemeral) and should be hidden from UI |
| knowledge_count | 否 | integer | Knowledge count (not stored in database, calculated on query) |
| name | 否 | string | Name of the knowledge base |
| pinned_at | 否 | string | PinnedAt records when the current caller pinned this knowledge<br>base; nil when they have not. |
| processing_count | 否 | integer | ProcessingCount indicates the number of knowledge items being processed (for document type knowledge bases) |
| profile_config | 否 | allOf([types.KnowledgeBaseProfileConfig](#model-152)) | ProfileConfig controls automatic generation of the knowledge-base<br>description from per-document profiles (document knowledge bases only). |
| question_generation_config | 否 | allOf([types.QuestionGenerationConfig](#model-190)) | QuestionGenerationConfig stores question generation configuration for document knowledge bases |
| share_count | 否 | integer | ShareCount indicates the number of organizations this knowledge base is shared with (not stored in database) |
| storage_backend_id | 否 | string | StorageBackendID binds this KB to one concrete storage instance. The<br>legacy provider field remains readable during migration only. |
| storage_config | 否 | allOf([types.StorageConfig](#model-217)) | Deprecated: legacy COS config column. Kept for backward compatibility with old data. |
| storage_provider_config | 否 | allOf([types.StorageProviderConfig](#model-219)) | Storage provider config (new): only stores provider selection; credentials from workspace StorageEngineConfig |
| summary_model_id | 否 | string | Summary model ID |
| tenant_id | 否 | integer | Workspace ID |
| type | 否 | string | Type of the knowledge base (document, faq, etc.) |
| updated_at | 否 | string | Last updated time of the knowledge base |
| vector_store_id | 否 | string | VectorStoreID references the VectorStore this knowledge base is bound to.<br>When nil, the KB falls back to the workspace's effective engines derived from<br>the RETRIEVE_DRIVER environment variable (env store flow).<br>This field is set once at creation time and must not be modified afterwards;<br>enforcement lives at the GORM layer (`&lt;-:create`) plus the service-layer<br>KB update path, which omits this field from its update DTO. |
| vlm_config | 否 | allOf([types.VLMConfig](#model-239)) | VLM config |
| wiki_config | 否 | allOf([types.WikiConfig](#model-245)) | WikiConfig stores wiki-specific configuration (only for wiki type knowledge bases) |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "asr_config": {
      "description": "ASR config (Automatic Speech Recognition)",
      "allOf": [
        {
          "$ref": "#/definitions/types.ASRConfig"
        }
      ]
    },
    "auto_tag_config": {
      "description": "AutoTagConfig controls asynchronous association of existing tags after parsing.",
      "allOf": [
        {
          "$ref": "#/definitions/types.AutoTagConfig"
        }
      ]
    },
    "chunk_count": {
      "description": "Chunk count (not stored in database, calculated on query)",
      "type": "integer"
    },
    "chunking_config": {
      "description": "Chunking configuration",
      "allOf": [
        {
          "$ref": "#/definitions/types.ChunkingConfig"
        }
      ]
    },
    "created_at": {
      "description": "Creation time of the knowledge base",
      "type": "string"
    },
    "creator_id": {
      "description": "CreatorID records the user ID of whoever originally created the KB.\nUsed by the workspace-level RBAC middleware to let Contributors edit\ntheir own KBs without granting them access to everyone else's.\nNullable for backward compatibility with rows created before the\nRBAC migration backfilled the column to the workspace Owner.",
      "type": "string"
    },
    "creator_name": {
      "description": "CreatorName 是 CreatorID 对应用户的展示名（username / email 等），\n仅在列表场景由 handler 批量回填，不落库；为空表示创建者无法解析（用户已删除、\nCreatorID 为空的老数据等）。前端用它在卡片来源徽章上做 mine vs workspace 的二分。",
      "type": "string"
    },
    "deleted_at": {
      "description": "Deletion time of the knowledge base",
      "allOf": [
        {
          "$ref": "#/definitions/gorm.DeletedAt"
        }
      ]
    },
    "description": {
      "description": "Description of the knowledge base",
      "type": "string"
    },
    "embedding_model_id": {
      "description": "ID of the embedding model",
      "type": "string"
    },
    "extract_config": {
      "description": "Extract config",
      "allOf": [
        {
          "$ref": "#/definitions/types.ExtractConfig"
        }
      ]
    },
    "faq_config": {
      "description": "FAQConfig stores FAQ specific configuration such as indexing strategy",
      "allOf": [
        {
          "$ref": "#/definitions/types.FAQConfig"
        }
      ]
    },
    "generated_profile": {
      "description": "GeneratedProfile is the machine-generated description: a gist, merged\ntopics, typical questions and the aggregate snapshot they came from. It\nnever overwrites the user-authored Description; both are shown to agents.",
      "allOf": [
        {
          "$ref": "#/definitions/types.KnowledgeBaseProfile"
        }
      ]
    },
    "id": {
      "description": "Unique identifier of the knowledge base",
      "type": "string"
    },
    "image_processing_config": {
      "description": "Image processing configuration",
      "allOf": [
        {
          "$ref": "#/definitions/types.ImageProcessingConfig"
        }
      ]
    },
    "indexing_strategy": {
      "description": "IndexingStrategy controls which indexing pipelines are active for this knowledge base.\nPipelines: vector search, keyword search, wiki generation, knowledge graph extraction.",
      "allOf": [
        {
          "$ref": "#/definitions/types.IndexingStrategy"
        }
      ]
    },
    "is_pinned": {
      "description": "IsPinned and PinnedAt are computed per-caller from user_kb_pins\n(see migration 000050). They used to be stored on the row itself,\nwhich made pinning a workspace-wide ordering decision gated behind\nthe kb-edit RBAC guard. The columns are still present in legacy\nschemas for rollback safety but are no longer read or written by\nthe application — both fields are tagged `gorm:\"-\"` so GORM\nignores them on every CRUD call and the list handler stamps them\nafter enriching with the caller's pin set.",
      "type": "boolean"
    },
    "is_processing": {
      "description": "IsProcessing indicates if there is a processing import task (for FAQ type knowledge bases)",
      "type": "boolean"
    },
    "is_temporary": {
      "description": "Whether this knowledge base is temporary (ephemeral) and should be hidden from UI",
      "type": "boolean"
    },
    "knowledge_count": {
      "description": "Knowledge count (not stored in database, calculated on query)",
      "type": "integer"
    },
    "name": {
      "description": "Name of the knowledge base",
      "type": "string"
    },
    "pinned_at": {
      "description": "PinnedAt records when the current caller pinned this knowledge\nbase; nil when they have not.",
      "type": "string"
    },
    "processing_count": {
      "description": "ProcessingCount indicates the number of knowledge items being processed (for document type knowledge bases)",
      "type": "integer"
    },
    "profile_config": {
      "description": "ProfileConfig controls automatic generation of the knowledge-base\ndescription from per-document profiles (document knowledge bases only).",
      "allOf": [
        {
          "$ref": "#/definitions/types.KnowledgeBaseProfileConfig"
        }
      ]
    },
    "question_generation_config": {
      "description": "QuestionGenerationConfig stores question generation configuration for document knowledge bases",
      "allOf": [
        {
          "$ref": "#/definitions/types.QuestionGenerationConfig"
        }
      ]
    },
    "share_count": {
      "description": "ShareCount indicates the number of organizations this knowledge base is shared with (not stored in database)",
      "type": "integer"
    },
    "storage_backend_id": {
      "description": "StorageBackendID binds this KB to one concrete storage instance. The\nlegacy provider field remains readable during migration only.",
      "type": "string"
    },
    "storage_config": {
      "description": "Deprecated: legacy COS config column. Kept for backward compatibility with old data.",
      "allOf": [
        {
          "$ref": "#/definitions/types.StorageConfig"
        }
      ]
    },
    "storage_provider_config": {
      "description": "Storage provider config (new): only stores provider selection; credentials from workspace StorageEngineConfig",
      "allOf": [
        {
          "$ref": "#/definitions/types.StorageProviderConfig"
        }
      ]
    },
    "summary_model_id": {
      "description": "Summary model ID",
      "type": "string"
    },
    "tenant_id": {
      "description": "Workspace ID",
      "type": "integer"
    },
    "type": {
      "description": "Type of the knowledge base (document, faq, etc.)",
      "type": "string"
    },
    "updated_at": {
      "description": "Last updated time of the knowledge base",
      "type": "string"
    },
    "vector_store_id": {
      "description": "VectorStoreID references the VectorStore this knowledge base is bound to.\nWhen nil, the KB falls back to the workspace's effective engines derived from\nthe RETRIEVE_DRIVER environment variable (env store flow).\nThis field is set once at creation time and must not be modified afterwards;\nenforcement lives at the GORM layer (`<-:create`) plus the service-layer\nKB update path, which omits this field from its update DTO.",
      "type": "string"
    },
    "vlm_config": {
      "description": "VLM config",
      "allOf": [
        {
          "$ref": "#/definitions/types.VLMConfig"
        }
      ]
    },
    "wiki_config": {
      "description": "WikiConfig stores wiki-specific configuration (only for wiki type knowledge bases)",
      "allOf": [
        {
          "$ref": "#/definitions/types.WikiConfig"
        }
      ]
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-150"></a>

### types.KnowledgeBaseConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| auto_tag_config | 否 | allOf([types.AutoTagConfig](#model-111)) | AutoTagConfig controls optional automatic association of existing KB tags. |
| chunking_config | 否 | allOf([types.ChunkingConfig](#model-114)) | Chunking configuration |
| faq_config | 否 | allOf([types.FAQConfig](#model-130)) | FAQ configuration (only for FAQ type knowledge bases) |
| image_processing_config | 否 | allOf([types.ImageProcessingConfig](#model-142)) | Image processing configuration.<br><br>nil means "no change" when updating, the same contract IndexingStrategy<br>uses. A request that does not mention this field must leave the image<br>settings alone: the attribute-observation switch and the attribute policy<br>are things a knowledge base accumulates, and a client that predates them<br>would otherwise reset the lot on any save. Sending an object — empty<br>included — replaces the whole configuration. |
| indexing_strategy | 否 | allOf([types.IndexingStrategy](#model-144)) | IndexingStrategy controls which indexing pipelines are active.<br>nil means "no change" when updating (preserves existing strategy). |
| profile_config | 否 | allOf([types.KnowledgeBaseProfileConfig](#model-152)) | ProfileConfig controls optional automatic knowledge-base description<br>generation. nil means "no change" when updating. |
| wiki_config | 否 | allOf([types.WikiConfig](#model-245)) | Wiki configuration (only for wiki-enabled knowledge bases) |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "auto_tag_config": {
      "description": "AutoTagConfig controls optional automatic association of existing KB tags.",
      "allOf": [
        {
          "$ref": "#/definitions/types.AutoTagConfig"
        }
      ]
    },
    "chunking_config": {
      "description": "Chunking configuration",
      "allOf": [
        {
          "$ref": "#/definitions/types.ChunkingConfig"
        }
      ]
    },
    "faq_config": {
      "description": "FAQ configuration (only for FAQ type knowledge bases)",
      "allOf": [
        {
          "$ref": "#/definitions/types.FAQConfig"
        }
      ]
    },
    "image_processing_config": {
      "description": "Image processing configuration.\n\nnil means \"no change\" when updating, the same contract IndexingStrategy\nuses. A request that does not mention this field must leave the image\nsettings alone: the attribute-observation switch and the attribute policy\nare things a knowledge base accumulates, and a client that predates them\nwould otherwise reset the lot on any save. Sending an object — empty\nincluded — replaces the whole configuration.",
      "allOf": [
        {
          "$ref": "#/definitions/types.ImageProcessingConfig"
        }
      ]
    },
    "indexing_strategy": {
      "description": "IndexingStrategy controls which indexing pipelines are active.\nnil means \"no change\" when updating (preserves existing strategy).",
      "allOf": [
        {
          "$ref": "#/definitions/types.IndexingStrategy"
        }
      ]
    },
    "profile_config": {
      "description": "ProfileConfig controls optional automatic knowledge-base description\ngeneration. nil means \"no change\" when updating.",
      "allOf": [
        {
          "$ref": "#/definitions/types.KnowledgeBaseProfileConfig"
        }
      ]
    },
    "wiki_config": {
      "description": "Wiki configuration (only for wiki-enabled knowledge bases)",
      "allOf": [
        {
          "$ref": "#/definitions/types.WikiConfig"
        }
      ]
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-151"></a>

### types.KnowledgeBaseProfile

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| aggregate_hash | 否 | string | AggregateHash identifies the aggregate the gist was generated from. A<br>mismatch with the live aggregate means the text is stale. |
| error | 否 | string | Error holds the last generation error when Status is failed. |
| generated_at | 否 | string | GeneratedAt records when the text was produced. |
| gist | 否 | string | Gist is a short statement of what the knowledge base covers. |
| model_id | 否 | string | ModelID records which chat model produced the text. |
| stats | 否 | allOf([types.KnowledgeBaseProfileStats](#model-153)) | Stats is the aggregate snapshot the text was generated from. |
| status | 否 | string | Status is one of the KnowledgeBaseProfileStatus* values. |
| topics | 否 | array&lt;string&gt; | Topics is the merged topic list (at most KnowledgeBaseProfileMaxTopics). |
| typical_questions | 否 | array&lt;string&gt; | TypicalQuestions lists questions this knowledge base can answer. |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "aggregate_hash": {
      "description": "AggregateHash identifies the aggregate the gist was generated from. A\nmismatch with the live aggregate means the text is stale.",
      "type": "string"
    },
    "error": {
      "description": "Error holds the last generation error when Status is failed.",
      "type": "string"
    },
    "generated_at": {
      "description": "GeneratedAt records when the text was produced.",
      "type": "string"
    },
    "gist": {
      "description": "Gist is a short statement of what the knowledge base covers.",
      "type": "string"
    },
    "model_id": {
      "description": "ModelID records which chat model produced the text.",
      "type": "string"
    },
    "stats": {
      "description": "Stats is the aggregate snapshot the text was generated from.",
      "allOf": [
        {
          "$ref": "#/definitions/types.KnowledgeBaseProfileStats"
        }
      ]
    },
    "status": {
      "description": "Status is one of the KnowledgeBaseProfileStatus* values.",
      "type": "string"
    },
    "topics": {
      "description": "Topics is the merged topic list (at most KnowledgeBaseProfileMaxTopics).",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "typical_questions": {
      "description": "TypicalQuestions lists questions this knowledge base can answer.",
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-152"></a>

### types.KnowledgeBaseProfileConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| custom_instructions | 否 | string | CustomInstructions is appended to the stable system prompt, e.g. the<br>audience the description should address or terms to keep. |
| enabled | 否 | boolean | — |
| model_id | 否 | string | ModelID overrides the chat model; empty falls back to the KB summary model. |

[返回数据结构目录](#models)

<a id="model-153"></a>

### types.KnowledgeBaseProfileStats

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| doc_types | 否 | array&lt;[types.NamedCount](#model-180)&gt; | DocTypes counts documents per model-assigned document type. |
| document_count | 否 | integer | DocumentCount is the number of eligible documents at generation time. |
| earliest_at | 否 | string | EarliestAt and LatestAt bound document creation times. |
| file_types | 否 | array&lt;[types.NamedCount](#model-180)&gt; | FileTypes counts documents per file extension. |
| folders | 否 | array&lt;string&gt; | Folders lists the top-level folder names present. |
| latest_at | 否 | string | — |
| profiled_count | 否 | integer | ProfiledCount is how many of them carried a document profile. |
| raw_topics | 否 | array&lt;[types.NamedCount](#model-180)&gt; | RawTopics counts documents per normalized topic keyword before merging. |
| tags | 否 | array&lt;[types.NamedCount](#model-180)&gt; | Tags counts documents per knowledge-base tag. |

[返回数据结构目录](#models)

<a id="model-154"></a>

### types.KnowledgeBaseShareResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| chunk_count | 否 | integer | — |
| created_at | 否 | string | — |
| id | 否 | string | — |
| knowledge_base_id | 否 | string | — |
| knowledge_base_name | 否 | string | — |
| knowledge_base_type | 否 | string | — |
| knowledge_count | 否 | integer | — |
| my_permission | 否 | string | Effective permission for current user = min(Permission, MyRoleInOrg) |
| my_role_in_org | 否 | string | Current user's role in this organization (admin/editor/viewer) |
| organization_id | 否 | string | — |
| organization_name | 否 | string | — |
| permission | 否 | string | Share permission (what the space was granted: viewer/editor) |
| require_approval | 否 | boolean | — |
| shared_by_user_id | 否 | string | — |
| shared_by_username | 否 | string | — |
| source_tenant_id | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-155"></a>

### types.KnowledgeMoveProgress

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| created_at | 否 | integer | 任务创建时间 |
| error | 否 | string | 错误信息 |
| failed | 否 | integer | 失败数 |
| message | 否 | string | 状态消息 |
| processed | 否 | integer | 已处理数 |
| progress | 否 | integer | 0-100 |
| source_kb_id | 否 | string | — |
| status | 否 | string | — |
| target_kb_id | 否 | string | — |
| task_id | 否 | string | — |
| total | 否 | integer | 总知识数 |
| updated_at | 否 | integer | 最后更新时间 |

[返回数据结构目录](#models)

<a id="model-156"></a>

### types.KnowledgeProcessOverrides

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| asr_config | 否 | [types.ASRConfig](#model-107) | — |
| chunking_config | 否 | [types.ChunkingConfig](#model-114) | — |
| enable_multimodel | 否 | boolean | — |
| extract_config | 否 | [types.ExtractConfig](#model-128) | — |
| graph_enabled | 否 | boolean | — |
| image_actions | 否 | allOf([types.ImageActionsConfig](#model-139)) | ImageActions overrides rows of the attribute-&gt;work table for this document<br>(merged per action key on top of the knowledge base's table).<br>nil keeps the knowledge base's setting. |
| image_attrs_enabled | 否 | boolean | ImageAttrsEnabled overrides the knowledge base's attribute-observed image<br>pipeline switch for this one document. nil keeps the knowledge base's<br>setting. |
| parser_engine_overrides | 否 | map&lt;string, string&gt; | ParserEngineOverrides passes key-value configuration to docreader parsers<br>(e.g. pdf_force_scanned=true). Merged with workspace-level overrides in the<br>parse pipeline; per-upload values take priority on conflict. |
| parser_engine_rules | 否 | array&lt;[types.ParserEngineRule](#model-188)&gt; | — |
| question_generation_config | 否 | [types.QuestionGenerationConfig](#model-190) | — |
| summary_enabled | 否 | boolean | SummaryEnabled defaults to true when omitted for backward compatibility. |
| vlm_config | 否 | [types.VLMConfig](#model-239) | — |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "asr_config": {
      "$ref": "#/definitions/types.ASRConfig"
    },
    "chunking_config": {
      "$ref": "#/definitions/types.ChunkingConfig"
    },
    "enable_multimodel": {
      "type": "boolean"
    },
    "extract_config": {
      "$ref": "#/definitions/types.ExtractConfig"
    },
    "graph_enabled": {
      "type": "boolean"
    },
    "image_actions": {
      "description": "ImageActions overrides rows of the attribute->work table for this document\n(merged per action key on top of the knowledge base's table).\nnil keeps the knowledge base's setting.",
      "allOf": [
        {
          "$ref": "#/definitions/types.ImageActionsConfig"
        }
      ]
    },
    "image_attrs_enabled": {
      "description": "ImageAttrsEnabled overrides the knowledge base's attribute-observed image\npipeline switch for this one document. nil keeps the knowledge base's\nsetting.",
      "type": "boolean"
    },
    "parser_engine_overrides": {
      "description": "ParserEngineOverrides passes key-value configuration to docreader parsers\n(e.g. pdf_force_scanned=true). Merged with workspace-level overrides in the\nparse pipeline; per-upload values take priority on conflict.",
      "type": "object",
      "additionalProperties": {
        "type": "string"
      }
    },
    "parser_engine_rules": {
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.ParserEngineRule"
      }
    },
    "question_generation_config": {
      "$ref": "#/definitions/types.QuestionGenerationConfig"
    },
    "summary_enabled": {
      "description": "SummaryEnabled defaults to true when omitted for backward compatibility.",
      "type": "boolean"
    },
    "vlm_config": {
      "$ref": "#/definitions/types.VLMConfig"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-157"></a>

### types.ListMembersResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| members | 否 | array&lt;[types.OrganizationMemberResponse](#model-185)&gt; | — |
| total | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-158"></a>

### types.ListOrganizationsResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| organizations | 否 | array&lt;[types.OrganizationResponse](#model-186)&gt; | — |
| resource_counts | 否 | allOf([types.ResourceCountsByOrgResponse](#model-199)) | 各空间内知识库/智能体数量，供列表侧栏展示 |
| total | 否 | integer | — |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "organizations": {
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.OrganizationResponse"
      }
    },
    "resource_counts": {
      "description": "各空间内知识库/智能体数量，供列表侧栏展示",
      "allOf": [
        {
          "$ref": "#/definitions/types.ResourceCountsByOrgResponse"
        }
      ]
    },
    "total": {
      "type": "integer"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-159"></a>

### types.ListSharesResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| shares | 否 | array&lt;[types.KnowledgeBaseShareResponse](#model-154)&gt; | — |
| total | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-160"></a>

### types.LocalEngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| path_prefix | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-161"></a>

### types.LoginRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| email | 是 | string | — |
| password | 是 | string | 最短长度：6 |

[返回数据结构目录](#models)

<a id="model-162"></a>

### types.LoginResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| active_tenant | 否 | allOf([types.Tenant](#model-225)) | ActiveTenant is the workspace whose ID is encoded in the issued JWT;<br>future requests are scoped to it until the client calls /auth/switch-tenant.<br>Defaults to the user's home workspace on a fresh login. |
| memberships | 否 | array&lt;[types.Membership](#model-170)&gt; | Memberships lists every workspace the user can authenticate into,<br>along with their role in each. Always populated (length 1 for users<br>who only belong to their home workspace) so frontends can render a<br>workspace switcher without a follow-up request. Serialised without<br>omitempty so the field is always present as a JSON array (possibly<br>empty) — the "always populated" contract relies on the server side<br>guaranteeing a non-nil slice. |
| message | 否 | string | — |
| refresh_token | 否 | string | — |
| success | 否 | boolean | — |
| token | 否 | string | — |
| user | 否 | [types.User](#model-236) | — |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "active_tenant": {
      "description": "ActiveTenant is the workspace whose ID is encoded in the issued JWT;\nfuture requests are scoped to it until the client calls /auth/switch-tenant.\nDefaults to the user's home workspace on a fresh login.",
      "allOf": [
        {
          "$ref": "#/definitions/types.Tenant"
        }
      ]
    },
    "memberships": {
      "description": "Memberships lists every workspace the user can authenticate into,\nalong with their role in each. Always populated (length 1 for users\nwho only belong to their home workspace) so frontends can render a\nworkspace switcher without a follow-up request. Serialised without\nomitempty so the field is always present as a JSON array (possibly\nempty) — the \"always populated\" contract relies on the server side\nguaranteeing a non-nil slice.",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.Membership"
      }
    },
    "message": {
      "type": "string"
    },
    "refresh_token": {
      "type": "string"
    },
    "success": {
      "type": "boolean"
    },
    "token": {
      "type": "string"
    },
    "user": {
      "$ref": "#/definitions/types.User"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-163"></a>

### types.MCPAdvancedConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| retry_count | 否 | integer | Number of retries, default: 3 |
| retry_delay | 否 | integer | Delay between retries in seconds, default: 1 |
| timeout | 否 | integer | Timeout in seconds, default: 30 |

[返回数据结构目录](#models)

<a id="model-164"></a>

### types.MCPAuthConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| api_key | 否 | string | — |
| api_key_header | 否 | string | APIKeyHeader is the header name that carries APIKey when AuthType is<br>api_key. Empty defaults to "X-API-Key". This is non-secret structural<br>config (the secret is APIKey), so it is not encrypted and is safe to echo<br>back in responses. It lets services that expect the key in a different<br>header (e.g. the raw token directly in "Authorization") work without<br>resorting to plaintext custom headers. |
| auth_server_metadata_url | 否 | string | AuthServerMetadataURL optionally pins the OAuth authorization server<br>metadata URL. When empty, the server is discovered automatically from<br>the MCP URL (RFC 9728 / RFC 8414). |
| auth_type | 否 | string | AuthType selects the authentication strategy. Empty ("") is treated as<br>none for backward compatibility with rows that pre-date this field. |
| custom_headers | 否 | map&lt;string, string&gt; | — |
| scopes | 否 | array&lt;string&gt; | Scopes are the OAuth scopes requested during authorization. Optional. |
| token | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-165"></a>

### types.MCPEnvVars

类型：map&lt;string, string&gt;

额外字段：string

[返回数据结构目录](#models)

<a id="model-166"></a>

### types.MCPHeaders

类型：map&lt;string, string&gt;

额外字段：string

[返回数据结构目录](#models)

<a id="model-167"></a>

### types.MCPService

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| advanced_config | 否 | [types.MCPAdvancedConfig](#model-163) | — |
| auth_config | 否 | [types.MCPAuthConfig](#model-164) | — |
| created_at | 否 | string | — |
| deleted_at | 否 | [gorm.DeletedAt](#model-016) | — |
| description | 否 | string | — |
| enabled | 否 | boolean | — |
| env_vars | 否 | allOf([types.MCPEnvVars](#model-165)) | Environment variables for stdio |
| headers | 否 | [types.MCPHeaders](#model-166) | — |
| id | 否 | string | — |
| is_builtin | 否 | boolean | Whether this is a builtin MCP service (visible to all workspaces) |
| name | 否 | string | — |
| stdio_config | 否 | allOf([types.MCPStdioConfig](#model-168)) | Required for stdio transport |
| tenant_id | 否 | integer | — |
| transport_type | 否 | string | — |
| updated_at | 否 | string | — |
| url | 否 | string | Optional: required for SSE/HTTP Streamable |
| usage_instructions | 否 | string | UsageInstructions is maintained locally and is not overwritten by directory refresh. |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "advanced_config": {
      "$ref": "#/definitions/types.MCPAdvancedConfig"
    },
    "auth_config": {
      "$ref": "#/definitions/types.MCPAuthConfig"
    },
    "created_at": {
      "type": "string"
    },
    "deleted_at": {
      "$ref": "#/definitions/gorm.DeletedAt"
    },
    "description": {
      "type": "string"
    },
    "enabled": {
      "type": "boolean"
    },
    "env_vars": {
      "description": "Environment variables for stdio",
      "allOf": [
        {
          "$ref": "#/definitions/types.MCPEnvVars"
        }
      ]
    },
    "headers": {
      "$ref": "#/definitions/types.MCPHeaders"
    },
    "id": {
      "type": "string"
    },
    "is_builtin": {
      "description": "Whether this is a builtin MCP service (visible to all workspaces)",
      "type": "boolean"
    },
    "name": {
      "type": "string"
    },
    "stdio_config": {
      "description": "Required for stdio transport",
      "allOf": [
        {
          "$ref": "#/definitions/types.MCPStdioConfig"
        }
      ]
    },
    "tenant_id": {
      "type": "integer"
    },
    "transport_type": {
      "type": "string"
    },
    "updated_at": {
      "type": "string"
    },
    "url": {
      "description": "Optional: required for SSE/HTTP Streamable",
      "type": "string"
    },
    "usage_instructions": {
      "description": "UsageInstructions is maintained locally and is not overwritten by directory refresh.",
      "type": "string"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-168"></a>

### types.MCPStdioConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| args | 否 | array&lt;string&gt; | Command arguments array |
| command | 否 | string | Command: "uvx" or "npx" |

[返回数据结构目录](#models)

<a id="model-169"></a>

### types.ManualKnowledgePayload

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| channel | 否 | string | — |
| content | 否 | string | — |
| process_config | 否 | [types.KnowledgeProcessOverrides](#model-156) | — |
| status | 否 | string | — |
| tag_ids | 否 | array&lt;string&gt; | — |
| title | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-170"></a>

### types.Membership

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| role | 否 | string | — |
| tenant_id | 否 | integer | — |
| tenant_name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-171"></a>

### types.MemoryConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| embedding_model_id | 否 | string | EmbeddingModelID is the single model used to score memory against a<br>question. It is pinned per workspace: knowledge bases each have their<br>own embedding model, and grabbing whichever one happens to be listed<br>first would mix incomparable vector spaces. Blank means semantic recall<br>is off and matching stays lexical. |
| enabled | 否 | boolean | Enabled defaults to false. Memory retains user statements across<br>sessions, so a workspace admin has to turn it on deliberately. |
| extract_delay_seconds | 否 | integer | ExtractDelaySeconds is how long a finished turn waits before<br>distillation runs. Waiting lets one model call cover the several<br>messages a user usually sends in a row. 0 means the default. |
| extract_instructions | 否 | string | ExtractInstructions are workspace-specific rules appended to the<br>distillation prompt, for policies the product cannot guess ("never record<br>customer names", "always note the environment a question is about"). |
| extract_min_interval_seconds | 否 | integer | ExtractMinIntervalSeconds is the floor between two distillation runs for<br>the same person, and exists purely to bound cost. It never drops a turn:<br>a turn arriving inside the interval is queued and picked up by the next<br>run. 0 means the default. |
| extract_model_id | 否 | string | ExtractModelID is the model used by the background extraction task.<br>Empty means "use the model the conversation itself used", which is what<br>the settings UI promises, so the extraction task must never fail merely<br>because this is blank. |
| interest_threshold | 否 | integer | InterestThreshold is how many separate conversations must touch a topic<br>before it becomes a stored interest. 0 means the default. Setting it to 1<br>records every topic on first sight, which is usually too noisy. |
| max_items | 否 | integer | MaxItems caps active items per subject. 0 means DefaultMemoryMaxItems. |
| retrieval_conditioning | 否 | boolean | RetrievalConditioning lets memory shape retrieval — query rewriting and<br>per-document ranking — rather than only being appended to the answer<br>prompt. This is where memory earns its keep in a knowledge-base product. |
| vector_recall | 否 | boolean | VectorRecall adds semantic similarity to memory recall. Nil means on<br>when an embedding model is reachable.<br><br>Lexical matching alone cannot find a memory the user has re-worded, which<br>is most of them: "回答直接给结论" and "别铺垫那么多" share no tokens. The<br>cost is one embedding call per turn, bounded and degraded to lexical on<br>failure, so the feature never becomes a reason a chat is slow. |
| write_mode | 否 | string | WriteMode is MemoryWriteExplicitOnly or MemoryWriteAuto. |

[返回数据结构目录](#models)

<a id="model-172"></a>

### types.MentionedItem

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| id | 否 | string | — |
| kb_id | 否 | string | Parent knowledge base for file/tag mentions |
| kb_name | 否 | string | Display name for parent KB |
| kb_type | 否 | string | "document" or "faq" (only for kb type) |
| name | 否 | string | — |
| service_id | 否 | string | Parent MCP service for MCP tool mentions |
| skill_name | 否 | string | Preloaded agent skill name |
| type | 否 | string | "kb", "file", "tag", "mcp", "skill" |

[返回数据结构目录](#models)

<a id="model-173"></a>

### types.Message

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| agent_duration_ms | 否 | integer | Agent total execution duration in milliseconds (from query start to answer start) |
| agent_id | 否 | string | AgentID is the agent used for this individual assistant turn. Unlike the<br>session's last_request_state it remains stable when users switch agents. |
| agent_steps | 否 | array&lt;[types.AgentStep](#model-109)&gt; | Agent execution steps (only for assistant messages generated by agent)<br>This contains the detailed reasoning process and tool calls made by the agent<br>Stored for user history display, but NOT included in LLM context to avoid redundancy |
| artifacts | 否 | array&lt;[types.MessageArtifact](#model-174)&gt; | Skill-generated files produced during this assistant turn (assistant messages only).<br>Populated by ArtifactCollector after the sandbox finishes, referenced by the<br>artifact download endpoint. Empty for user messages and turns without skills.<br><br>Stored in the message_artifacts table, not on the message row: the message<br>repository loads it with every message it returns and writes it whenever it<br>is non-nil on create or update. A nil slice leaves the stored rows alone. |
| attachments | 否 | array&lt;[types.MessageAttachment](#model-175)&gt; | Attached files (documents, audio, etc., for user messages) |
| channel | 否 | string | Channel indicates the source channel of this message (e.g., "web", "api", "im") |
| content | 否 | string | Message text content |
| created_at | 否 | string | Message creation timestamp |
| deleted_at | 否 | allOf([gorm.DeletedAt](#model-016)) | Soft delete timestamp |
| id | 否 | string | Unique identifier for the message |
| images | 否 | array&lt;[types.MessageImage](#model-176)&gt; | Attached images with OCR/Caption text (for user messages) |
| is_completed | 否 | boolean | Whether message generation is complete |
| is_fallback | 否 | boolean | Whether this response is a fallback (no knowledge base match found) |
| knowledge_id | 否 | string | KnowledgeID links this message to a Knowledge entry in the chat history knowledge base<br>Used for vector search indexing: when set, the message content has been indexed as a Knowledge passage |
| knowledge_references | 否 | array&lt;[types.SearchResult](#model-209)&gt; | References to knowledge chunks used in the response |
| mentioned_items | 否 | array&lt;[types.MentionedItem](#model-172)&gt; | Mentioned knowledge bases and files (for user messages)<br>Stores the @mentioned items when user sends a message |
| model_id | 否 | string | ModelID is the requested/effective chat model binding captured for this<br>turn. It is useful for reproducibility and suggestion generation. |
| request_id | 否 | string | Request identifier for tracking API requests |
| role | 否 | string | Message role: "user", "assistant", "system" |
| sandbox_checkpoint | 否 | allOf([types.SandboxCheckpoint](#model-206)) | SandboxCheckpoint is the git commit this assistant turn produced in the<br>session sandbox's /workspace. Nil for user messages, for turns that ran<br>without a sandbox, and for turns whose commit failed (best-effort — a<br>failed checkpoint must never block the reply). A message without a<br>checkpoint cannot serve as a fork point with sandbox state. |
| session_id | 否 | string | ID of the session this message belongs to |
| updated_at | 否 | string | Last update timestamp |
| usage | 否 | allOf([types.TokenUsage](#model-227)) | LLM token usage aggregated across every round of the turn that produced this<br>assistant message. Persisted so history reads can attribute cost after the<br>live stream is gone; NULL (nil) for user messages and pre-feature rows. |
| used_memories | 否 | array&lt;[types.UsedMemory](#model-235)&gt; | UsedMemories records which long-term memories were injected into this<br>answer, so the chat UI can show them and let the user delete one on the<br>spot. Persisted rather than only streamed so reopening a conversation<br>still explains what the answer saw. |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "agent_duration_ms": {
      "description": "Agent total execution duration in milliseconds (from query start to answer start)",
      "type": "integer"
    },
    "agent_id": {
      "description": "AgentID is the agent used for this individual assistant turn. Unlike the\nsession's last_request_state it remains stable when users switch agents.",
      "type": "string"
    },
    "agent_steps": {
      "description": "Agent execution steps (only for assistant messages generated by agent)\nThis contains the detailed reasoning process and tool calls made by the agent\nStored for user history display, but NOT included in LLM context to avoid redundancy",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.AgentStep"
      }
    },
    "artifacts": {
      "description": "Skill-generated files produced during this assistant turn (assistant messages only).\nPopulated by ArtifactCollector after the sandbox finishes, referenced by the\nartifact download endpoint. Empty for user messages and turns without skills.\n\nStored in the message_artifacts table, not on the message row: the message\nrepository loads it with every message it returns and writes it whenever it\nis non-nil on create or update. A nil slice leaves the stored rows alone.",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.MessageArtifact"
      }
    },
    "attachments": {
      "description": "Attached files (documents, audio, etc., for user messages)",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.MessageAttachment"
      }
    },
    "channel": {
      "description": "Channel indicates the source channel of this message (e.g., \"web\", \"api\", \"im\")",
      "type": "string"
    },
    "content": {
      "description": "Message text content",
      "type": "string"
    },
    "created_at": {
      "description": "Message creation timestamp",
      "type": "string"
    },
    "deleted_at": {
      "description": "Soft delete timestamp",
      "allOf": [
        {
          "$ref": "#/definitions/gorm.DeletedAt"
        }
      ]
    },
    "id": {
      "description": "Unique identifier for the message",
      "type": "string"
    },
    "images": {
      "description": "Attached images with OCR/Caption text (for user messages)",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.MessageImage"
      }
    },
    "is_completed": {
      "description": "Whether message generation is complete",
      "type": "boolean"
    },
    "is_fallback": {
      "description": "Whether this response is a fallback (no knowledge base match found)",
      "type": "boolean"
    },
    "knowledge_id": {
      "description": "KnowledgeID links this message to a Knowledge entry in the chat history knowledge base\nUsed for vector search indexing: when set, the message content has been indexed as a Knowledge passage",
      "type": "string"
    },
    "knowledge_references": {
      "description": "References to knowledge chunks used in the response",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.SearchResult"
      }
    },
    "mentioned_items": {
      "description": "Mentioned knowledge bases and files (for user messages)\nStores the @mentioned items when user sends a message",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.MentionedItem"
      }
    },
    "model_id": {
      "description": "ModelID is the requested/effective chat model binding captured for this\nturn. It is useful for reproducibility and suggestion generation.",
      "type": "string"
    },
    "request_id": {
      "description": "Request identifier for tracking API requests",
      "type": "string"
    },
    "role": {
      "description": "Message role: \"user\", \"assistant\", \"system\"",
      "type": "string"
    },
    "sandbox_checkpoint": {
      "description": "SandboxCheckpoint is the git commit this assistant turn produced in the\nsession sandbox's /workspace. Nil for user messages, for turns that ran\nwithout a sandbox, and for turns whose commit failed (best-effort — a\nfailed checkpoint must never block the reply). A message without a\ncheckpoint cannot serve as a fork point with sandbox state.",
      "allOf": [
        {
          "$ref": "#/definitions/types.SandboxCheckpoint"
        }
      ]
    },
    "session_id": {
      "description": "ID of the session this message belongs to",
      "type": "string"
    },
    "updated_at": {
      "description": "Last update timestamp",
      "type": "string"
    },
    "usage": {
      "description": "LLM token usage aggregated across every round of the turn that produced this\nassistant message. Persisted so history reads can attribute cost after the\nlive stream is gone; NULL (nil) for user messages and pre-feature rows.",
      "allOf": [
        {
          "$ref": "#/definitions/types.TokenUsage"
        }
      ]
    },
    "used_memories": {
      "description": "UsedMemories records which long-term memories were injected into this\nanswer, so the chat UI can show them and let the user delete one on the\nspot. Persisted rather than only streamed so reopening a conversation\nstill explains what the answer saw.",
      "type": "array",
      "items": {
        "$ref": "#/definitions/types.UsedMemory"
      }
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-174"></a>

### types.MessageArtifact

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| content_hash | 否 | string | SHA-256 of the persisted bytes |
| created_at | 否 | string | When WeKnora persisted the blob |
| deleted_at | 否 | string | DeletedAt marks a file the user deleted. The entry stays in the list<br>rather than being removed because its position IS the download address<br>(msg.Artifacts[index]); dropping it would shift every later file's index<br>and hand an old link the wrong blob. Keeping it also keeps the entry in<br>ArtifactCollector's de-duplication set, so the next collect does not<br>re-attach the very file that was deleted — its sandbox mtime has not<br>moved. Clients filter these out; the bytes are already reclaimed. |
| file_name | 否 | string | Original filename inside the sandbox |
| file_size | 否 | integer | File size in bytes |
| file_type | 否 | string | File extension (e.g., ".pptx", ".pdf") |
| mod_time | 否 | string | Sandbox-side modification time (used for diff) |
| source_path | 否 | string | Absolute path inside the sandbox (used for diff) |
| url | 否 | string | Storage URL (provider://path); persisted, not sent to client |

[返回数据结构目录](#models)

<a id="model-175"></a>

### types.MessageAttachment

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| content | 否 | string | Extracted text content (for small text files) |
| content_mode | 否 | string | full or selected_chunks |
| file_name | 否 | string | Original filename |
| file_size | 否 | integer | File size in bytes |
| file_type | 否 | string | File extension (e.g., ".pdf", ".docx") |
| id | 否 | string | Temporary document ID for session-scoped uploads |
| is_truncated | 否 | boolean | Whether content was truncated |
| line_count | 否 | integer | Total line count (for text files) |
| selected_chunks | 否 | integer | Chunks included in this message prompt |
| token_count | 否 | integer | Approximate tokens in the parsed document |
| total_chunks | 否 | integer | Total parsed chunks |

[返回数据结构目录](#models)

<a id="model-176"></a>

### types.MessageImage

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| caption | 否 | string | — |
| url | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-177"></a>

### types.MinIOEngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| access_key_id | 否 | string | — |
| bucket_name | 否 | string | — |
| endpoint | 否 | string | — |
| mode | 否 | string | "docker" or "remote" |
| path_prefix | 否 | string | — |
| secret_access_key | 否 | string | — |
| use_ssl | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-178"></a>

### types.ModelParameters

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| api_key | 否 | string | — |
| app_id | 否 | string | WeKnoraCloud 厂商专用凭证 |
| app_secret | 否 | string | AES-256 加密存储，实际承载上游 API Key |
| base_url | 否 | string | — |
| context_window | 否 | integer | ContextWindow is the model's total context window in tokens and<br>MaxOutputTokens the most it emits in one response. Both are provider<br>facts the agent cannot discover but has to act on: the context window is<br>what decides when conversation history gets compacted, and assuming a<br>window larger than the real one means compaction never fires and the<br>provider rejects the request mid-conversation instead. 0 means unknown,<br>which falls back to DefaultMaxContextTokens. |
| custom_headers | 否 | map&lt;string, string&gt; | CustomHeaders 允许在调用远程模型 API 时附加自定义 HTTP 请求头，<br>用途类似 Python OpenAI SDK 的 extra_headers 参数，<br>常见场景包括透传企业网关鉴权信息、追踪 ID、路由标识等。<br>保留字段（Authorization、api-key、Content-Type、Accept 等）会在运行期被忽略以避免破坏签名/鉴权流程。 |
| embedding_parameters | 否 | [types.EmbeddingParameters](#model-127) | — |
| extra_config | 否 | map&lt;string, string&gt; | Provider-specific configuration |
| interface_type | 否 | string | — |
| max_concurrency | 否 | integer | MaxConcurrency caps concurrent in-flight BACKGROUND (ingestion /<br>enrichment) calls to THIS specific model, keyed by model ID and shared<br>across all replicas. 0 (the default) means "fall back to the<br>process-wide model.max_concurrency". Interactive user-facing calls are<br>never gated. Only chat / vlm / embedding honour this (see limiter.Gate). |
| max_output_tokens | 否 | integer | — |
| parameter_size | 否 | string | Ollama model parameter size (e.g., "7B", "13B", "70B") |
| provider | 否 | string | Provider identifier: openai, aliyun, zhipu, generic |
| spec | 否 | allOf([types.ModelSpecOverride](#model-179)) | Spec overrides the catalog entry for this row: protocol, capability<br>flags and the flat protocol compat object. Everything omitted is<br>inherited from the vendor catalog (see internal/models/catalog). |
| supports_vision | 否 | boolean | Whether the model accepts image/multimodal input |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "api_key": {
      "type": "string"
    },
    "app_id": {
      "description": "WeKnoraCloud 厂商专用凭证",
      "type": "string"
    },
    "app_secret": {
      "description": "AES-256 加密存储，实际承载上游 API Key",
      "type": "string"
    },
    "base_url": {
      "type": "string"
    },
    "context_window": {
      "description": "ContextWindow is the model's total context window in tokens and\nMaxOutputTokens the most it emits in one response. Both are provider\nfacts the agent cannot discover but has to act on: the context window is\nwhat decides when conversation history gets compacted, and assuming a\nwindow larger than the real one means compaction never fires and the\nprovider rejects the request mid-conversation instead. 0 means unknown,\nwhich falls back to DefaultMaxContextTokens.",
      "type": "integer"
    },
    "custom_headers": {
      "description": "CustomHeaders 允许在调用远程模型 API 时附加自定义 HTTP 请求头，\n用途类似 Python OpenAI SDK 的 extra_headers 参数，\n常见场景包括透传企业网关鉴权信息、追踪 ID、路由标识等。\n保留字段（Authorization、api-key、Content-Type、Accept 等）会在运行期被忽略以避免破坏签名/鉴权流程。",
      "type": "object",
      "additionalProperties": {
        "type": "string"
      }
    },
    "embedding_parameters": {
      "$ref": "#/definitions/types.EmbeddingParameters"
    },
    "extra_config": {
      "description": "Provider-specific configuration",
      "type": "object",
      "additionalProperties": {
        "type": "string"
      }
    },
    "interface_type": {
      "type": "string"
    },
    "max_concurrency": {
      "description": "MaxConcurrency caps concurrent in-flight BACKGROUND (ingestion /\nenrichment) calls to THIS specific model, keyed by model ID and shared\nacross all replicas. 0 (the default) means \"fall back to the\nprocess-wide model.max_concurrency\". Interactive user-facing calls are\nnever gated. Only chat / vlm / embedding honour this (see limiter.Gate).",
      "type": "integer"
    },
    "max_output_tokens": {
      "type": "integer"
    },
    "parameter_size": {
      "description": "Ollama model parameter size (e.g., \"7B\", \"13B\", \"70B\")",
      "type": "string"
    },
    "provider": {
      "description": "Provider identifier: openai, aliyun, zhipu, generic",
      "type": "string"
    },
    "spec": {
      "description": "Spec overrides the catalog entry for this row: protocol, capability\nflags and the flat protocol compat object. Everything omitted is\ninherited from the vendor catalog (see internal/models/catalog).",
      "allOf": [
        {
          "$ref": "#/definitions/types.ModelSpecOverride"
        }
      ]
    },
    "supports_vision": {
      "description": "Whether the model accepts image/multimodal input",
      "type": "boolean"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-179"></a>

### types.ModelSpecOverride

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| api | 否 | string | — |
| compat | 否 | map&lt;string, 未声明&gt; | Compat is a plain object so it round-trips through both JSON (API,<br>database column) and YAML (builtin_models.yaml). |
| context_window | 否 | integer | — |
| input | 否 | array&lt;string&gt; | — |
| max_output_tokens | 否 | integer | — |
| reasoning | 否 | boolean | — |
| thinking_levels | 否 | map&lt;string, string&gt; | — |

[返回数据结构目录](#models)

<a id="model-180"></a>

### types.NamedCount

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| count | 否 | integer | — |
| name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-181"></a>

### types.OBSEngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| access_key | 否 | string | — |
| bucket_name | 否 | string | — |
| endpoint | 否 | string | — |
| path_prefix | 否 | string | — |
| region | 否 | string | — |
| secret_key | 否 | string | — |
| use_ssl | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-182"></a>

### types.OIDCAuthURLResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| authorization_url | 否 | string | — |
| provider_display_name | 否 | string | — |
| state | 否 | string | — |
| success | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-183"></a>

### types.OIDCConfigResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| enabled | 否 | boolean | — |
| provider_display_name | 否 | string | — |
| success | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-184"></a>

### types.OSSEngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| access_key | 否 | string | — |
| bucket_name | 否 | string | — |
| endpoint | 否 | string | — |
| path_prefix | 否 | string | — |
| region | 否 | string | — |
| secret_key | 否 | string | — |
| temp_bucket_name | 否 | string | — |
| temp_region | 否 | string | — |
| use_temp_bucket | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-185"></a>

### types.OrganizationMemberResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| avatar | 否 | string | — |
| email | 否 | string | — |
| id | 否 | string | — |
| joined_at | 否 | string | — |
| representative_user_id | 否 | string | — |
| role | 否 | string | — |
| tenant_id | 否 | integer | — |
| tenant_name | 否 | string | — |
| user_id | 否 | string | — |
| username | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-186"></a>

### types.OrganizationResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| agent_share_count | 否 | integer | 共享到该组织的智能体数量 |
| avatar | 否 | string | — |
| created_at | 否 | string | — |
| description | 否 | string | — |
| has_pending_upgrade | 否 | boolean | 当前用户是否有待处理的权限升级申请 |
| id | 否 | string | — |
| invite_code | 否 | string | — |
| invite_code_expires_at | 否 | string | — |
| invite_code_validity_days | 否 | integer | — |
| is_owner | 否 | boolean | — |
| member_count | 否 | integer | — |
| member_limit | 否 | integer | 0 = unlimited |
| my_role | 否 | string | — |
| name | 否 | string | — |
| owner_id | 否 | string | — |
| owner_tenant_id | 否 | integer | OwnerTenantID is the persisted owner workspace of the organization<br>(Plan 3, migration 000046). Frontend uses this to identify the<br>"owner row" in the workspace-keyed members list — comparing<br>member.tenant_id against owner_tenant_id is the post-Plan-3<br>equivalent of the old member.user_id == owner_id check. |
| pending_join_request_count | 否 | integer | 待审批加入申请数（仅管理员可见） |
| require_approval | 否 | boolean | — |
| searchable | 否 | boolean | — |
| share_count | 否 | integer | 共享到该组织的知识库数量 |
| updated_at | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-187"></a>

### types.ParserEngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| chat_parser_engine_rules | 否 | array&lt;[types.ParserEngineRule](#model-188)&gt; | ChatParserEngineRules selects parser engines for session-scoped chat<br>documents. Knowledge bases keep their own rules in ChunkingConfig. |
| mineru_api_key | 否 | string | MinerU 云 API Key |
| mineru_cloud_enable_formula | 否 | boolean | — |
| mineru_cloud_enable_ocr | 否 | boolean | — |
| mineru_cloud_enable_table | 否 | boolean | — |
| mineru_cloud_language | 否 | string | — |
| mineru_cloud_model | 否 | string | MinerU 云 API 解析参数 |
| mineru_enable_formula | 否 | boolean | — |
| mineru_enable_ocr | 否 | boolean | MinerUEnableOCR is retained for compatibility with configurations saved<br>before parse_method supported auto/ocr/txt. |
| mineru_enable_table | 否 | boolean | — |
| mineru_endpoint | 否 | string | MinerU 自建服务端点 |
| mineru_language | 否 | string | — |
| mineru_model | 否 | string | 以下 backend、vLLM、公式/表格、语言参数仅对 3.x 及更早版本生效。 |
| mineru_parse_method | 否 | string | — |
| mineru_server_api_key | 否 | string | MinerU 自建解析参数。协议按服务端自动识别：4.0+ 走 V1 API，更早版本走 /file_parse。 |
| mineru_tier | 否 | string | 4.0+：flash/basic/standard/advanced，空为服务端默认 |
| mineru_vlm_server_url | 否 | string | vLLM 服务器地址 (vlm-http-client / hybrid-http-client) |
| odl_hybrid | 否 | string | OpenDataLoader PDF (docreader engine); hybrid requires opendataloader-pdf-hybrid service. |
| odl_hybrid_fallback | 否 | boolean | — |
| odl_hybrid_mode | 否 | string | auto, full |
| odl_hybrid_url | 否 | string | e.g. http://odl-hybrid:5002 |
| odl_markdown_with_html | 否 | boolean | — |
| paddleocr_vl_cloud_model | 否 | string | e.g. PaddleOCR-VL-1.6 |
| paddleocr_vl_cloud_token | 否 | string | PaddleOCR-VL AI Studio cloud API. |
| paddleocr_vl_cloud_use_chart_recognition | 否 | boolean | — |
| paddleocr_vl_cloud_use_seal_recognition | 否 | boolean | — |
| paddleocr_vl_endpoint | 否 | string | PaddleOCR-VL self-hosted pipeline service (full /layout-parsing API). |
| paddleocr_vl_use_chart_recognition | 否 | boolean | — |
| paddleocr_vl_use_seal_recognition | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-188"></a>

### types.ParserEngineRule

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| engine | 否 | string | — |
| file_types | 否 | array&lt;string&gt; | — |
| xlsx_first_row_as_header | 否 | boolean | XLSXFirstRowAsHeader restores row-1 column context for flat XLSX tables.<br>nil preserves the parser default; an explicit false disables the mode. |

[返回数据结构目录](#models)

<a id="model-189"></a>

### types.ProviderMetadata

类型：map&lt;string, array&lt;integer&gt;&gt;

额外字段：array&lt;integer&gt;

[返回数据结构目录](#models)

<a id="model-190"></a>

### types.QuestionGenerationConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| custom_instructions | 否 | string | CustomInstructions describes the intended audience or question style.<br>It is appended to the stable system question-generation template. |
| enabled | 否 | boolean | — |
| question_count | 否 | integer | Number of questions to generate per chunk (default: 3, max: 10) |

[返回数据结构目录](#models)

<a id="model-191"></a>

### types.QuestionOrigin

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| knowledge_base_id | 否 | string | — |
| knowledge_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-192"></a>

### types.QuestionSuggestionConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| follow_ups | 否 | [types.FollowUpSuggestionConfig](#model-135) | — |
| starters | 否 | [types.StarterSuggestionConfig](#model-215) | — |

[返回数据结构目录](#models)

<a id="model-193"></a>

### types.QueueStat

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| active | 否 | integer | — |
| archived | 否 | integer | — |
| completed | 否 | integer | — |
| failed | 否 | integer | — |
| latency_ms | 否 | integer | LatencyMs is the age of the oldest pending task, in milliseconds. |
| memory_usage_bytes | 否 | integer | MemoryUsageBytes is the approximate Redis memory the queue occupies. |
| name | 否 | string | — |
| paused | 否 | boolean | Paused reports whether the queue is paused (tasks not consumed). |
| pending | 否 | integer | — |
| pool | 否 | string | Pool is the independent worker pool that drains this queue. |
| processed | 否 | integer | Processed / Failed are today's counters (reset daily). |
| retry | 否 | integer | — |
| scheduled | 否 | integer | — |
| size | 否 | integer | Size is the total number of tasks in the queue (pending + active +<br>scheduled + retry + aggregating + archived). |
| weight | 否 | integer | Weight is the queue's scheduling weight inside its pool. |

[返回数据结构目录](#models)

<a id="model-194"></a>

### types.RegisterRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| email | 是 | string | — |
| password | 是 | string | 最短长度：6 |
| username | 是 | string | 最短长度：2；最大长度：50 |

[返回数据结构目录](#models)

<a id="model-195"></a>

### types.RegisterResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| message | 否 | string | — |
| success | 否 | boolean | — |
| tenant | 否 | [types.Tenant](#model-225) | — |
| user | 否 | [types.User](#model-236) | — |

[返回数据结构目录](#models)

<a id="model-196"></a>

### types.RequestRoleUpgradeRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| message | 否 | string | Optional message explaining the reason；最大长度：500 |
| requested_role | 是 | string | The role user wants to upgrade to |

[返回数据结构目录](#models)

<a id="model-197"></a>

### types.RerankOptions

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| enabled | 否 | boolean | Enabled turns the rerank stage off when explicitly false. Nil means on. |
| model_id | 否 | string | ModelID selects the rerank model. Empty falls back to the tenant<br>RetrievalConfig, then to the first rerank model of the tenant. |
| threshold | 否 | number | Threshold is the minimum rerank model score. Nil uses the tenant<br>RetrievalConfig. A pointer because 0 and negative scores are valid. |
| top_k | 否 | integer | TopK caps the reranked results. Zero uses the endpoint's result count. |

[返回数据结构目录](#models)

<a id="model-198"></a>

### types.Resource

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| description | 否 | string | Optional description |
| external_id | 否 | string | Unique identifier in the external system |
| has_children | 否 | boolean | Whether this resource has children that can be expanded |
| metadata | 否 | object | Additional metadata |
| modified_at | 否 | string | Last modified time in external system |
| name | 否 | string | Display name |
| parent_id | 否 | string | For hierarchical resources (parent ID if applicable) |
| type | 否 | string | Resource type (document, folder, space, page, etc.) |
| url | 否 | string | URL to access in external system |

[返回数据结构目录](#models)

<a id="model-199"></a>

### types.ResourceCountsByOrgResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| agents | 否 | object | — |
| knowledge_bases | 否 | object | — |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "agents": {
      "type": "object",
      "properties": {
        "by_organization": {
          "type": "object",
          "additionalProperties": {
            "type": "integer"
          }
        }
      }
    },
    "knowledge_bases": {
      "type": "object",
      "properties": {
        "by_organization": {
          "type": "object",
          "additionalProperties": {
            "type": "integer"
          }
        }
      }
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-200"></a>

### types.RetrievalConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| embedding_top_k | 否 | integer | EmbeddingTopK is the maximum number of chunks returned by vector search (default: 50) |
| keyword_threshold | 否 | number | KeywordThreshold is the minimum keyword match score (0-1, default: 0.3) |
| rerank_model_id | 否 | string | RerankModelID is the ID of the rerank model to use (required for search) |
| rerank_threshold | 否 | number | RerankThreshold is the minimum rerank score (-10 to 10, default: 0.2) |
| rerank_top_k | 否 | integer | RerankTopK is the maximum number of results after reranking (default: 10) |
| rrf_k | 否 | integer | RRFK is the smoothing constant of Reciprocal Rank Fusion. Larger values<br>flatten the curve, reducing the bias towards top-1 results.<br>Default: 60. Sensible range: 30..100 depending on corpus size. |
| rrf_keyword_weight | 否 | number | RRFKeywordWeight is the keyword counterpart. Default: 0.3. |
| rrf_vector_weight | 否 | number | RRFVectorWeight is the weight applied to the vector retriever inside RRF.<br>RRFVectorWeight + RRFKeywordWeight should usually sum to 1.0 but the math<br>works for any positive weights. Default: 0.7. |
| vector_threshold | 否 | number | VectorThreshold is the minimum vector similarity score (0-1, default: 0.15) |

[返回数据结构目录](#models)

<a id="model-201"></a>

### types.RetrieverEngineParams

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| retriever_engine_type | 否 | string | Retriever engine type |
| retriever_type | 否 | string | Retriever type |

[返回数据结构目录](#models)

<a id="model-202"></a>

### types.RetrieverEngines

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| engines | 否 | array&lt;[types.RetrieverEngineParams](#model-201)&gt; | — |

[返回数据结构目录](#models)

<a id="model-203"></a>

### types.ReviewJoinRequestRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| approved | 否 | boolean | — |
| message | 否 | string | 最大长度：500 |
| role | 否 | string | Optional: role to assign when approving; overrides applicant's requested role |

[返回数据结构目录](#models)

<a id="model-204"></a>

### types.RuntimeTaskInfo

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| allowed_actions | 否 | array&lt;string&gt; | — |
| completed_at | 否 | string | — |
| data_source_id | 否 | string | — |
| deadline | 否 | string | — |
| enqueued_at | 否 | string | — |
| id | 否 | string | — |
| is_orphaned | 否 | boolean | — |
| knowledge_base_id | 否 | string | — |
| knowledge_count | 否 | integer | — |
| knowledge_id | 否 | string | — |
| last_error | 否 | string | — |
| last_failed_at | 否 | string | — |
| max_retry | 否 | integer | — |
| next_process_at | 否 | string | — |
| queue | 否 | string | — |
| retried | 否 | integer | — |
| source_id | 否 | string | — |
| source_kb_id | 否 | string | — |
| started_at | 否 | string | — |
| state | 否 | string | — |
| sync_log_id | 否 | string | — |
| target_id | 否 | string | — |
| target_kb_id | 否 | string | — |
| task_id | 否 | string | — |
| tenant_id | 否 | integer | — |
| type | 否 | string | — |
| worker | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-205"></a>

### types.S3EngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| access_key | 否 | string | — |
| bucket_name | 否 | string | — |
| endpoint | 否 | string | — |
| force_path_style | 否 | boolean | — |
| path_prefix | 否 | string | — |
| region | 否 | string | — |
| secret_key | 否 | string | — |
| use_ssl | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-206"></a>

### types.SandboxCheckpoint

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| commit_sha | 否 | string | — |
| committed_at | 否 | string | — |
| sandbox_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-207"></a>

### types.SandboxNetworkPolicy

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| allow_out | 否 | array&lt;string&gt; | AllowOut accepts IPv4, IPv4 CIDR, a DNS name, or a single-label<br>wildcard such as "*.example.com". Domain entries are only meaningful<br>together with a deny-all; see ValidateSandboxNetworkPolicy. |
| allow_public_inbound | 否 | boolean | AllowPublicInbound is accepted on the wire for old payloads and then<br>cleared. Inbound is always credential-required; a true value has no<br>runtime effect. |
| cube_rules | 否 | array&lt;[types.CubeEgressRule](#model-119)&gt; | CubeRules are validated whenever present and consumed only by the Cube<br>provider adapter. |
| deny_egress_by_default | 否 | boolean | DenyEgressByDefault installs a 0.0.0.0/0 deny-all, after which only<br>AllowOut (and L7 rule targets) can reach the network. false allows<br>public egress, which is what skill installs need. |
| deny_out | 否 | array&lt;string&gt; | DenyOut accepts IPv4 and IPv4 CIDR only. Neither provider can deny a<br>domain: denial is a pure longest-prefix match on the destination IP. |
| e2b_host_rules | 否 | array&lt;[types.E2BHostRule](#model-125)&gt; | E2BHostRules are validated whenever present and consumed only by the E2B<br>provider adapter. |

[返回数据结构目录](#models)

<a id="model-208"></a>

### types.SearchParams

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| disable_keywords_match | 否 | boolean | — |
| disable_vector_match | 否 | boolean | — |
| keyword_threshold | 否 | number | — |
| knowledge_base_ids | 否 | array&lt;string&gt; | KnowledgeBaseIDs overrides the single KB ID passed to HybridSearch,<br>allowing a single retrieval call to span multiple KBs that share the<br>same embedding model. When empty, HybridSearch uses its own id parameter. |
| knowledge_ids | 否 | array&lt;string&gt; | — |
| match_count | 否 | integer | — |
| only_recommended | 否 | boolean | — |
| query_embedding | 否 | array&lt;number&gt; | — |
| query_text | 否 | string | QueryText is required unless query_embedding is provided, keyword matching is disabled,<br>and vector matching remains enabled. |
| rerank | 否 | allOf([types.RerankOptions](#model-197)) | Rerank, when present and enabled, reranks the fused candidates before<br>truncating to MatchCount. Only the hybrid-search API reads it; internal<br>callers rerank on their own. |
| scope_tag_ids | 否 | array&lt;string&gt; | — |
| skip_context_enrichment | 否 | boolean | SkipContextEnrichment skips fetching parent, nearby, and relation chunks<br>in processSearchResults. Used by the chat pipeline where context assembly<br>is handled separately in the merge stage. |
| tag_ids | 否 | array&lt;string&gt; | Tag IDs for filtering (used for FAQ priority filtering) |
| vector_threshold | 否 | number | — |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "disable_keywords_match": {
      "type": "boolean"
    },
    "disable_vector_match": {
      "type": "boolean"
    },
    "keyword_threshold": {
      "type": "number"
    },
    "knowledge_base_ids": {
      "description": "KnowledgeBaseIDs overrides the single KB ID passed to HybridSearch,\nallowing a single retrieval call to span multiple KBs that share the\nsame embedding model. When empty, HybridSearch uses its own id parameter.",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "knowledge_ids": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "match_count": {
      "type": "integer"
    },
    "only_recommended": {
      "type": "boolean"
    },
    "query_embedding": {
      "type": "array",
      "items": {
        "type": "number"
      }
    },
    "query_text": {
      "description": "QueryText is required unless query_embedding is provided, keyword matching is disabled,\nand vector matching remains enabled.",
      "type": "string"
    },
    "rerank": {
      "description": "Rerank, when present and enabled, reranks the fused candidates before\ntruncating to MatchCount. Only the hybrid-search API reads it; internal\ncallers rerank on their own.",
      "allOf": [
        {
          "$ref": "#/definitions/types.RerankOptions"
        }
      ]
    },
    "scope_tag_ids": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "skip_context_enrichment": {
      "description": "SkipContextEnrichment skips fetching parent, nearby, and relation chunks\nin processSearchResults. Used by the chat pipeline where context assembly\nis handled separately in the merge stage.",
      "type": "boolean"
    },
    "tag_ids": {
      "description": "Tag IDs for filtering (used for FAQ priority filtering)",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "vector_threshold": {
      "type": "number"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-209"></a>

### types.SearchResult

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| chunk_index | 否 | integer | Chunk index |
| chunk_metadata | 否 | array&lt;integer&gt; | ChunkMetadata stores chunk-level metadata (e.g., generated questions) |
| chunk_type | 否 | string | Chunk 类型 |
| content | 否 | string | Content |
| end_at | 否 | integer | End at |
| id | 否 | string | ID |
| image_info | 否 | string | 图片信息 (JSON 格式) |
| knowledge_base_id | 否 | string | KnowledgeBaseID is the ID of the knowledge base this result belongs to |
| knowledge_channel | 否 | string | KnowledgeChannel indicates through which channel the knowledge was ingested (web, api, wechat, etc.) |
| knowledge_custom_metadata | 否 | string | KnowledgeCustomMetadata is user-authored context safe to expose to models. |
| knowledge_description | 否 | string | KnowledgeDescription is the description of the knowledge document |
| knowledge_filename | 否 | string | Knowledge file name<br>Used for file type knowledge, contains the original file name |
| knowledge_id | 否 | string | Knowledge ID |
| knowledge_source | 否 | string | Knowledge source<br>Used to indicate the source of the knowledge, such as "url" |
| knowledge_title | 否 | string | Knowledge title |
| match_type | 否 | integer | Match type |
| matched_content | 否 | string | MatchedContent is the actual content that was matched in vector search<br>For FAQ: this is the matched question text (standard or similar question) |
| metadata | 否 | map&lt;string, string&gt; | Metadata |
| parent_chunk_id | 否 | string | 父 Chunk ID |
| score | 否 | number | Score |
| seq | 否 | integer | Seq |
| source_locators | 否 | array&lt;[types.SourceLocator](#model-214)&gt; | SourceLocators point back into the original file for citation<br>navigation. Merged results carry the union of their chunks' locators. |
| start_at | 否 | integer | Start at |
| sub_chunk_id | 否 | array&lt;string&gt; | SubChunkIndex |

[返回数据结构目录](#models)

<a id="model-210"></a>

### types.Session

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| created_at | 否 | string | — |
| deleted_at | 否 | [gorm.DeletedAt](#model-016) | — |
| description | 否 | string | Description |
| forked_from_message_id | 否 | string | ForkedFromMessageID is the user or assistant message, IN THE PARENT<br>SESSION, that the fork branched at. For a user point, messages strictly<br>before it were copied here. For an assistant point, that answer is<br>included so the branch continues after it. |
| host_workspace_dir | 否 | string | HostWorkspaceDir is the user-selected project directory this session<br>operates in, for the WeKnora Lite host sandbox only. Empty means the session<br>gets an auto-allocated workspace instead.<br><br>Written once at creation and never updated: re-pointing a live session<br>at a different directory would leave its transcript describing files<br>that are no longer there. Standard edition never writes it. |
| id | 否 | string | ID |
| im_platform | 否 | string | IMPlatform is the originating IM platform (e.g. "feishu", "wecom") when<br>this session is bound to an IM channel. It is not stored on the sessions<br>table (it lives in im_channel_sessions) and is populated on read so the<br>Web console can classify a session's origin folder without a list query. |
| is_pinned | 否 | boolean | IsPinned indicates whether the session is pinned in the list. |
| last_request_state | 否 | allOf([types.SessionLastRequestState](#model-211)) | LastRequestState records the input-bar state used the last time this<br>session sent a question (agent, model, KB scope, web search, MCPs).<br>Persisted on every successful POST to /knowledge-chat or /agent-chat so<br>that reopening the session can restore the original request context to<br>the chat UI. Stored in the legacy sessions.agent_config JSONB column to<br>avoid a new migration; the shape used today is `SessionLastRequestState`. |
| parent_session_id | 否 | string | ParentSessionID names the session this one was forked from. Empty for<br>ordinary sessions. Deliberately not a foreign key: the parent may be<br>deleted while the branch lives on, and a branch must not cascade away<br>with it. A dangling value simply renders as an ordinary session. |
| pinned_at | 否 | string | PinnedAt records when the session was pinned; nil when not pinned. |
| sandbox_config_id | 否 | string | SandboxConfigID pins which sandbox config this session's CURRENT live<br>sandbox was created on. Empty means no live sandbox;<br>SandboxConfigIDGlobalDefault means the deployment-wide default config.<br><br>This is an ephemeral pin that dies with the sandbox, not a permanent<br>owner: sessions outlive sandboxes by months, so treating it as<br>permanent would make "no session references this config" never true. |
| tenant_id | 否 | integer | Workspace ID |
| title | 否 | string | Title |
| updated_at | 否 | string | — |
| user_id | 否 | string | UserID is the owner scope for this session. WeKnora user UUIDs, API<br>external-user principals, and embed visitor principals all use this column. |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "created_at": {
      "type": "string"
    },
    "deleted_at": {
      "$ref": "#/definitions/gorm.DeletedAt"
    },
    "description": {
      "description": "Description",
      "type": "string"
    },
    "forked_from_message_id": {
      "description": "ForkedFromMessageID is the user or assistant message, IN THE PARENT\nSESSION, that the fork branched at. For a user point, messages strictly\nbefore it were copied here. For an assistant point, that answer is\nincluded so the branch continues after it.",
      "type": "string"
    },
    "host_workspace_dir": {
      "description": "HostWorkspaceDir is the user-selected project directory this session\noperates in, for the WeKnora Lite host sandbox only. Empty means the session\ngets an auto-allocated workspace instead.\n\nWritten once at creation and never updated: re-pointing a live session\nat a different directory would leave its transcript describing files\nthat are no longer there. Standard edition never writes it.",
      "type": "string"
    },
    "id": {
      "description": "ID",
      "type": "string"
    },
    "im_platform": {
      "description": "IMPlatform is the originating IM platform (e.g. \"feishu\", \"wecom\") when\nthis session is bound to an IM channel. It is not stored on the sessions\ntable (it lives in im_channel_sessions) and is populated on read so the\nWeb console can classify a session's origin folder without a list query.",
      "type": "string"
    },
    "is_pinned": {
      "description": "IsPinned indicates whether the session is pinned in the list.",
      "type": "boolean"
    },
    "last_request_state": {
      "description": "LastRequestState records the input-bar state used the last time this\nsession sent a question (agent, model, KB scope, web search, MCPs).\nPersisted on every successful POST to /knowledge-chat or /agent-chat so\nthat reopening the session can restore the original request context to\nthe chat UI. Stored in the legacy sessions.agent_config JSONB column to\navoid a new migration; the shape used today is `SessionLastRequestState`.",
      "allOf": [
        {
          "$ref": "#/definitions/types.SessionLastRequestState"
        }
      ]
    },
    "parent_session_id": {
      "description": "ParentSessionID names the session this one was forked from. Empty for\nordinary sessions. Deliberately not a foreign key: the parent may be\ndeleted while the branch lives on, and a branch must not cascade away\nwith it. A dangling value simply renders as an ordinary session.",
      "type": "string"
    },
    "pinned_at": {
      "description": "PinnedAt records when the session was pinned; nil when not pinned.",
      "type": "string"
    },
    "sandbox_config_id": {
      "description": "SandboxConfigID pins which sandbox config this session's CURRENT live\nsandbox was created on. Empty means no live sandbox;\nSandboxConfigIDGlobalDefault means the deployment-wide default config.\n\nThis is an ephemeral pin that dies with the sandbox, not a permanent\nowner: sessions outlive sandboxes by months, so treating it as\npermanent would make \"no session references this config\" never true.",
      "type": "string"
    },
    "tenant_id": {
      "description": "Workspace ID",
      "type": "integer"
    },
    "title": {
      "description": "Title",
      "type": "string"
    },
    "updated_at": {
      "type": "string"
    },
    "user_id": {
      "description": "UserID is the owner scope for this session. WeKnora user UUIDs, API\nexternal-user principals, and embed visitor principals all use this column.",
      "type": "string"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-211"></a>

### types.SessionLastRequestState

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| agent_enabled | 否 | boolean | — |
| agent_id | 否 | string | — |
| knowledge_base_ids | 否 | array&lt;string&gt; | — |
| knowledge_ids | 否 | array&lt;string&gt; | — |
| local_browser_enabled | 否 | boolean | — |
| mcp_service_ids | 否 | array&lt;string&gt; | — |
| mentioned_items | 否 | array&lt;[types.MentionedItem](#model-172)&gt; | — |
| model_id | 否 | string | — |
| reasoning_effort | 否 | string | — |
| skill_names | 否 | array&lt;string&gt; | — |
| tag_ids | 否 | array&lt;string&gt; | — |
| web_search_enabled | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-212"></a>

### types.ShareKnowledgeBaseRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| organization_id | 是 | string | — |
| permission | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-213"></a>

### types.SkillImageConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| base_template_id | 否 | string | BaseTemplateID is the template this chain was originally built from;<br>the rebuild path starts over from it. |
| built_at | 否 | string | BuiltAt records when this generation was produced. |
| generation | 否 | integer | Generation increments on every successful install/remove, for naming<br>and troubleshooting. |
| owner_fingerprint | 否 | string | OwnerFingerprint identifies the provider account that owns the snapshot.<br>Snapshots are invisible across accounts, so a mismatch means "fall back<br>to the base template" rather than "fail". |
| snapshot_id | 否 | string | SnapshotID is the currently effective snapshot; empty = base template. |

[返回数据结构目录](#models)

<a id="model-214"></a>

### types.SourceLocator

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| bbox | 否 | array&lt;number&gt; | BBox is [x0, y0, x1, y1] as fractions of the page width and height,<br>origin at the top-left corner of the rendered page. |
| block | 否 | integer | Block is the 1-based index among a Word file's body paragraphs and tables. |
| end | 否 | integer | — |
| end_ms | 否 | integer | — |
| mapping | 否 | string | Mapping records verified text-to-source alignment. Empty means legacy. |
| page | 否 | integer | Page is the 1-based PDF page. |
| partial | 否 | boolean | Partial means some of the chunk has no source mapping. |
| quote | 否 | string | Quote is the complete cited overlap as parsed. Viewers match it<br>inside the rendered original and against the<br>answer sentence that cites the chunk. |
| row_end | 否 | integer | — |
| row_start | 否 | integer | — |
| section | 否 | integer | Section is the 1-based EPUB spine index. |
| sheet | 否 | string | Sheet is the spreadsheet sheet name; RowStart/RowEnd are its 1-based<br>row numbers as shown by spreadsheet applications. |
| slide | 否 | integer | Slide is the 1-based slide number in presentation order. |
| source_hash | 否 | string | SourceHash binds the locator to the knowledge record’s source revision hash. |
| source_id | 否 | string | SourceID identifies a block within the parser result, not a rendered item. |
| start | 否 | integer | Start/End are rune offsets into the original text file. |
| start_ms | 否 | integer | StartMs/EndMs bound an audio segment. |
| title | 否 | string | Title names the section or slide when the source has one. |
| type | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-215"></a>

### types.StarterSuggestionConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| count | 否 | integer | — |
| enabled | 否 | boolean | — |
| items | 否 | array&lt;string&gt; | — |
| mode | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-216"></a>

### types.StorageBackendConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| access_key_id | 否 | string | — |
| app_id | 否 | string | — |
| bucket_name | 否 | string | — |
| endpoint | 否 | string | — |
| force_path_style | 否 | boolean | — |
| mode | 否 | string | — |
| path_prefix | 否 | string | — |
| region | 否 | string | — |
| secret_access_key | 否 | string | — |
| temp_bucket_name | 否 | string | — |
| temp_region | 否 | string | — |
| use_ssl | 否 | boolean | — |
| use_temp_bucket | 否 | boolean | — |

[返回数据结构目录](#models)

<a id="model-217"></a>

### types.StorageConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| app_id | 否 | string | App ID (COS specific) |
| bucket_name | 否 | string | Bucket Name |
| endpoint | 否 | string | Endpoint (S3 specific) - e.g., s3.amazonaws.com, oss-cn-hangzhou.aliyuncs.com |
| force_path_style | 否 | boolean | ForcePathStyle (S3 specific) - whether to use path-style URLs |
| path_prefix | 否 | string | Path Prefix |
| provider | 否 | string | Provider: "cos", "minio", "s3" |
| region | 否 | string | Region |
| secret_id | 否 | string | Secret ID (COS) / Access Key ID (S3, MinIO) |
| secret_key | 否 | string | Secret Key (COS) / Secret Access Key (S3, MinIO) |
| use_ssl | 否 | boolean | UseSSL (S3 specific) - whether to use HTTPS |

[返回数据结构目录](#models)

<a id="model-218"></a>

### types.StorageEngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| cos | 否 | [types.COSEngineConfig](#model-112) | — |
| default_provider | 否 | string | "local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs" |
| ks3 | 否 | [types.KS3EngineConfig](#model-148) | — |
| local | 否 | [types.LocalEngineConfig](#model-160) | — |
| minio | 否 | [types.MinIOEngineConfig](#model-177) | — |
| obs | 否 | [types.OBSEngineConfig](#model-181) | — |
| oss | 否 | [types.OSSEngineConfig](#model-184) | — |
| s3 | 否 | [types.S3EngineConfig](#model-205) | — |
| tos | 否 | [types.TOSEngineConfig](#model-224) | — |

[返回数据结构目录](#models)

<a id="model-219"></a>

### types.StorageProviderConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| provider | 否 | string | "local", "minio", "cos", "tos", "s3", "oss", "ks3", "obs" |

[返回数据结构目录](#models)

<a id="model-220"></a>

### types.SubmitJoinRequestRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| invite_code | 是 | string | 最短长度：8；最大长度：32 |
| message | 否 | string | 最大长度：500 |
| role | 否 | string | Optional: role the applicant requests (admin/editor/viewer); default viewer |

[返回数据结构目录](#models)

<a id="model-221"></a>

### types.SuggestionAttribution

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| question_id | 否 | string | — |
| suggestion_set_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-222"></a>

### types.SyncLog

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| created_at | 否 | string | Creation timestamp (usually same as StartedAt) |
| data_source_id | 否 | string | Reference to the data source |
| error_message | 否 | string | Error details if status is "failed" |
| finished_at | 否 | string | Sync completion time |
| id | 否 | string | Unique identifier |
| items_created | 否 | integer | New items created in knowledge base |
| items_deleted | 否 | integer | Items deleted from knowledge base |
| items_failed | 否 | integer | Items that failed to sync |
| items_skipped | 否 | integer | Items skipped (no changes detected) |
| items_total | 否 | integer | Total items fetched from source |
| items_updated | 否 | integer | Existing items updated |
| result | 否 | array&lt;integer&gt; | Detailed sync result (JSON-encoded) |
| started_at | 否 | string | Sync start time |
| status | 否 | string | Sync status: running, success, partial, failed, canceled |
| tenant_id | 否 | integer | Workspace ID |
| updated_at | 否 | string | Last update timestamp |

[返回数据结构目录](#models)

<a id="model-223"></a>

### types.SystemSetting

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| category | 否 | string | Category groups settings in the management UI ("limits", "agent",<br>"auth", ...). Free-form string so adding a new category is a<br>data-only change. |
| created_at | 否 | string | — |
| description | 否 | string | — |
| enum | 否 | array&lt;string&gt; | Enum is populated by the service layer (NOT persisted) from the<br>in-code registry. Empty/nil means "free-form input"; non-empty<br>means the UI should render a select with these options. Tagged<br>`gorm:"-"` so GORM never tries to read/write the column. |
| id | 否 | integer | — |
| is_secret | 否 | boolean | IsSecret reserves UI affordances for P3 (mask + reveal-with-confirm).<br>In P1 every row is is_secret=false; service Update accepts the<br>column but does not yet enforce special handling. |
| key | 否 | string | — |
| last_modified_by | 否 | string | — |
| last_modified_by_name | 否 | string | LastModifiedByName is a display label resolved from LastModifiedBy<br>(the user's UUID) at handler time — username when available,<br>otherwise email. Empty for virtual rows that were never persisted<br>(LastModifiedBy=""). Not stored: derived per request so renaming<br>a user reflects on the next reload without a backfill job. |
| requires_restart | 否 | boolean | RequiresRestart reserves UI affordances for P3 (banner "this<br>change won't take effect until the next restart"). In P1 the<br>only seeded key is per-request, so always false. |
| updated_at | 否 | string | — |
| value | 否 | array&lt;integer&gt; | — |
| value_type | 否 | string | ValueType is one of "int", "string", "bool". Service layer rejects<br>updates whose payload type does not match; UI uses it to pick<br>InputNumber vs Input vs Switch. |

[返回数据结构目录](#models)

<a id="model-224"></a>

### types.TOSEngineConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| access_key | 否 | string | — |
| bucket_name | 否 | string | — |
| endpoint | 否 | string | — |
| path_prefix | 否 | string | — |
| region | 否 | string | — |
| secret_key | 否 | string | — |
| temp_bucket_name | 否 | string | — |
| temp_region | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-225"></a>

### types.Tenant

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| business | 否 | string | Business |
| chat_history_config | 否 | allOf([types.ChatHistoryConfig](#model-113)) | Chat history config: knowledge base configuration for indexing and searching chat messages via vector search |
| context_config | 否 | allOf([types.ContextConfig](#model-116)) | Global Context configuration for this workspace (default for all sessions) |
| created_at | 否 | string | Creation time |
| credentials | 否 | allOf([types.CredentialsConfig](#model-118)) | Credentials config: third-party provider credentials (e.g. WeKnoraCloud AppID/AppSecret) |
| default_storage_backend_id | 否 | string | DefaultStorageBackendID is the workspace default concrete storage instance. |
| deleted_at | 否 | allOf([gorm.DeletedAt](#model-016)) | Deletion time |
| description | 否 | string | Description |
| id | 否 | integer | ID |
| memory_config | 否 | allOf([types.MemoryConfig](#model-171)) | Memory config: workspace switch for cross-session long-term memory |
| name | 否 | string | Name |
| parser_engine_config | 否 | allOf([types.ParserEngineConfig](#model-187)) | Parser engine config overrides (MinerU endpoint, API key, etc.). Used when parsing documents; overrides env. |
| retrieval_config | 否 | allOf([types.RetrievalConfig](#model-200)) | Retrieval config: global search/retrieval parameters shared by knowledge search and message search |
| retriever_engines | 否 | allOf([types.RetrieverEngines](#model-202)) | Retriever engines |
| status | 否 | string | Status |
| storage_engine_config | 否 | allOf([types.StorageEngineConfig](#model-218)) | Storage engine config: parameters for Local, MinIO, COS. Used for document/file storage and docreader. |
| storage_quota | 否 | integer | Storage quota (Bytes), default is 10GB, including vector, original file, text, index, etc. |
| storage_used | 否 | integer | Storage used (Bytes) |
| updated_at | 否 | string | Last updated time |
| web_search_config | 否 | allOf([types.WebSearchConfig](#model-242)) | Global WebSearch configuration for this workspace |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "business": {
      "description": "Business",
      "type": "string"
    },
    "chat_history_config": {
      "description": "Chat history config: knowledge base configuration for indexing and searching chat messages via vector search",
      "allOf": [
        {
          "$ref": "#/definitions/types.ChatHistoryConfig"
        }
      ]
    },
    "context_config": {
      "description": "Global Context configuration for this workspace (default for all sessions)",
      "allOf": [
        {
          "$ref": "#/definitions/types.ContextConfig"
        }
      ]
    },
    "created_at": {
      "description": "Creation time",
      "type": "string"
    },
    "credentials": {
      "description": "Credentials config: third-party provider credentials (e.g. WeKnoraCloud AppID/AppSecret)",
      "allOf": [
        {
          "$ref": "#/definitions/types.CredentialsConfig"
        }
      ]
    },
    "default_storage_backend_id": {
      "description": "DefaultStorageBackendID is the workspace default concrete storage instance.",
      "type": "string"
    },
    "deleted_at": {
      "description": "Deletion time",
      "allOf": [
        {
          "$ref": "#/definitions/gorm.DeletedAt"
        }
      ]
    },
    "description": {
      "description": "Description",
      "type": "string"
    },
    "id": {
      "description": "ID",
      "type": "integer"
    },
    "memory_config": {
      "description": "Memory config: workspace switch for cross-session long-term memory",
      "allOf": [
        {
          "$ref": "#/definitions/types.MemoryConfig"
        }
      ]
    },
    "name": {
      "description": "Name",
      "type": "string"
    },
    "parser_engine_config": {
      "description": "Parser engine config overrides (MinerU endpoint, API key, etc.). Used when parsing documents; overrides env.",
      "allOf": [
        {
          "$ref": "#/definitions/types.ParserEngineConfig"
        }
      ]
    },
    "retrieval_config": {
      "description": "Retrieval config: global search/retrieval parameters shared by knowledge search and message search",
      "allOf": [
        {
          "$ref": "#/definitions/types.RetrievalConfig"
        }
      ]
    },
    "retriever_engines": {
      "description": "Retriever engines",
      "allOf": [
        {
          "$ref": "#/definitions/types.RetrieverEngines"
        }
      ]
    },
    "status": {
      "description": "Status",
      "type": "string"
    },
    "storage_engine_config": {
      "description": "Storage engine config: parameters for Local, MinIO, COS. Used for document/file storage and docreader.",
      "allOf": [
        {
          "$ref": "#/definitions/types.StorageEngineConfig"
        }
      ]
    },
    "storage_quota": {
      "description": "Storage quota (Bytes), default is 10GB, including vector, original file, text, index, etc.",
      "type": "integer"
    },
    "storage_used": {
      "description": "Storage used (Bytes)",
      "type": "integer"
    },
    "updated_at": {
      "description": "Last updated time",
      "type": "string"
    },
    "web_search_config": {
      "description": "Global WebSearch configuration for this workspace",
      "allOf": [
        {
          "$ref": "#/definitions/types.WebSearchConfig"
        }
      ]
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-226"></a>

### types.TenantSandboxConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| allow_private_endpoints | 否 | boolean | AllowPrivateEndpoints permits this workspace config to reach RFC1918 or<br>loopback cluster endpoints. Link-local/cloud-metadata addresses remain<br>blocked. It is explicit in the UI instead of hidden in process env. |
| cube | 否 | [types.CubeSandboxConfig](#model-121) | — |
| default_timeout_sec | 否 | integer | DefaultTimeoutSec is the per-execution timeout in seconds. 0 uses the<br>program's built-in default. |
| desktop_enabled | 否 | boolean | DesktopEnabled declares that this config's base template is a desktop<br>image (XFCE + x11vnc + websockify). It is NOT a second template: a<br>config has exactly one boot target, and skill snapshots stack on top of<br>this base generation after generation. Flipping it changes the base, so<br>any installed skills must be rebuilt from the new one. |
| docker | 否 | [types.DockerSandboxConfig](#model-124) | — |
| e2b | 否 | [types.E2BSandboxConfig](#model-126) | — |
| env_vars | 否 | map&lt;string, string&gt; | EnvVars are additional environment variables injected into every<br>sandbox created for this tenant. 🔒 Values are encrypted at rest.<br>These become visible to all scripts running in the tenant's<br>sandboxes — do not place secrets here that scripts must not access. |
| network | 否 | allOf([types.SandboxNetworkPolicy](#model-207)) | Network is the outbound/inbound network policy applied to every sandbox<br>created from this config — chat sessions, skill installs and deep<br>connectivity probes alike. nil and the zero value mean the same thing:<br>outbound egress allowed, inbound public access closed. |
| sandbox_type | 否 | string | SandboxType is cube, e2b, or docker; disabled is the hidden policy row. |
| skill_image | 否 | allOf([types.SkillImageConfig](#model-213)) | SkillImage points at the snapshot that carries this config's installed<br>skills. Empty means "use the base template". Written only by the skill<br>install/remove path: MergeSandboxConfigForUpdate ignores client values<br>so a settings-form save cannot wipe or plant the pointer. |
| skill_rollout | 否 | string | SkillRollout decides whether sessions that already hold a sandbox of<br>this config rebuild after a skill install or removal. Empty and<br>SkillRolloutNextTurn rebuild on the next chat turn. SkillRolloutNewSession<br>leaves those sandboxes on the previous image; only sessions that start<br>afterwards boot the new snapshot. |
| terminal_idle_disconnect_sec | 否 | integer | TerminalIdleDisconnectSec is how long an interactive terminal or<br>desktop may go without user activity before WeKnora closes the<br>connection so the sandbox can pause on its provider TTL. Terminal<br>counts keystrokes and PTY output; desktop counts mouse and keyboard.<br>0 uses the built-in default (15 minutes). Not an identity field. |
| volume_mount | 否 | allOf([types.VolumeMountConfig](#model-240)) | VolumeMount configures an optional shared volume mounted into every<br>sandbox created for this tenant. Currently used for tenant-installed<br>skills, but the configuration itself is skill-agnostic and can serve<br>any volume-mount use case (shared datasets, pre-installed toolchains,<br>etc.). |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "allow_private_endpoints": {
      "description": "AllowPrivateEndpoints permits this workspace config to reach RFC1918 or\nloopback cluster endpoints. Link-local/cloud-metadata addresses remain\nblocked. It is explicit in the UI instead of hidden in process env.",
      "type": "boolean"
    },
    "cube": {
      "$ref": "#/definitions/types.CubeSandboxConfig"
    },
    "default_timeout_sec": {
      "description": "DefaultTimeoutSec is the per-execution timeout in seconds. 0 uses the\nprogram's built-in default.",
      "type": "integer"
    },
    "desktop_enabled": {
      "description": "DesktopEnabled declares that this config's base template is a desktop\nimage (XFCE + x11vnc + websockify). It is NOT a second template: a\nconfig has exactly one boot target, and skill snapshots stack on top of\nthis base generation after generation. Flipping it changes the base, so\nany installed skills must be rebuilt from the new one.",
      "type": "boolean"
    },
    "docker": {
      "$ref": "#/definitions/types.DockerSandboxConfig"
    },
    "e2b": {
      "$ref": "#/definitions/types.E2BSandboxConfig"
    },
    "env_vars": {
      "description": "EnvVars are additional environment variables injected into every\nsandbox created for this tenant. 🔒 Values are encrypted at rest.\nThese become visible to all scripts running in the tenant's\nsandboxes — do not place secrets here that scripts must not access.",
      "type": "object",
      "additionalProperties": {
        "type": "string"
      }
    },
    "network": {
      "description": "Network is the outbound/inbound network policy applied to every sandbox\ncreated from this config — chat sessions, skill installs and deep\nconnectivity probes alike. nil and the zero value mean the same thing:\noutbound egress allowed, inbound public access closed.",
      "allOf": [
        {
          "$ref": "#/definitions/types.SandboxNetworkPolicy"
        }
      ]
    },
    "sandbox_type": {
      "description": "SandboxType is cube, e2b, or docker; disabled is the hidden policy row.",
      "type": "string"
    },
    "skill_image": {
      "description": "SkillImage points at the snapshot that carries this config's installed\nskills. Empty means \"use the base template\". Written only by the skill\ninstall/remove path: MergeSandboxConfigForUpdate ignores client values\nso a settings-form save cannot wipe or plant the pointer.",
      "allOf": [
        {
          "$ref": "#/definitions/types.SkillImageConfig"
        }
      ]
    },
    "skill_rollout": {
      "description": "SkillRollout decides whether sessions that already hold a sandbox of\nthis config rebuild after a skill install or removal. Empty and\nSkillRolloutNextTurn rebuild on the next chat turn. SkillRolloutNewSession\nleaves those sandboxes on the previous image; only sessions that start\nafterwards boot the new snapshot.",
      "type": "string"
    },
    "terminal_idle_disconnect_sec": {
      "description": "TerminalIdleDisconnectSec is how long an interactive terminal or\ndesktop may go without user activity before WeKnora closes the\nconnection so the sandbox can pause on its provider TTL. Terminal\ncounts keystrokes and PTY output; desktop counts mouse and keyboard.\n0 uses the built-in default (15 minutes). Not an identity field.",
      "type": "integer"
    },
    "volume_mount": {
      "description": "VolumeMount configures an optional shared volume mounted into every\nsandbox created for this tenant. Currently used for tenant-installed\nskills, but the configuration itself is skill-agnostic and can serve\nany volume-mount use case (shared datasets, pre-installed toolchains,\netc.).",
      "allOf": [
        {
          "$ref": "#/definitions/types.VolumeMountConfig"
        }
      ]
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-227"></a>

### types.TokenUsage

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| cache_miss_tokens | 否 | integer | — |
| cache_read_tokens | 否 | integer | — |
| cache_reported | 否 | boolean | — |
| cache_status | 否 | string | — |
| cache_write_tokens | 否 | integer | — |
| cached_tokens | 否 | integer | CachedTokens is the legacy alias for CacheReadTokens. It remains on the<br>wire for compatibility with existing API consumers. |
| completion_tokens | 否 | integer | — |
| context_token_scale | 否 | number | ContextTokenScale is provider prompt tokens per cl100k-estimated token,<br>measured over the turn's rounds. Persisted with the turn so the next<br>turn's history loading and first compaction check are calibrated before<br>any provider count of their own. Zero when the turn measured none. |
| prompt_tokens | 否 | integer | — |
| total_tokens | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-228"></a>

### types.ToolCall

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| args | 否 | object | Tool arguments |
| duration | 否 | integer | Execution time in milliseconds |
| id | 否 | string | Function call ID from LLM |
| name | 否 | string | Tool name |
| provider_metadata | 否 | allOf([types.ToolCallMetadata](#model-229)) | Provider-specific tool-call state for replay |
| reflection | 否 | string | Agent's reflection on this tool call result (if enabled) |
| result | 否 | allOf([types.ToolResult](#model-231)) | Execution result (contains Output) |
| target | 否 | allOf([types.ToolCallTarget](#model-230)) | Target identifies the actual proxy target; Name/Args retain the model call for replay. |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "args": {
      "description": "Tool arguments",
      "type": "object",
      "additionalProperties": true
    },
    "duration": {
      "description": "Execution time in milliseconds",
      "type": "integer"
    },
    "id": {
      "description": "Function call ID from LLM",
      "type": "string"
    },
    "name": {
      "description": "Tool name",
      "type": "string"
    },
    "provider_metadata": {
      "description": "Provider-specific tool-call state for replay",
      "allOf": [
        {
          "$ref": "#/definitions/types.ToolCallMetadata"
        }
      ]
    },
    "reflection": {
      "description": "Agent's reflection on this tool call result (if enabled)",
      "type": "string"
    },
    "result": {
      "description": "Execution result (contains Output)",
      "allOf": [
        {
          "$ref": "#/definitions/types.ToolResult"
        }
      ]
    },
    "target": {
      "description": "Target identifies the actual proxy target; Name/Args retain the model call for replay.",
      "allOf": [
        {
          "$ref": "#/definitions/types.ToolCallTarget"
        }
      ]
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-229"></a>

### types.ToolCallMetadata

类型：map&lt;string, array&lt;integer&gt;&gt;

额外字段：array&lt;integer&gt;

[返回数据结构目录](#models)

<a id="model-230"></a>

### types.ToolCallTarget

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| args | 否 | object | — |
| name | 否 | string | — |
| service_name | 否 | string | — |
| tool_name | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-231"></a>

### types.ToolResult

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| data | 否 | object | Structured data for programmatic use |
| error | 否 | string | Error message if execution failed |
| images | 否 | array&lt;string&gt; | Base64 data URIs from tool (e.g. MCP image content) |
| output | 否 | string | Human-readable output |
| success | 否 | boolean | Whether the tool executed successfully |

[返回数据结构目录](#models)

<a id="model-232"></a>

### types.UpdateMemberRoleRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| role | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-233"></a>

### types.UpdateOrganizationRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| avatar | 否 | string | optional avatar URL；最大长度：512 |
| description | 否 | string | 最大长度：1000 |
| invite_code_validity_days | 否 | integer | 0=never, 1, 7, 30 |
| member_limit | 否 | integer | max members; 0=unlimited |
| name | 否 | string | 最短长度：1；最大长度：255 |
| require_approval | 否 | boolean | — |
| searchable | 否 | boolean | open for search so others can discover and join |

[返回数据结构目录](#models)

<a id="model-234"></a>

### types.UpdateSharePermissionRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| permission | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-235"></a>

### types.UsedMemory

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| content | 否 | string | — |
| id | 否 | string | — |
| kind | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-236"></a>

### types.User

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| avatar | 否 | string | Avatar URL of the user |
| can_access_all_tenants | 否 | boolean | Whether the user can access all workspaces (cross-workspace access) |
| created_at | 否 | string | Creation time of the user |
| deleted_at | 否 | allOf([gorm.DeletedAt](#model-016)) | Deletion time of the user |
| email | 否 | string | Email address of the user |
| id | 否 | string | Unique identifier of the user |
| is_active | 否 | boolean | Whether the user is active |
| is_system_admin | 否 | boolean | Whether the user is a system administrator (independent of workspace roles) |
| preferences | 否 | allOf([types.UserPreferences](#model-238)) | Per-user UI/feature preferences.<br>Stored as JSON (jsonb on Postgres, TEXT on SQLite) via the<br>driver.Valuer / sql.Scanner methods on UserPreferences. |
| tenant | 否 | allOf([types.Tenant](#model-225)) | Association relationship, not stored in the database |
| tenant_id | 否 | integer | Workspace ID that the user belongs to |
| updated_at | 否 | string | Last updated time of the user |
| username | 否 | string | Username of the user |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "avatar": {
      "description": "Avatar URL of the user",
      "type": "string"
    },
    "can_access_all_tenants": {
      "description": "Whether the user can access all workspaces (cross-workspace access)",
      "type": "boolean"
    },
    "created_at": {
      "description": "Creation time of the user",
      "type": "string"
    },
    "deleted_at": {
      "description": "Deletion time of the user",
      "allOf": [
        {
          "$ref": "#/definitions/gorm.DeletedAt"
        }
      ]
    },
    "email": {
      "description": "Email address of the user",
      "type": "string"
    },
    "id": {
      "description": "Unique identifier of the user",
      "type": "string"
    },
    "is_active": {
      "description": "Whether the user is active",
      "type": "boolean"
    },
    "is_system_admin": {
      "description": "Whether the user is a system administrator (independent of workspace roles)",
      "type": "boolean"
    },
    "preferences": {
      "description": "Per-user UI/feature preferences.\nStored as JSON (jsonb on Postgres, TEXT on SQLite) via the\ndriver.Valuer / sql.Scanner methods on UserPreferences.",
      "allOf": [
        {
          "$ref": "#/definitions/types.UserPreferences"
        }
      ]
    },
    "tenant": {
      "description": "Association relationship, not stored in the database",
      "allOf": [
        {
          "$ref": "#/definitions/types.Tenant"
        }
      ]
    },
    "tenant_id": {
      "description": "Workspace ID that the user belongs to",
      "type": "integer"
    },
    "updated_at": {
      "description": "Last updated time of the user",
      "type": "string"
    },
    "username": {
      "description": "Username of the user",
      "type": "string"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-237"></a>

### types.UserInfo

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| avatar | 否 | string | — |
| can_access_all_tenants | 否 | boolean | — |
| created_at | 否 | string | — |
| email | 否 | string | — |
| id | 否 | string | — |
| is_active | 否 | boolean | — |
| is_system_admin | 否 | boolean | — |
| preferences | 否 | [types.UserPreferences](#model-238) | — |
| tenant_id | 否 | integer | — |
| updated_at | 否 | string | — |
| username | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-238"></a>

### types.UserPreferences

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| browser_search_instructions | 否 | string | BrowserSearchInstructions customizes browser search for this user. Nil/empty uses the platform default. |
| gallery | 否 | allOf([types.GalleryUserPrefs](#model-136)) | Gallery holds the user's image-gallery UI state: the search<br>activation mode and the per-attribute search-field toggles. Written<br>by the gallery's mode / checkbox interactions via the preferences<br>PUT; read back by GET /knowledge-bases/:id/gallery-config so the<br>gallery renders the same activation state on every device. Nil = the<br>user never touched the gallery controls (mode defaults to "all"). |
| last_active_tenant_id | 否 | integer | LastActiveTenantID remembers the last workspace the user actively<br>switched into, so a fresh login (new device, cleared browser, new<br>refresh token) lands them back in that workspace instead of always<br>bouncing to their home workspace. Written by the SPA's preferences<br>PUT and by service-level SwitchTenant (including when switching<br>home, which stores the home ID). Login / RefreshToken validate that<br>the workspace still exists and the user still has an active membership<br>(or CanAccessAllTenants) before honouring this preference; an<br>invalid pointer is best-effort cleared and the user falls back to<br>home. Refresh JWT claims have no tenant_id, so RefreshToken<br>re-resolves from this field.<br><br>nil  = no preference (use user.TenantID, i.e. home)<br>*0   = "clear preference" sentinel for the partial-update endpoint<br>       (UpdateUserPreferences turns this into nil). Otherwise treat<br>       a stored *0 the same as nil.<br>*N   = preferred workspace id. |
| oidc_only_login | 否 | boolean | OidcOnlyLogin is set server-side when an account is auto-provisioned<br>via OIDC with a random password the user never received. The profile<br>UI hides self-service password rotation until the user sets a known<br>password via ChangePassword (which clears this flag). |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "browser_search_instructions": {
      "description": "BrowserSearchInstructions customizes browser search for this user. Nil/empty uses the platform default.",
      "type": "string"
    },
    "gallery": {
      "description": "Gallery holds the user's image-gallery UI state: the search\nactivation mode and the per-attribute search-field toggles. Written\nby the gallery's mode / checkbox interactions via the preferences\nPUT; read back by GET /knowledge-bases/:id/gallery-config so the\ngallery renders the same activation state on every device. Nil = the\nuser never touched the gallery controls (mode defaults to \"all\").",
      "allOf": [
        {
          "$ref": "#/definitions/types.GalleryUserPrefs"
        }
      ]
    },
    "last_active_tenant_id": {
      "description": "LastActiveTenantID remembers the last workspace the user actively\nswitched into, so a fresh login (new device, cleared browser, new\nrefresh token) lands them back in that workspace instead of always\nbouncing to their home workspace. Written by the SPA's preferences\nPUT and by service-level SwitchTenant (including when switching\nhome, which stores the home ID). Login / RefreshToken validate that\nthe workspace still exists and the user still has an active membership\n(or CanAccessAllTenants) before honouring this preference; an\ninvalid pointer is best-effort cleared and the user falls back to\nhome. Refresh JWT claims have no tenant_id, so RefreshToken\nre-resolves from this field.\n\nnil  = no preference (use user.TenantID, i.e. home)\n*0   = \"clear preference\" sentinel for the partial-update endpoint\n       (UpdateUserPreferences turns this into nil). Otherwise treat\n       a stored *0 the same as nil.\n*N   = preferred workspace id.",
      "type": "integer"
    },
    "oidc_only_login": {
      "description": "OidcOnlyLogin is set server-side when an account is auto-provisioned\nvia OIDC with a random password the user never received. The profile\nUI hides self-service password rotation until the user sets a known\npassword via ChangePassword (which clears this flag).",
      "type": "boolean"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-239"></a>

### types.VLMConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| api_key | 否 | string | API Key |
| base_url | 否 | string | Base URL |
| custom_instructions | 否 | string | CustomInstructions adds KB-specific image interpretation guidance without<br>replacing the system-owned OCR and Markdown output contract. |
| description_language | 否 | string | DescriptionLanguage controls the language used for generated image<br>captions. Empty means follow the document/request language. |
| enabled | 否 | boolean | — |
| interface_type | 否 | string | Interface Type: "ollama" or "openai" |
| model_id | 否 | string | — |
| model_name | 否 | string | 兼容老版本<br>Model Name |

[返回数据结构目录](#models)

<a id="model-240"></a>

### types.VolumeMountConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| enabled | 否 | boolean | Enabled toggles the volume mount for this tenant. |
| mount_path | 否 | string | MountPath is the sandbox-internal path where the volume is mounted.<br>Default: /weknora/tenant/skills (customizable per use case). |
| provider | 否 | string | Provider identifies the volume backend. Currently "e2b" or "cube". |
| volume_id | 否 | string | VolumeID is the provider-specific volume identifier, populated after<br>EnsureVolume / CreateVolume succeeds. |
| volume_name | 否 | string | VolumeName is the human-readable volume name, e.g.<br>"weknora-tenant-&lt;id&gt;-skills". |
| volume_owner_fingerprint | 否 | string | VolumeOwnerFingerprint = sha256(provider + APIKey + APIURL).<br>Used to detect when the tenant switched to a different backend or<br>API key, at which point the volume is no longer reachable and must<br>be recreated. |

[返回数据结构目录](#models)

<a id="model-241"></a>

### types.WeKnoraCloudCredentials

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| app_id | 否 | string | — |
| app_secret | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-242"></a>

### types.WebSearchConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| api_key | 否 | string | Deprecated: Use WebSearchProviderEntity.Parameters.APIKey instead. |
| blacklist | 否 | array&lt;string&gt; | 黑名单规则列表 |
| compression_method | 否 | string | 压缩方法：none, summary, extract, rag |
| document_fragments | 否 | integer | 文档片段数量（用于RAG压缩） |
| embedding_dimension | 否 | integer | 嵌入维度（用于RAG压缩） |
| embedding_model_id | 否 | string | RAG压缩相关配置 |
| include_date | 否 | boolean | 是否包含日期 |
| max_results | 否 | integer | 最大搜索结果数 |
| provider | 否 | string | Deprecated: Use WebSearchProviderEntity.Parameters.APIKey instead. |
| proxy_url | 否 | string | Optional per-request proxy override; normally empty — use WebSearchProviderEntity.Parameters.proxy_url. Merged at call time when set. |
| rerank_model_id | 否 | string | 重排模型ID（用于RAG压缩） |

[返回数据结构目录](#models)

<a id="model-243"></a>

### types.WebSearchProviderEntity

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| created_at | 否 | string | Timestamps |
| deleted_at | 否 | [gorm.DeletedAt](#model-016) | — |
| description | 否 | string | Description |
| id | 否 | string | Unique identifier (UUID, auto-generated) |
| is_default | 否 | boolean | Whether this is the default provider for the workspace |
| name | 否 | string | User-friendly name, e.g., "Production Bing Search" |
| parameters | 否 | allOf([types.WebSearchProviderParameters](#model-244)) | Provider-specific parameters (API key, engine ID, etc.) stored as encrypted JSON |
| provider | 否 | string | Provider type: bing, google, duckduckgo, tavily |
| tenant_id | 否 | integer | Workspace ID for scoping |
| updated_at | 否 | string | — |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "created_at": {
      "description": "Timestamps",
      "type": "string"
    },
    "deleted_at": {
      "$ref": "#/definitions/gorm.DeletedAt"
    },
    "description": {
      "description": "Description",
      "type": "string"
    },
    "id": {
      "description": "Unique identifier (UUID, auto-generated)",
      "type": "string"
    },
    "is_default": {
      "description": "Whether this is the default provider for the workspace",
      "type": "boolean"
    },
    "name": {
      "description": "User-friendly name, e.g., \"Production Bing Search\"",
      "type": "string"
    },
    "parameters": {
      "description": "Provider-specific parameters (API key, engine ID, etc.) stored as encrypted JSON",
      "allOf": [
        {
          "$ref": "#/definitions/types.WebSearchProviderParameters"
        }
      ]
    },
    "provider": {
      "description": "Provider type: bing, google, duckduckgo, tavily",
      "type": "string"
    },
    "tenant_id": {
      "description": "Workspace ID for scoping",
      "type": "integer"
    },
    "updated_at": {
      "type": "string"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-244"></a>

### types.WebSearchProviderParameters

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| api_key | 否 | string | API key for the search provider (encrypted in DB) |
| base_url | 否 | string | Base URL for self-hosted search engines (e.g. SearXNG instance URL).<br>Validated with utils.ValidateURLForSSRF; private hosts must be added to SSRF_WHITELIST. |
| engine_id | 否 | string | Google Custom Search Engine ID (only for Google provider) |
| extra_config | 否 | map&lt;string, string&gt; | Provider-specific extra configuration for future extensibility |
| proxy_url | 否 | string | Optional HTTP/HTTPS proxy URL for outbound search requests (e.g. http://host:port); validated with utils.ValidateURLForSSRF.<br>Does not replace the search API endpoint; only tunnels traffic to the official APIs. |

[返回数据结构目录](#models)

<a id="model-245"></a>

### types.WikiConfig

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| content_instructions | 否 | string | ContentInstructions controls tone, structure and emphasis for generated<br>summary/entity/index prose. Citation and merge rules remain system-owned. |
| extraction_granularity | 否 | string | ExtractionGranularity controls how many candidate slugs Pass 0 extracts<br>per document. Empty / unknown value is treated as WikiExtractionStandard. |
| extraction_instructions | 否 | string | ExtractionInstructions tells candidate extraction which domain concepts<br>to emphasize without replacing the stable JSON/citation protocol. |
| ingest_batch_size | 否 | integer | IngestBatchSize controls how many pending ops a single batch claims and<br>processes before scheduling a follow-up. 0 falls back to the hard-coded<br>default (5). Larger batches amortize per-batch setup and let more docs<br>share the batch-internal Map/Reduce fan-out; smaller batches spread a<br>KB's backlog across more concurrent batches (finer scheduling grain). |
| ingest_map_parallel | 否 | integer | IngestMapParallel sets the errgroup limit for the Map phase<br>(per-document extraction + summary + chunk citation) WITHIN one batch.<br>0 falls back to 10. Bound by the LLM provider's concurrency limit and<br>the worker's outbound HTTP pool. Remember it multiplies with the number<br>of concurrent batches (IngestMaxInflight). |
| ingest_max_inflight | 否 | integer | IngestMaxInflight caps how many ingest batches for THIS KB may run<br>concurrently in the shared wiki worker pool (standard/Redis mode<br>only). 0 falls back to the hard-coded default (4). Since Phase 3<br>removed the exclusive per-KB lock, one KB's backlog could otherwise<br>monopolize the whole pool during a bulk import and starve other KBs;<br>this knob trades a single KB's peak throughput for cross-KB fairness.<br>Set it &gt;= the wiki pool size to effectively disable the cap. |
| ingest_reduce_parallel | 否 | integer | IngestReduceParallel sets the errgroup limit for the Reduce phase<br>(per-slug page write) WITHIN one batch. 0 falls back to 10. Bound by the<br>same LLM concurrency / HTTP pool considerations as the Map phase, plus<br>DB connection pool size. Same multiplier caveat as IngestMapParallel. |
| max_pages_per_ingest | 否 | integer | MaxPagesPerIngest limits pages created/updated per ingest operation (0 = no limit) |
| synthesis_model_id | 否 | string | SynthesisModelID is the LLM model ID used for wiki page generation and updates |

[返回数据结构目录](#models)

<a id="model-246"></a>

### types.WikiFolder

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| created_at | 否 | string | — |
| deleted_at | 否 | [gorm.DeletedAt](#model-016) | — |
| depth | 否 | integer | — |
| id | 否 | string | — |
| knowledge_base_id | 否 | string | — |
| name | 否 | string | — |
| parent_id | 否 | string | — |
| path | 否 | string | — |
| sort_order | 否 | integer | — |
| tenant_id | 否 | integer | — |
| updated_at | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-247"></a>

### types.WikiFolderCreateRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| name | 否 | string | — |
| parent_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-248"></a>

### types.WikiFolderListResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| folders | 否 | array&lt;[types.WikiFolderNode](#model-249)&gt; | — |
| parent_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-249"></a>

### types.WikiFolderNode

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| created_at | 否 | string | — |
| deleted_at | 否 | [gorm.DeletedAt](#model-016) | — |
| depth | 否 | integer | — |
| has_children | 否 | boolean | — |
| id | 否 | string | — |
| knowledge_base_id | 否 | string | — |
| name | 否 | string | — |
| page_count | 否 | integer | — |
| parent_id | 否 | string | — |
| path | 否 | string | — |
| sort_order | 否 | integer | — |
| tenant_id | 否 | integer | — |
| updated_at | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-250"></a>

### types.WikiFolderUpdateRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| move_parent | 否 | boolean | — |
| name | 否 | string | — |
| parent_id | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-251"></a>

### types.WikiGraphData

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| edges | 否 | array&lt;[types.WikiGraphEdge](#model-252)&gt; | — |
| meta | 否 | [types.WikiGraphMeta](#model-253) | — |
| nodes | 否 | array&lt;[types.WikiGraphNode](#model-254)&gt; | — |

[返回数据结构目录](#models)

<a id="model-252"></a>

### types.WikiGraphEdge

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| source | 否 | string | source slug |
| target | 否 | string | target slug |

[返回数据结构目录](#models)

<a id="model-253"></a>

### types.WikiGraphMeta

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| center | 否 | string | populated in ego mode |
| depth | 否 | integer | populated in ego mode |
| familiar_count | 否 | integer | FamiliarCount is how many returned nodes are lit up for this person. |
| mode | 否 | string | — |
| returned | 否 | integer | number of nodes actually returned |
| total | 否 | integer | total node count in the KB before filtering/limit |
| truncated | 否 | boolean | true when Returned &lt; Total (after filters) |

[返回数据结构目录](#models)

<a id="model-254"></a>

### types.WikiGraphNode

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| familiar | 否 | boolean | Familiar is true when this page was built from a document this person<br>keeps citing in answers. It is a personal overlay, not a property of<br>the page: two people looking at the same wiki see different highlights. |
| link_count | 否 | integer | Number of inbound + outbound links |
| page_type | 否 | string | — |
| slug | 否 | string | — |
| title | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-255"></a>

### types.WikiIndexEntry

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| category_path | 否 | array&lt;string&gt; | — |
| depth | 否 | integer | — |
| parent_slug | 否 | string | — |
| slug | 否 | string | — |
| sort_order | 否 | integer | — |
| summary | 否 | string | — |
| title | 否 | string | — |
| wiki_path | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-256"></a>

### types.WikiIndexGroup

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| items | 否 | array&lt;[types.WikiIndexEntry](#model-255)&gt; | — |
| next_cursor | 否 | string | — |
| total | 否 | integer | — |
| type | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-257"></a>

### types.WikiIndexResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| groups | 否 | array&lt;[types.WikiIndexGroup](#model-256)&gt; | — |
| intro | 否 | string | — |
| version | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-258"></a>

### types.WikiPage

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| aliases | 否 | array&lt;string&gt; | Alternate names, abbreviations, acronyms or translated names |
| category_path | 否 | array&lt;string&gt; | CategoryPath is the directory breadcrumb that groups this page in the<br>wiki browser, e.g. ["AI", "LLM 应用", "RAG"]. Derived cache of the<br>folder chain identified by FolderID. |
| chunk_refs | 否 | array&lt;string&gt; | ChunkRefs records the specific source-document chunks this page was<br>built from — one UUID per cited chunk. Populated during ingest from<br>the chunk-citation pass; refreshed wholesale whenever the page is<br>re-materialized. Empty for summary pages (they are document-level<br>synopses and don't carry chunk-level citations). Use this when you<br>need to surface the underlying evidence for a wiki page, or to<br>retract citations when a source document is deleted. |
| content | 否 | string | Full markdown content |
| created_at | 否 | string | Creation time |
| deleted_at | 否 | allOf([gorm.DeletedAt](#model-016)) | Soft delete |
| depth | 否 | integer | Depth is len(CategoryPath), cached for filtering / display. |
| folder_id | 否 | string | FolderID is the single source of truth for where this page sits in the<br>directory tree — a reference to wiki_folders.id ("" = wiki root). The<br>CategoryPath / WikiPath / Depth fields below are denormalized caches<br>recomputed from this folder's chain on every write so list/index/search<br>queries don't have to join wiki_folders. |
| id | 否 | string | Unique identifier (UUID) |
| in_links | 否 | array&lt;string&gt; | Slugs of pages that link TO this page (backlinks) |
| knowledge_base_id | 否 | string | Knowledge base this page belongs to |
| last_edit_source | 否 | string | LastEditSource records who authored the CURRENT version: pipeline &#124;<br>agent &#124; user &#124; revert. Empty for legacy rows (treated as pipeline).<br>When the version is superseded this value travels into the revision<br>snapshot, so each historical version keeps its own author kind. |
| last_editor_id | 否 | string | LastEditorID is the user id of the caller that produced the current<br>version (empty for background pipeline writes). |
| out_links | 否 | array&lt;string&gt; | Slugs of pages this page links to (outbound links) |
| page_metadata | 否 | array&lt;integer&gt; | Arbitrary metadata (tags, categories, dates, etc.) |
| page_type | 否 | string | Page type: summary, entity, concept, index, synthesis, comparison |
| parent_slug | 否 | string | ParentSlug optionally points at the wiki page that should act as this<br>page's semantic parent in the directory tree. The parent may be empty<br>when the page is grouped only by FolderID. |
| slug | 否 | string | URL-friendly slug for addressing, e.g. "entity/acme-corp", "concept/rag"<br>Unique within a knowledge base |
| sort_order | 否 | integer | SortOrder allows generated or manually edited pages to control sibling<br>ordering before falling back to title. |
| source_refs | 否 | array&lt;string&gt; | References to source knowledge IDs that contributed to this page.<br>Format matches the legacy "&lt;knowledge_id&gt;&#124;&lt;doc_title&gt;" convention used<br>across the ingest pipeline, so retract / display code can split on `&#124;`<br>to recover the title. Document-level granularity. |
| status | 否 | string | Page status: draft, published, archived |
| summary | 否 | string | One-line summary for index listing |
| tenant_id | 否 | integer | Workspace ID for multi-workspace isolation |
| title | 否 | string | Human-readable title |
| updated_at | 否 | string | Last update time |
| version | 否 | integer | Version number. Incremented only when a user-visible content field<br>(title, content, summary, page_type, status) actually changes; pure<br>bookkeeping writes (link maintenance, same-content re-ingest, status<br>sync from background jobs) leave it untouched so it can be used as a<br>real "the page was edited" signal. |
| wiki_path | 否 | string | WikiPath is a normalized, sortable path derived from page_type,<br>category_path, and title. It keeps large directory listings cheap to sort. |

<details>
<summary>完整嵌套结构 Schema</summary>

```json
{
  "type": "object",
  "properties": {
    "aliases": {
      "description": "Alternate names, abbreviations, acronyms or translated names",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "category_path": {
      "description": "CategoryPath is the directory breadcrumb that groups this page in the\nwiki browser, e.g. [\"AI\", \"LLM 应用\", \"RAG\"]. Derived cache of the\nfolder chain identified by FolderID.",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "chunk_refs": {
      "description": "ChunkRefs records the specific source-document chunks this page was\nbuilt from — one UUID per cited chunk. Populated during ingest from\nthe chunk-citation pass; refreshed wholesale whenever the page is\nre-materialized. Empty for summary pages (they are document-level\nsynopses and don't carry chunk-level citations). Use this when you\nneed to surface the underlying evidence for a wiki page, or to\nretract citations when a source document is deleted.",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "content": {
      "description": "Full markdown content",
      "type": "string"
    },
    "created_at": {
      "description": "Creation time",
      "type": "string"
    },
    "deleted_at": {
      "description": "Soft delete",
      "allOf": [
        {
          "$ref": "#/definitions/gorm.DeletedAt"
        }
      ]
    },
    "depth": {
      "description": "Depth is len(CategoryPath), cached for filtering / display.",
      "type": "integer"
    },
    "folder_id": {
      "description": "FolderID is the single source of truth for where this page sits in the\ndirectory tree — a reference to wiki_folders.id (\"\" = wiki root). The\nCategoryPath / WikiPath / Depth fields below are denormalized caches\nrecomputed from this folder's chain on every write so list/index/search\nqueries don't have to join wiki_folders.",
      "type": "string"
    },
    "id": {
      "description": "Unique identifier (UUID)",
      "type": "string"
    },
    "in_links": {
      "description": "Slugs of pages that link TO this page (backlinks)",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "knowledge_base_id": {
      "description": "Knowledge base this page belongs to",
      "type": "string"
    },
    "last_edit_source": {
      "description": "LastEditSource records who authored the CURRENT version: pipeline |\nagent | user | revert. Empty for legacy rows (treated as pipeline).\nWhen the version is superseded this value travels into the revision\nsnapshot, so each historical version keeps its own author kind.",
      "type": "string"
    },
    "last_editor_id": {
      "description": "LastEditorID is the user id of the caller that produced the current\nversion (empty for background pipeline writes).",
      "type": "string"
    },
    "out_links": {
      "description": "Slugs of pages this page links to (outbound links)",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "page_metadata": {
      "description": "Arbitrary metadata (tags, categories, dates, etc.)",
      "type": "array",
      "items": {
        "type": "integer"
      }
    },
    "page_type": {
      "description": "Page type: summary, entity, concept, index, synthesis, comparison",
      "type": "string"
    },
    "parent_slug": {
      "description": "ParentSlug optionally points at the wiki page that should act as this\npage's semantic parent in the directory tree. The parent may be empty\nwhen the page is grouped only by FolderID.",
      "type": "string"
    },
    "slug": {
      "description": "URL-friendly slug for addressing, e.g. \"entity/acme-corp\", \"concept/rag\"\nUnique within a knowledge base",
      "type": "string"
    },
    "sort_order": {
      "description": "SortOrder allows generated or manually edited pages to control sibling\nordering before falling back to title.",
      "type": "integer"
    },
    "source_refs": {
      "description": "References to source knowledge IDs that contributed to this page.\nFormat matches the legacy \"<knowledge_id>|<doc_title>\" convention used\nacross the ingest pipeline, so retract / display code can split on `|`\nto recover the title. Document-level granularity.",
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "status": {
      "description": "Page status: draft, published, archived",
      "type": "string"
    },
    "summary": {
      "description": "One-line summary for index listing",
      "type": "string"
    },
    "tenant_id": {
      "description": "Workspace ID for multi-workspace isolation",
      "type": "integer"
    },
    "title": {
      "description": "Human-readable title",
      "type": "string"
    },
    "updated_at": {
      "description": "Last update time",
      "type": "string"
    },
    "version": {
      "description": "Version number. Incremented only when a user-visible content field\n(title, content, summary, page_type, status) actually changes; pure\nbookkeeping writes (link maintenance, same-content re-ingest, status\nsync from background jobs) leave it untouched so it can be used as a\nreal \"the page was edited\" signal.",
      "type": "integer"
    },
    "wiki_path": {
      "description": "WikiPath is a normalized, sortable path derived from page_type,\ncategory_path, and title. It keeps large directory listings cheap to sort.",
      "type": "string"
    }
  }
}
```

</details>

[返回数据结构目录](#models)

<a id="model-259"></a>

### types.WikiPageIssue

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| created_at | 否 | string | — |
| deleted_at | 否 | [gorm.DeletedAt](#model-016) | — |
| description | 否 | string | — |
| id | 否 | string | — |
| issue_type | 否 | string | — |
| knowledge_base_id | 否 | string | — |
| reported_by | 否 | string | — |
| slug | 否 | string | — |
| status | 否 | string | — |
| suspected_knowledge_ids | 否 | array&lt;string&gt; | — |
| tenant_id | 否 | integer | — |
| updated_at | 否 | string | — |

[返回数据结构目录](#models)

<a id="model-260"></a>

### types.WikiPageListResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| page | 否 | integer | — |
| page_size | 否 | integer | — |
| pages | 否 | array&lt;[types.WikiPage](#model-258)&gt; | — |
| total | 否 | integer | — |
| total_pages | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-261"></a>

### types.WikiPageMoveRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| folder_id | 否 | string | — |
| slug | 是 | string | — |

[返回数据结构目录](#models)

<a id="model-262"></a>

### types.WikiPageRevertRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| slug | 是 | string | — |
| version | 是 | integer | — |

[返回数据结构目录](#models)

<a id="model-263"></a>

### types.WikiPageRevision

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| aliases | 否 | array&lt;string&gt; | — |
| content | 否 | string | — |
| created_at | 否 | string | — |
| edit_source | 否 | string | Author of THIS version (same semantics as WikiPage.LastEditSource). |
| edited_at | 否 | string | When this version was authored (the page's updated_at while current). |
| editor_id | 否 | string | — |
| id | 否 | string | — |
| knowledge_base_id | 否 | string | — |
| page_id | 否 | string | — |
| page_type | 否 | string | — |
| slug | 否 | string | — |
| status | 否 | string | — |
| summary | 否 | string | — |
| tenant_id | 否 | integer | — |
| title | 否 | string | — |
| version | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-264"></a>

### types.WikiPageRevisionListResponse

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| current_version | 否 | integer | — |
| revisions | 否 | array&lt;[types.WikiPageRevision](#model-263)&gt; | — |
| total | 否 | integer | — |

[返回数据结构目录](#models)

<a id="model-265"></a>

### types.WikiPageUpdateRequest

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| aliases | 否 | array&lt;string&gt; | — |
| content | 否 | string | — |
| page_type | 否 | string | — |
| status | 否 | string | — |
| summary | 否 | string | — |
| title | 否 | string | — |
| version | 否 | integer | Version is the optimistic-lock guard: when &gt; 0 the update is rejected<br>with a conflict if the stored version differs (someone else edited the<br>page since the client loaded it). 0 skips the check (legacy clients). |

[返回数据结构目录](#models)

<a id="model-266"></a>

### types.WikiStats

| 字段 | 必填 | 类型 | 说明 / 约束 |
| --- | --- | --- | --- |
| is_active | 否 | boolean | whether wiki ingestion is currently running |
| orphan_count | 否 | integer | pages with no inbound links |
| pages_by_type | 否 | map&lt;string, integer&gt; | — |
| pending_issues | 否 | integer | number of pending wiki issues |
| pending_tasks | 否 | integer | number of documents waiting to be ingested |
| recent_updates | 否 | array&lt;[types.WikiPage](#model-258)&gt; | last N updated pages |
| total_links | 否 | integer | — |
| total_pages | 否 | integer | — |

[返回数据结构目录](#models)

<a id="update"></a>

## 更新说明

本文是 2026-10-17 的源码快照，接口变更后需要同步更新。Swagger 的标准生成命令是：

```bash
make docs
```

`make docs` 需要本地安装 Swag CLI。生成 JSON / YAML 后，还应按 `internal/router/` 校验实际路径、补充未注释路由，再同步本 Markdown 的参数表、响应表和目录。不要把本文的生成日期当作远端服务的部署版本。
