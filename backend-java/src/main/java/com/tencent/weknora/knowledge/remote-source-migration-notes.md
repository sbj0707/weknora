# URL / file_url 文档处理增量

2026-10-16：原生 document worker 已接入网页 URL 和 file_url。全部 Go 功能迁移仍未完成；本增量没有新增公开 URL 导入接口，没有升级路由验收状态，也没有重启服务。

后续批worker增量已将这里的远端来源代码纳入8,139项冻结完整构建，并通过同jar的single/go单文档与共享批量重解析；详见[后续worker验收](reparse-worker-migration-notes.md)。该同jar流程使用本地文件；这里的URL/file_url特定生产链仍以本文件的Spring证据为准，不能将本地文件jar验收描述成完整远端URL验收。

## 行为与实现

file_url 使用逐跳校验、DNS 固定的 HTTP GET，60 秒超时、10 次重定向限制。文件名按任务参数、Content-Disposition、原始 URL 路径的顺序解析，保留扩展名大小写；响应头和流式读取分别执行 Go 的 10MB 上限。下载成功后校验支持的扩展名，通过 KB 原 owner 的 FileStore 保存临时资源，再读取、解析和构建索引。与 Go 一样，不把临时文件路径写回文档 file_path。下载/保存错误可重试，只有最后一次才记录文档失败；不支持的类型及安全拒绝直接记录失败并结束任务。

网页 URL 按 `url` 解析规则选择引擎，默认使用远端 reader，即使任务同时携带 txt 等简单格式。ReadRequest 保留 URL、标题、request_id 和 parser overrides，文件名、类型和内容留空。只有标题为空或等于源 URL 时才采用解析器的非空 title；索引内容使用更新后的标题。URL 不附加原始文件结构坐标，但保留解析器提供的 source blocks。未配置 reader 时按实际 Go 标记失败并结束任务。

文件名/类型发现和网页标题更新按 Go 的 checkpoint 进行 best-effort 写入。Snapshot 独立保存原始来源字段，避免 worker 自己补齐元数据后被当作来源替换；后续文档状态发布保留尚未写入的发现值。下载期间检查取消、来源和租约，挂起的响应可被关闭。Java 原有租约/来源保护比 Go 的 file_path-only guard 更严格，不能将这些原生保护测试称为完整 Go 并发语义对照。

## 验证

- 31 个实际 Go 下载/文件名观察值：真实 HTTP、文件名优先级、大小写、filename*、引号/分号、URL 解码/尾斜线/dot segments、重定向保留原路径、HTTP 状态、10MB 边界及流式超限。失败场景比较错误文本；Go helper 在返回错误前修改的局部 filename/type 指针不属于 Java 返回值契约。
- 13 个实际 Go ProcessDocument 场景：URL 标题的空值/默认/自定义保留、parser 错误、transport 错误、reader 缺失；file_url 元数据发现、已有值、远端解析、不支持类型、HTTP/保存失败。比较所导出的 SQL 字段、chunks、reader 请求、存储读写与 postprocess 类型。Go 使用实际服务和 SQL，reader/FileStore/队列为记录边界的适配器；该 corpus 不验证真实 Go 模型或向量引擎。
- 5 个原生保护测试：挂起下载取消、重定向目标 SSRF、下载期间来源变更/任务取消/租约失效。
- 新增 2 个完整 Spring 重解析流程：JWT、共享 KB、原 owner、Hikari1、实际 HTTP 下载/DocReader/非恒定 embedding、临时资源、物理 vec/FTS 和 postprocess。测试通过公开 process_config 关闭摘要，仍断言最终 completed 和实际索引内容。

最终组合 **265 项 / 12 类，failures/errors/skips 全 0，Maven actual exit0**，同时覆盖原文档处理、手动处理、图片 fanout、重解析准入、上传、角色权限和 span 处理链回归。实际 Go 两个 oracle 测试重新执行 actual exit0，工作区 corpus 与最终输出逐字节相同。输入/报告/日志哈希见[本增量 checkpoint](../../../../../../../migration/remote-source-checkpoint-2026-10-16.json)。

原始失败保留：路径 basename 两处差异、reader 缺失 ACK 差异、测试 cleanup 编译错误，以及新测试继承默认摘要配置导致 finalizing 的失败。分别修正实现或显式测试配置，未放宽最终状态、物理索引或错误断言。

## 尚未完成

公开 URL/临时文档导入接口、Go 批量重解析 worker 的完整差分、所有 parser/对象存储/绑定索引/模型/代理、底层网络错误和 header/deadline 精确语义、非法 URL/userinfo、远端 reader 调用期间取消、所有 metadata 保存失败 checkpoint、完整 PostgreSQL URL 处理与 trace 差分、全部并发/重启/切换仍需完成。文档发布事务及配额与 Go 的已有差异未因本增量解决。

本次未执行新的冻结完整 clean verify，也未打包/运行同 jar single/go 流程。历史 7,914 项、旧 jar 和先前重解析 PostgreSQL100项不能替代当前增量的完整验收。Redis/Asynq、Lite 及其他模块缺口保持在原目标中。
