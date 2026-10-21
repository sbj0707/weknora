# Azure Blob Storage：设计与任务清单

> 设计及实施日期：2026-10-20。功能已接入，自动化检查结果见第 13 节；真实 Azure 环境验收单独列出。

## 1. 目标与范围

在现有存储类型中增加 **Azure Blob Storage**，复用「存储实例 → 知识库绑定」机制，为原文、解析图片、文档页面图片等文件提供统一存储。

### 已确认的需求

- 存储类型增加 Azure Blob Storage，内部提供商标识为 `azure`。
- 知识库可选择已配置的 Azure 存储实例，同一空间允许配置多个 Azure 实例。
- 支持账户密钥和托管身份两种认证方式。
- 配置表单增加连接串解析辅助入口，解析后自动填写账户、密钥和 Blob Endpoint。
- 连接串解析、测试连接和保存是三个独立操作。
- 复用现有文件上传、读取、预览、下载、复制、删除和资源鉴权能力。

### 第一版边界

- 不在应用中创建 Azure Storage Account 或 Container；用户先在 Azure 配置好资源。
- 连接串解析第一版支持账户密钥连接串；SAS、`UseDevelopmentStorage=true` 等模拟器连接串明确提示暂不支持。
- 不自动迁移已有知识库文件；旧文件迁移是独立任务。
- 不要求 Container 公开，不把临时签名地址作为长期资源标识。
- 不把此任务扩展为页面渲染或模型图片理解的实现；仅提供这些功能需要的存储能力，相关设计见 [文档页面图片辅助回答](page-image-answer-evidence.md)。

## 2. 当前基础与接入位置

- `storage_backends` 已保存存储实例，`provider` 是字符串，`config` 是 JSON 配置。
- 知识库通过 `storage_backend_id` 绑定实例；不应仅凭提供商类型寻找账户。
- `FileService` 已定义连接检测、上传、读取、下载链接、删除和复制接口。
- 已有 `resource://` 资源身份、`storage://<backend-id>/...` 实例作用域和文件访问鉴权机制。
- 前端「设置 → 存储管理」已有实例列表、新建、编辑和测试连接表单；知识库已有存储实例选择界面。

实现时扩展上述能力，不再增加平行的 Azure 专属文件管理系统。

## 3. 配置表单

在「设置 → 存储管理 → 新增存储」中增加 Azure Blob Storage 选项。表单沿用现有抽屉样式、主题和交互。

| 页面字段 | 建议字段名 | 必填与行为 |
| --- | --- | --- |
| 名称 | `name` | 必填，复用存储实例名称校验 |
| 存储类型 | `provider` | `azure`，显示 Azure Blob Storage |
| Storage Account | `config.account_name` | 必填，账户名称 |
| Blob Endpoint | `config.endpoint` | 必填；可根据账户名生成默认公共云地址，也支持明确配置的其他 Azure 云环境地址 |
| Container | `config.container_name` | 必填，现有容器名称 |
| 路径前缀 | `config.path_prefix` | 可选，相对路径，不允许越过配置的存储范围 |
| 认证方式 | `config.auth_mode` | `managed_identity` 或 `account_key` |
| Account Key | `config.account_key` | 账户密钥模式必填，密码输入，保存后脱敏 |
| Managed Identity Client ID | `config.managed_identity_client_id` | 托管身份模式可选；为空使用系统分配身份，非空指定用户分配身份 |

- Azure 表单不显示 S3 的 Region、Access Key ID、Force Path Style 等配置项。
- 默认推荐托管身份；用户可以主动选择账户密钥，连接串解析成功时切换为账户密钥。
- 根据认证方式进行校验，不在保存、测试时自动尝试其他身份或历史密钥。
- 切换认证方式后，仅保存当前模式需要的凭据，防止旧凭据继续生效。
- 编辑时沿用现有物理位置不可随意修改的约定；账户、Endpoint、Container、路径前缀参与物理位置判断，凭据轮换不改变资源身份。
- 名称、Container 和路径前缀均可单独填写，连接串辅助入口不是必经步骤。

