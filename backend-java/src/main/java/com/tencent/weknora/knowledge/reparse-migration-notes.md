# 重解析迁移进度

2026-10-16：单个/批量重解析已完成下面的有界差分和生产链验收，**完整迁移仍未完成**。后续URL/file_url worker见[远端来源增量](remote-source-migration-notes.md)。最新直接批worker的SQLite/PG各67场景、8,139项冻结完整构建和同jar两拓扑流程见[worker验收](reparse-worker-migration-notes.md)；清单中的两个接口及 `knowledge:list_reparse` 已推进至 `in_progress`，0新增verified。以下保留先前的125项/100项证据与当时范围，不能只根据入队成功将整个处理链标记完成。

## 本次实现与修正

- 单个入口保留权限先于可选JSON绑定、process overrides持久化、非手动同步清理、旧任务取消、新attempt、Wiki pending清理、状态/计数器独立重置和入队的顺序。清理或入队失败保留Go已有的部分副作用，不增加整段事务回滚。
- 手动文档SQL NULL metadata拒绝，JSON字面量null则按Go空struct处理；清理延迟到manual worker。普通文件任务保留request_id、原始扩展名、语言、attempt和任务选项。表格任务优先存储file_type，只有空值才退回文件名。
- 批量入口执行整批归属/moving校验，使用low队列、3次重试、1小时，并保留原initiator。原生worker执行前重新校验整批；单项失败后继续提交其他项，部分失败为不可重试。
- 生产NativeRoleGuard补批量入口Contributor拒绝审计。GoUploadJson修正匿名struct字段类型错误的路径文本；模型清理错误及file_path的SQL NULL写回与Go对照修正。
- Wiki清理使用Go的task_type/scope/KB/document/op元组；同一元组中即使残留错误tenant字段也按实际Go行为清除。保留retract、其他KB、其他任务和其他文档的待办。

## 实际验证

最终SQLite组合125项、5类，failures/errors/skips均0，Maven actual exit0：

- 100个实际Go HTTP/服务/SQL/Asynq场景回放：完整响应与错误、文档状态、NULL、chunk软删除、实际vec/FTS元数据清理、配额、attempt、任务顺序/payload/options、原actor审计、旧任务可执行性和Wiki pending。
- 7个完整Spring生产链：真实JWT/API key、共享KB原owner执行、实际模型HTTP、物理vec重建/源文件保留、手动清理延迟、批量全批预检/部分失败、失租约拒绝以及入队失败保留checkpoint，Hikari pool=1。
- 上传与生产角色guard回归18项。

另用runner自建的PostgreSQL/ParadeDB容器，应用全部115个Go迁移及Java task schema，重新导出100个真实Go场景，Java PostgreSQL差分100项、1类、全部0，actual exit0，Hikari pool=1。没有接触用户数据库。Go和Java分别使用Asynq/SQL队列，旧任务比较的是能否继续执行，不声称两种队列的物理记录相同。

Go HTTP oracle注入可信身份后调用真实权限middleware，并不验证Go JWT/API key完整认证；Spring7项另行验证Java完整认证链。图谱禁用、本地/默认索引场景不能证明Neo4j、多云或绑定引擎已完整验收。SQL断言覆盖导出列，动态更新时间与随机任务UUID分别验证格式后归一化。

证据与输入哈希：[本次checkpoint](../../../../../../../migration/reparse-admission-checkpoint-2026-10-16.json)。最终SQLite日志 `/tmp/weknora-java-reparse-queue-20261016-fifth.log`；PG runner日志 `/tmp/weknora-java-postgres-reparse-20261016-first.log`。早期48项差分失败、手动worker断言字段错误及SQL队列canceled拼写错误的报告均保留，未通过放宽业务断言处理失败。计数器失败从第二版Go oracle起使用真实SQL触发器；最终版本还使用真实Go Asynq inspector和pending repository。

## 仍需完成

URL/file_url的后续worker专项不代表公开导入、全部网络/存储/解析器/异常边界已验收。直接Go批worker的畸形/legacy payload与失败/trace/initiator差分、部署语言、全部并发归属/版本/租约/取消窗口、全部解析器/模型/云/绑定索引/Wiki生命周期均需继续完成。当前尚未执行包含本增量的冻结完整clean verify或同jar single/go流程；历史7,914项及旧jar不覆盖本增量。SQL两种布局不代表已消费Go Redis/Asynq任务，也不代表Lite或生产切换已对齐。
