# Chunk 问题索引迁移检查点

PUT /api/v1/chunks/by-id/:id/questions 与 DELETE 同路径已经接入 Java 原生控制器；保留 in_progress。同步 regenerate 已接入并完成专项对照，本检查点不代表全部 Go 功能迁移完成。

## 已验证行为

- 实际 Go handler、middleware、service、SQLite 物理 vec/FTS 导出 130 场景：增删、Go JSON binder、角色/共享/API scope、typed metadata、重复/长 ID、模型/索引失败、外部调用期间源数据和 owner 变更。
- Java 新鲜隔离运行 337 tests / 4 classes，failures/errors/skips 全 0，actual exit 0：问题索引 130 + production JWT/API/上传链路 5 + chunk edit 196 + edit Spring 6。日志 `/tmp/weknora-java-chunk-question-safe.log`；不是把历史专项累加。
- PostgreSQL 115 Go migrations、Hikari1、实际 Go/Java 130 场景，actual exit 0、独立 XML 130/0/0/0。日志 `/tmp/weknora-java-postgres-chunk-question-safe-second.log`；artifact `weknora-postgres-chunk-edit-7wxebxpe`。核对有效 upsert 为 HTTP200、确实发出 1 次 embedding 请求，避免把双方同样报错当成功功能。
- 比较完整响应、当前 chunks、不可变 revisions、逐 source/type 的 float32 原始位、物理索引、真实 provider HTTP、任务及文档保留状态。新 UUID 先验证格式再规范化；请求 input 只消除 Go map 的顺序差异，内容和逐 source 向量仍严格比较。
- 上传后完整 UUID chunk + UUID question 的 source_id 超过 64 bytes，断言独立计算 Go SHA256 前 12 bytes 标识；删除前后验证的是同一真实 alias。

## 对齐细节与修复

问题增删不产生正文 revision、不调 summary refresh、不改变正文 index_status。upsert 元数据先提交，再同步重建正文及全部问题索引；索引失败返回400，已提交问题保留，再次 upsert 可以修复。删除只移除第一个匹配 ID，物理 alias 删除失败与 Go 一样 best effort，然后保存元数据。

PostgreSQL JSONB 会重排键和空白。此前 Go/Java 的索引绑定校验比较原始字符串，导致实际有效 upsert 也误报 revision conflict；已改为先比原始值、再比 JSON 内容，Go 使用 UseNumber 避免整数精度损失。原始错误运行保留在 `weknora-postgres-chunk-edit-o6_yi4_y`，修复后真实成功路径和并发拒绝路径均比较。

## 未完成验收

公开问题增删已纳入1194文件冻结源码（含config/migrations/proto）的7538项/154类完整构建和同jar36种最终模式验收。后续同步regenerate已纳入7712项/159类新冻结构建与同jar40种最终流程/配置，其中重新生成两种拓扑均actual0；更广泛并发/外部存储仍待完成。

## 同步问题重新生成专项

- 实际 Go/SQLite 81 场景和 Java 新鲜组合421项/5类、写权限38项/1类均 actual exit 0；组合含重新生成81、Spring9、问题增删130、编辑196、任务拓扑5。
- 实际 Go/PostgreSQL 81 场景 actual exit 0，独立XML81/0/0/0；日志 `/tmp/weknora-java-postgres-chunk-regenerate-safe81-first.log`，artifact `weknora-postgres-chunk-edit-c5_ychhc`。
- 覆盖默认/截断问题数、配置模板、同文档前后文、忽略请求体、生成新UUID、重建真实body/question索引，以及chat/embedding期间正文、元数据、owner、模型/配置变更。两端均新增源绑定与窄字段SQL提交防护；保留修正前实际Go输出，避免把原实现并发覆盖当成期望。
- 元数据先提交后重建索引的Go失败副作用保留；不产生正文revision/summary任务。生成模型错误与有效200请求均严格检查。