### 配置示例

```json
{
  "name": "Azure 生产文档存储",
  "provider": "azure",
  "config": {
    "account_name": "giostorageblob",
    "endpoint": "https://giostorageblob.blob.core.windows.net",
    "container_name": "weknora",
    "path_prefix": "production/",
    "auth_mode": "managed_identity"
  }
}
```

账户密钥模式使用 `auth_mode: "account_key"` 并提供 `account_key`；读取接口只返回脱敏值。

## 4. 连接串解析辅助功能

### 4.1 界面与操作

Azure 配置表单顶部增加可折叠的「从连接串填写」区域，包含连接串输入框和「解析并填入」按钮。

```text
从连接串填写
┌─────────────────────────────────────────────┐
│ 粘贴 Azure Storage Connection String        │
└─────────────────────────────────────────────┘
                                [解析并填入]
```

- 用户点击后在浏览器本地解析，不单独调用解析 API，也不探测远程 Endpoint。
- 校验成功后一次性填入可确定的字段；任一关键校验失败时不修改原表单。
- 成功提示：「已填入账户、密钥和 Blob Endpoint，请补充 Container 并测试连接。」
- 解析成功仅表示配置格式可识别，不表示账户存在、密钥有效、网络可达或权限充足。
- 解析后仍允许手动调整表单；不自动保存、不自动测试连接。
- 原始连接串只保留在当前组件内存中，关闭、切换实例或重置表单时清空；不写入浏览器持久化存储、URL、日志或遥测。

### 4.2 用户示例与填充结果

输入：

```text
DefaultEndpointsProtocol=https;AccountName=giostorageblob;AccountKey=xxxx;EndpointSuffix=core.windows.net
```

| 表单字段 | 解析结果 |
| --- | --- |
| 认证方式 | 账户密钥，即 `account_key` |
| Storage Account | `giostorageblob` |
| Account Key | `xxxx`，以密码形式显示；这里是示例占位值，不代表有效凭据 |
| Blob Endpoint | `https://giostorageblob.blob.core.windows.net` |
| 名称 | 保持原值 |
| Container | 保持原值，未填写时由用户补充 |
| 路径前缀 | 保持原值 |

连接串本身不提供此示例中的 Container 和路径前缀，不能自动猜测。

### 4.3 解析规则

1. 按分号拆分条目，忽略首尾空白和空条目，允许末尾分号及换行排版。
2. 每条只按**第一个 `=`** 拆分键和值；保留密钥中的 `+`、`/`、末尾 `=`、`==`，不擅自 URL 解码。
3. 字段名不区分大小写，字段顺序不限；字段值保留大小写，仅去除首尾空白。
4. `AccountName`、`AccountKey` 必须存在且非空；不通过实际联网验证密钥有效性。
5. 优先使用显式 `BlobEndpoint`。未提供时按 `{protocol}://{account}.blob.{suffix}` 生成。
6. 对没有显式 Endpoint 的账户密钥连接串，辅助工具建议默认协议为 `https`、后缀为 `core.windows.net`；显式值优先，默认值行为需在测试中固定。
7. 支持 `EndpointSuffix=core.chinacloudapi.cn` 等后缀；显式 Endpoint 必须是合法 HTTP(S) URL，生产配置要求 HTTPS，并复用后端 Endpoint 校验策略。
8. 相同字段重复且值一致时允许；存在冲突时明确报错，不采用“最后一项覆盖”的行为。
9. 忽略与 Blob 无关的 `QueueEndpoint`、`TableEndpoint`、`FileEndpoint`；其他未识别字段提示未使用，不将其透传为客户端配置。
10. 遇到 `SharedAccessSignature`、模拟器快捷连接串或混合认证字段时明确提示当前格式不支持，不部分填入或退回其他认证模式。
11. 严重格式错误、缺少必要项、非法 Endpoint 或重复冲突均返回不含密钥正文的错误信息。

