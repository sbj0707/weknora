# 评估方案 API

本接口只管理质量评估的方案配置，不调用模型、不生成 QA、不创建评估任务。不包含方案状态、版本或历史记录。

## 身份与权限

- 地址前缀：`/api/v1/evaluation-plans`。
- 使用项目现有 Bearer 用户会话；当前空间由认证中间件确定，支持已有的 `X-Tenant-ID` 空间切换约定。
- 暂不开放 API Key，包括 full-access、run_evaluations 能力 Key 和指定知识库范围的 Key。
- 当前空间成员可以查看全部方案；Contributor 及以上可创建和复制；创建人、当前空间 Admin/Owner 或已获跨空间访问授权的超级用户可编辑、删除。
- 创建人即使随后降为 Viewer，仍保留本人方案的管理权限，与已有资源的创建人授权原则一致。
- 跨空间 GUID 返回 404，所有数据库读写均带当前空间条件。
- MySQL 不是当前应用支持的主库。本次提供 PostgreSQL 和 SQLite 迁移。

## 接口

| 方法 | 地址 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/evaluation-plans?keyword=&page=1&page_size=12` | 列表，支持 GUID 或名称的字面子串搜索，不区分大小写 |
| POST | `/api/v1/evaluation-plans` | 创建 |
| GET | `/api/v1/evaluation-plans/{id}` | 完整详情 |
| PUT | `/api/v1/evaluation-plans/{id}` | 替换全部可编辑配置 |
| DELETE | `/api/v1/evaluation-plans/{id}` | 软删除 |
| POST | `/api/v1/evaluation-plans/{id}/copy` | 创建副本，返回新方案；无请求体 |

列表 `page` 默认 1，`page_size` 默认 12，范围 1～100，`page` 上限 1,000,000，`keyword` 最多 100 个 Unicode 字符。排序为 `updated_at DESC, id DESC`。响应：

```json
{
  "success": true,
  "data": { "items": [], "total": 0, "page": 1, "page_size": 12 }
}
```

创建、编辑、详情、复制均返回 `{"success": true, "data": <完整方案>}`；删除返回 `{"success": true, "data": null}`。错误沿用项目的统一错误响应；参数无效为 400，缺少用户身份为 401，权限不足为 403，不存在或已删除为 404。

## 创建与编辑请求

```json
{
  "name": "年报问答评估",
  "description": "检查年报回答中的事实与数字",
  "qa_generation_prompt": "仅根据 {{content}} 生成问答，保留原文证据和来源信息。",
  "answer_comparison_prompt": "按照 {{scoring_dimensions}} 比较 {{expected_answer}} 与 {{actual_answer}}。",
  "qa_generation_model_id": "可用的文本生成模型 ID",
  "evaluation_model_id": "可用的文本生成模型 ID",
  "scoring_dimensions": [
    { "id": "correctness", "name": "答案正确性", "description": "事实与原文一致", "weight": 100 }
  ],
  "pass_score": 80,
  "qa_generation_rules": {
    "questions_per_chunk": 3,
    "question_types": ["fact", "numeric", "explanation", "synthesis"],
    "difficulty": "medium",
    "language": "zh-CN"
  }
}
```

- 除 `description` 外，以上顶层字段均必需；未知字段及 `id`、`tenant_id`、`created_by`、时间等不可编辑字段会被拒绝。单次请求体最多 1 MiB。
- 名称去掉首尾空白后为 1～100 字符，描述最多 1,000 字符；两类提示词非空且各自最多 50,000 字符，按原文保存，不替换占位符。
- 两个模型分别校验：当前空间模型或平台内置模型、类型为 `KnowledgeQA`、状态为 `active`。详情不依赖模型仍存在，模型失效不会隐藏方案；创建、编辑、复制必须使用有效模型。
- 评分维度 1～20 项，标识唯一，以英文字母开头，后续允许字母、数字、下划线和连字符，最多 64 字符；名称必填且最多 100 字符，说明最多 2,000 字符。
- 每项权重为 1～100 的整数，总和必须为 100。通过分数范围 0～100，允许 0。
- 每个片段生成题数为 1～20 的整数；题型至少一个，不能重复，仅支持 `fact`、`numeric`、`explanation`、`synthesis`。
- 难度支持 `easy`、`medium`、`hard`；语言支持 `zh-CN`、`en-US`、`ja-JP`、`ko-KR`、`ru-RU`。
- 提示词变量约定仅作为后续执行模块的输入协议，本接口不检查变量是否齐全，也不执行模板。

## 详情额外字段

详情返回全部可编辑字段，以及：

- `id`：服务端生成的 UUID/GUID。
- `created_by`、`creator_name`：创建人的用户 ID 与展示名称；用户不存在时展示名称为空。
- `created_at`、`updated_at`：服务端维护的时间。
- `can_manage`：服务端计算的当前用户编辑、删除权限；列表同样返回该字段。

复制生成新 GUID，以「原名称（副本）」命名，必要时截短原名称保证长度限制，创建人改为当前用户。复制后返回的是已持久化的记录，取消编辑副本不会撤销复制操作。

删除采用软删除，已删除记录不出现在列表中，详情、编辑、复制和再次删除返回 404。审计事件为 `evaluation_plan.created`、`evaluation_plan.updated`、`evaluation_plan.deleted`、`evaluation_plan.copied`，仅记录资源 ID 和操作者，不记录提示词正文。
