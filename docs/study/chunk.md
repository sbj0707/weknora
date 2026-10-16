当前项目里，一个 **chunk 是“内容＋归属＋位置＋关联关系＋版本状态”的记录**。核心定义在 [chunk.go](/Users/barryshen/source_code/WeKnora/internal/types/chunk.go:121)。

主要字段如下：

| 类别 | 字段 | 作用 |
|---|---|---|
| 标识与归属 | `id`、`knowledge_id`、`knowledge_base_id`、`tenant_id` | 片段、文档、知识库及租户 ID |
| 内容 | `content` | 当前正文，可以编辑 |
| 原始内容 | `source_content` | 保留解析时的原始正文，详情接口单独返回 |
| 标题上下文 | `context_header` | 所属章节的标题路径，构建索引时可加在正文前 |
| 类型与顺序 | `chunk_type`、`chunk_index` | 片段类型及其在文档中的顺序 |
| 文本位置 | `start_at`、`end_at` | 解析文本中的起止偏移，不能直接当作页码 |
| 原文定位 | `source_locators` | PDF 页码、区域，Excel 工作表和行号等 |
| 片段关系 | `pre_chunk_id`、`next_chunk_id`、`parent_chunk_id` | 前后片段及父片段 |
| 扩展信息 | `metadata`、`image_info` | FAQ、生成问题、图片地址、OCR、图片描述等 |
| 版本与状态 | `content_revision`、`index_status`、`is_enabled` | 内容版本、索引同步状态、是否参与检索 |
| 审计信息 | `last_editor_id`、`created_at`、`updated_at` | 编辑人及时间 |

例如，一个 PDF 文本片段的简化结构：

```json
{
  "id": "chunk-uuid",
  "knowledge_id": "document-uuid",
  "knowledge_base_id": "kb-uuid",
  "chunk_type": "text",
  "chunk_index": 12,
  "content": "索夹在主缆上定位后，应紧固螺栓。",
  "parent_chunk_id": "parent-chunk-uuid",
  "source_locators": [
    {
      "type": "pdf",
      "mapping": "exact",
      "page": 57,
      "bbox": [0.1, 0.2, 0.9, 0.35],
      "quote": "索夹在主缆上定位后，应紧固螺栓。"
    }
  ],
  "content_revision": 0,
  "index_status": "ready",
  "is_enabled": true
}
```

这里 `page: 57` 表示 PDF 文件的第 57 页，页码从 1 开始；`bbox` 是页面中的归一化区域坐标。**一个 chunk 可以有多个定位信息，例如正文跨越两页；旧数据也可能没有定位信息。**

`chunk_type` 常见有：

| 类型 | 含义 |
|---|---|
| `text` | 普通正文，包括父子分块中的子片段 |
| `parent_text` | 提供较完整上下文的父片段，本身不参与向量索引 |
| `image_ocr` / `image_caption` | 图片识别文字／图片描述 |
| `summary` | 文档摘要 |
| `faq` | FAQ 条目 |
| `table_summary` / `table_column` | 表格摘要／列描述 |
| `wiki_page` | 接入检索的 Wiki 页面内容 |

**向量、关键词、图谱等索引详情，是详情接口额外查询并组装的。** 你现在的 chunk 独立视图使用 [ChunkDetail](/Users/barryshen/source_code/WeKnora/internal/types/chunk_detail.go:6)，结构大致为：

```text
ChunkDetail
├── chunk              片段本身
├── source_content     原始正文
├── context_header     标题上下文
├── children           子片段
└── indexes
    ├── vector         向量记录、维度、向量值
    ├── keywords       关键词／全文检索记录
    ├── graph          关联实体与实体间关系
    ├── wiki           引用该片段或所属文档的 Wiki 页面
    └── questions      生成问题及其索引状态
```