### 4.4 解析测试示例

| 场景 | 预期 |
| --- | --- |
| 用户提供的连接串 | 正确填入账户、密钥、认证模式和生成的 Endpoint |
| 密钥包含 `+`、`/`、`==` | 原样保留密钥内容 |
| 字段乱序、不同键名大小写、末尾分号 | 正常解析 |
| 显式 `BlobEndpoint` 与后缀同时存在 | 使用显式 Endpoint |
| 中国区后缀 | 正确生成 `*.blob.core.chinacloudapi.cn` |
| Container、名称和前缀已有值 | 保留原值 |
| 缺少账户或密钥、条目没有 `=` | 显示错误，原表单保持不变 |
| 重复字段值冲突 | 显示冲突字段名称，不输出其密钥值 |
| SAS 或模拟器连接串 | 明确提示暂不支持，原表单保持不变 |
| 非法协议或 URL | 拒绝填充；直接提交配置时后端也必须拒绝 |

## 5. 后端驱动与资源路径

采用官方 Go SDK：`github.com/Azure/azure-sdk-for-go/sdk/storage/azblob`；托管身份认证使用 `github.com/Azure/azure-sdk-for-go/sdk/azidentity`。依赖版本由实施时与当前 Go 版本兼容的稳定版本确定并锁定。

新增 Azure 驱动，实现已有 `FileService` 接口：

| 方法 | 实现要求 |
| --- | --- |
| `CheckConnectivity` | 检测账户地址、认证和容器访问，返回可区分的错误 |
| `SaveFile` | 流式上传原文，保留必要的文件类型和下载元数据 |
| `SaveBytes` | 保存解析图片、页面图片等二进制文件 |
| `GetFile` | 流式读取，支持取消和超时，避免完整文件无界读入内存 |
| `GetFileURL` | 接入现有资源临时访问链接或文件代理，不依赖公开 Container |
| `DeleteFile` | 删除对应 Blob，不存在时按已删除处理 |
| `CopyFile` | 生成独立目标对象；异步复制必须等待成功，不能返回尚未完成的副本 |

建议对象布局如下；最终路径生成需复用现有命名规则和资源归属校验：

```text
{path_prefix}/tenants/{tenant_id}/knowledge/{knowledge_id}/
  original/...
  images/...
  pages/{source_hash}/page-0042.jpg
```

- 底层路径示例：`storage://<backend-id>/azure://<container>/<blob-key>`；业务仍通过现有 `resource://<handle>` 标识访问资源。
- 多 Azure 账户、同名 Container 和多个相同类型实例必须按存储实例 ID 精确解析，不能回退到错误账户。
- 校验 Container、对象路径、租户和配置前缀；防止路径越界或使用任意外部 URL 读取文件。
- 原文、图片和后续页图走同一知识库存储实例；复制、移动、删除沿用现有资源生命周期。
- `temp=true` 的对象有明确的临时前缀和清理方案；不能假定写入临时对象后 Azure 自动过期删除。
- 对超时、限流及短暂失败采用有界重试；记录可诊断错误，不记录凭据或完整签名 URL。

## 6. 认证、权限与文件访问

### 托管身份

- 根据配置显式创建托管身份凭据，不在生产环境无意使用其他开发身份或历史凭据。
- Azure VM 启用系统分配身份，或配置用户分配身份的 Client ID。
- 对指定 Container 授予 `Storage Blob Data Contributor`，满足应用上传、读取和删除文件的需求。
- 验证实际 app 容器可访问身份令牌服务与 Blob Endpoint；按目标 Azure 云环境核对身份认证配置。

### 账户密钥

- 新字段接入现有加密、解密、响应脱敏和脱敏占位值保留机制。
- 缺少必要加密配置时给出明确错误，不把密钥以明文持久化作为回退。
- 密钥轮换只更新凭据，不改变资源身份和物理位置。
- 知识库导出不包含密钥或原始连接串；请求、审计、SDK 错误和调试日志均需脱敏。

### 文件访问

- 浏览器继续通过现有后端鉴权接口预览和下载，不要求配置浏览器跨域直传。
- 复用资源目录的应用临时访问链接；有权限的外部集成使用现有访问授权机制。
- 预览涉及的 Content-Type、Content-Disposition、文件名编码以及现有 Range/分页读取行为需做兼容验证。
- Azure SAS 直链作为后续扩展，第一版不依赖账户级 SAS，也不保存带有效期的签名链接作为对象身份。
- 页图辅助回答时，由后端读取 Blob 字节并通过多模态消息发送给回答模型，不将需要应用登录的地址直接交给模型。

## 7. 测试连接

复用现有存储实例测试入口，前端明确说明会创建并清理一个临时测试对象。

1. 校验配置与 Endpoint。
2. 检查认证及 Container 可访问性，不自动创建容器。
3. 在当前实例允许的路径范围内上传唯一的小型测试对象。
4. 读取并校验内容。
5. 删除测试对象；任一步失败仍尝试清理，清理失败单独提示。

测试返回可区分的配置错误、网络错误、身份获取失败、密钥无效、Container 不存在、读写权限不足和清理失败。只读连通不等于完整读写能力通过，不向客户端回传 SDK 原始敏感信息。

## 8. 数据、部署与兼容

- 优先扩展 `StorageBackendConfig` 及相关存储引擎适配配置，复用现有 `storage_backends` JSON，不新增平行的 Azure 配置表。
- 当前存储表的提供商是字符串，预计不需要新增数据库表；实施时检查 PostgreSQL、SQLite 的约束、序列化及兼容逻辑，确有必要时新增迁移，不修改历史迁移。
- 补齐提供商白名单、资源引用解析、实例解析、系统默认存储和环境变量配置入口。
- 原知识库保持原存储绑定，有内容的知识库继续执行现有修改限制；不因新增 Azure 类型而重写历史文件路径。
- 只新增存储能力时更新 app、frontend 即可，不新增 Docker 服务，不要求更新 DocReader。
- Azure 资源、网络可达性、托管身份和权限由部署环境准备；使用账户密钥时按既有受保护配置方式注入或保存。
- 真实集成测试使用独立 Container 或独立测试前缀，不能清理其他业务对象。

## 9. 开发任务清单

### 配置与后端

- [x] 添加 `azure` 提供商白名单和显示名称。
- [x] 扩展实例、系统存储引擎配置及前后端类型，按认证方式校验 Azure 字段。
- [x] 实现 Azure 密钥加密、脱敏、占位值保留、认证切换和轮换行为。
- [x] 补齐物理位置比较，限制有文件实例的账户、Endpoint、Container 和前缀变更。
- [x] 引入并锁定官方 SDK，配置传输、Endpoint 校验、超时、取消和重试。
- [x] 新增 Azure `FileService` 驱动并接入工厂、依赖注入、实例解析和系统默认存储。
- [x] 实现上传、读取、删除、独立复制及临时对象清理约定。
- [x] 补齐 `azure://` 与已有资源引用的解析和实例隔离。
- [x] 复用现有 API 的权限、响应结构及访问链接机制。
- [x] 实现连接和读写测试，确保测试对象可清理且错误脱敏。
- [x] 补齐环境变量说明与部署示例，保持其他存储驱动兼容。

### 前端与连接串解析

- [x] 存储类型选择增加 Azure Blob Storage，列表增加对应名称和图标。
- [x] 实现账户、Endpoint、Container、前缀、认证方式和条件凭据表单。
- [x] 调整现有通用表单条件，Azure 不强制填写 Region、S3 Access Key ID 等字段。
- [x] 增加「从连接串填写」折叠区域和「解析并填入」按钮。
- [x] 实现独立、无网络副作用的连接串解析函数，原子更新目标字段。
- [x] 处理 `=`、字段顺序、空白、后缀、显式 Endpoint、缺失项和重复冲突。
- [x] 不支持的 SAS、模拟器和混合认证连接串给出明确提示。
- [x] 保留名称、Container、路径前缀；失败保留整个原表单。
- [x] 原始连接串不持久化、不记录日志，表单重置与切换时清空。
- [x] 测试连接显示分阶段结果及测试对象创建、清理说明。
- [x] 知识库存储实例选择、配置导入导出脱敏和已有编辑限制保持兼容。
- [x] 补齐国际化、键盘操作、浅色/深色主题和窄窗口布局。

### 测试与验收

以下自动化项目采用 SDK HTTP 模拟、SQLite 及现有代理回归测试；它们不替代真实 Azure 部署验收。

- [x] 覆盖第 4.4 节解析场景，验证密钥特殊字符无损、原子填充和失败不覆盖。
- [x] 验证账户密钥驱动及无效密钥、容器、权限、网络错误的安全分类；托管身份明确使用专属凭据构造器。
- [x] 验证密钥加密读写、脱敏编辑保留、轮换、切换认证和导出不泄露。
- [x] 验证原文和图片上传、流式读取、独立复制与删除，包含中文文件名和多块大文件。
- [x] 验证多个 Azure 实例、同名 Container、租户隔离、路径前缀和越界拒绝。
- [x] 验证私有资源访问授权链接及现有文件代理、签名链接和访问校验。
- [x] 验证复制完成后返回、任务取消、有界重试和连接测试对象清理。
- [x] 验证现有本地、MinIO、S3 等存储相关测试与历史资源路径；后端 11 个受影响包的专项回归通过。
- [ ] 在真实 Azure 环境验证 app 容器的账户密钥、托管身份、RBAC、私有网络和文件读写。当前未提供集成测试环境，保留为部署验收项。
- [ ] 在浏览器实测两种认证的创建、编辑、解析、保存、预览、Range 下载、深浅主题和窄窗口。当前已完成代码接入及自动化检查，未进行浏览器操作。
- [x] 运行受影响后端测试、前端专项及回归、国际化、类型检查和生产构建；结果见第 13 节。

## 10. 建议修改位置与实施顺序

| 位置 | 主要工作 |
| --- | --- |
| `internal/types/storagebackend.go`、`internal/types/tenant.go` | Azure 配置、校验、加密、脱敏与适配 |
| `internal/storageallowlist/allowlist.go` | 新增提供商 |
| `internal/application/service/file/azure.go`（新增）、`factory.go` | Azure 驱动与工厂接入 |
| `internal/application/service/storagebackend.go`、`internal/container/` | 实例管理、环境配置和依赖注入 |
| `internal/types/resource.go`、资源路径及文件访问相关入口 | 提供商路径、资源解析和租户边界 |
| `frontend/src/api/storage-backend.ts` | 请求与响应类型 |
| `frontend/src/views/settings/StorageBackendSettings.vue` | 存储表单、连接串辅助入口、测试结果 |
| `frontend/src/utils/azureConnectionString.ts`（建议新增） | 连接串解析与无副作用校验 |
| `frontend/src/views/knowledge/settings/KBStorageSettings.vue` | 核对新实例在知识库中的可选及绑定行为 |

实施顺序：配置模型与凭据处理 → Azure 驱动与资源接入 → 测试连接 → 前端表单及连接串解析 → 集成、兼容与部署验收。

## 11. 官方参考

- [Azure Blob Storage Go SDK 入门](https://learn.microsoft.com/en-us/azure/storage/blobs/storage-blob-go-get-started)
- [Azure Storage 连接串格式与自定义 Endpoint](https://learn.microsoft.com/en-us/azure/storage/common/storage-configure-connection-string)
- [Microsoft Entra ID 与托管身份访问 Blob](https://learn.microsoft.com/en-us/azure/storage/blobs/authorize-access-azure-active-directory)
- [Blob 数据访问角色与 Container 范围授权](https://learn.microsoft.com/en-us/azure/storage/blobs/assign-azure-role-data-access)


## 12. 使用与部署

### 从界面创建实例

1. 打开「设置 → 存储管理 → 新增存储」，类型选择 **Azure Blob Storage**。
2. 选择托管身份，或展开「从连接串填写」，粘贴账户密钥连接串并点击「解析并填入」。解析不会发起网络请求或自动保存。
3. 填写已有 Container 和可选路径前缀。Endpoint 留空时使用该账户的公共云地址；中国区或私有 Endpoint 请明确填写完整 HTTPS 地址。
4. 点击「测试连接」，确认容器访问、上传、读取、清理均通过后保存。
5. 在知识库的「存储引擎」选择此实例。有内容的知识库继续执行原有绑定修改限制。

编辑时可修改名称和凭据。账户、Endpoint、Container、前缀保持不变；如需移动文件，新建实例后走原有迁移流程。编辑中的连接测试会使用当前表单和新密钥；返回的脱敏占位值只会从当前租户的指定实例补回，显式清空密钥会校验失败。

### 环境变量默认存储

通常直接在存储管理中配置即可，无需修改默认存储。若希望进程默认使用 Azure，可在部署环境设置：

```dotenv
STORAGE_TYPE=azure
AZURE_STORAGE_ACCOUNT_NAME=giostorageblob
AZURE_STORAGE_BLOB_ENDPOINT=https://giostorageblob.blob.core.windows.net
AZURE_STORAGE_CONTAINER_NAME=weknora
AZURE_STORAGE_PATH_PREFIX=production/
AZURE_STORAGE_AUTH_MODE=managed_identity
# 指定用户分配身份时填写；空值使用系统分配身份。
AZURE_STORAGE_MANAGED_IDENTITY_CLIENT_ID=
# 账户密钥模式时切换 AUTH_MODE=account_key，并安全注入下面变量。
AZURE_STORAGE_ACCOUNT_KEY=
```

- `STORAGE_ALLOW_LIST` 如已显式设置，需要包含 `azure`。
- 保存账户密钥到数据库前必须设置 **32 字节**的 `SYSTEM_AES_KEY`，并保留该密钥以便后续解密。缺失或长度错误时拒绝保存，不回退到明文。
- 私有资源链接继续使用 `APP_EXTERNAL_URL` 与现有访问授权机制；无需开放 Container 或启用浏览器 Blob 跨域直传。
- 私有 Endpoint 需配置正确的 DNS、网络和现有 SSRF 白名单；托管身份需在实际 app 运行环境启用并授予容器数据访问权限。
- 已补齐 `.env.example` 与 `docker-compose.yml` 中的 Azure 环境变量透传。只需重新构建、更新 app 和 frontend，不新增数据库表或容器，也无需更新 DocReader。

### 临时对象清理

- 连接测试对象位于 `{prefix}/.connection-check/`，成功、读取失败、上传失败或取消后均尝试删除；删除失败会作为独立阶段返回，不能把测试显示为成功。
- `SaveBytes(temp=true)` 使用 `{prefix}/tenants/{tenantID}/temporary/`。临时附件沿用应用已有的删除和过期任务，调用统一 `DeleteFile` 清理 Azure 对象。
- Azure 驱动不会创建 Azure 生命周期规则，也不能假定对象自动过期。对于失败导出 CSV 等无应用过期任务的临时输出，部署方应在 Azure 配置专属临时路径的生命周期规则。不要对整个业务前缀设置删除规则。

例如：在 Azure Storage 的生命周期管理中，对 Container `weknora` 下租户 `42` 的临时目录及连接探针设置以下规则；其他租户需要增加各自的精确前缀。天数按业务下载保留期调整。

```json
{
  "rules": [{
    "enabled": true,
    "name": "weknora-temporary-only",
    "type": "Lifecycle",
    "definition": {
      "filters": {
        "blobTypes": ["blockBlob"],
        "prefixMatch": [
          "weknora/production/tenants/42/temporary/",
          "weknora/production/.connection-check/"
        ]
      },
      "actions": {"baseBlob": {"delete": {"daysAfterModificationGreaterThan": 2}}}
    }
  }]
}
```

临时对象是否清理与访问链接是否到期是两件独立的事；访问链接到期不会删除 Blob。

## 13. 实施与验证记录

实现包括配置加密和脱敏、账户密钥及显式托管身份认证、完整 FileService、存储实例与资源目录接入、分阶段连接测试、前端条件表单及本地连接串解析。复制采用有界缓冲的流式独立副本，上传完成后才返回，不依赖 SAS 或异步复制状态。

实际对象路径遵循接口可用的归属信息：原文为 `{prefix}/tenants/{tenantID}/knowledge/{knowledgeID}/{uuid}.{ext}`，普通字节附件为 `{prefix}/tenants/{tenantID}/assets/{uuid}.{ext}`，临时字节对象为 `temporary` 目录。图片与页图的知识库、文档和页面关联继续保存在应用资源绑定中，不依赖 Blob 文件名推断关系。

已补齐共享 Agent 附件解析及授权链接读取的存储执行租户：授权成功后按文件所有者读取，解析模型仍使用原有配置上下文。编辑测试接口也会校验物理位置，避免把已有凭据用于测试其他 Endpoint。

已通过：

- Go 存储、配置、仓储、Service、Handler、Router、Container、日志脱敏及路径相关专项回归；具体命令如下。
- Azure SDK 模拟测试包括原文 multipart 上传、9 MB 以上分块字节上传、内容一致性、独立复制、幂等删除、资源目录和私有授权链接、失败阶段与清理，以及短暂失败的重试次数上限。
- 20 项连接串、原子填充、访问路径及 Azure 凭据导出保护测试；相关前端回归首轮 90 项通过，新增导出用例与解析优先级调整后专项重新通过。
- 13 项国际化审计，包括五种语言的键一致性和消息语法检查。
- `git diff --check`。

```sh
go test -p 2 ./internal/types ./internal/application/repository \
  ./internal/application/service/file ./internal/application/service \
  ./internal/handler ./internal/middleware ./internal/router \
  ./internal/container ./internal/utils ./internal/storageallowlist ./internal/storageurl \
  -run '(Azure|Storage|Presigned|ServeFiles|ResourceGrant|BuildStorageConfig|SanitizeBody|ParseProvider)' -count=1

go test ./internal/application/service/file ./internal/handler -run TestAzure -count=1

cd frontend
npm test -- src/utils/azureConnectionString.test.ts
npm run check-i18n
npm run type-check
npm run build
```

`storageallowlist`、`storageurl` 的完整包测试另行通过；上面的专项名称过滤不包含它们的全部用例。首次全量 `types` 测试曾遇到工作区内并行 Azure AI Search 的旧数量断言，后续存储相关测试独立通过，未将其计为 Blob 功能失败。

前端最终类型检查通过。生产构建首轮在既有 Less 工作线程上超时，使用 Vite 的 `css.preprocessorMaxWorkers: 0` 关闭 CSS 并行预处理重试后通过，没有修改项目构建配置；仍有既有大资源包提示。成功的重试命令为：

```sh
# 在 frontend 目录执行，使用项目要求的 Node 24。
node --input-type=module -e 'import { build } from "vite"; await build({ css: { preprocessorMaxWorkers: 0 } });'
```

真实 Azure 的密钥、托管身份、RBAC、私有网络和浏览器交互尚未实测，保留为部署验收项。
