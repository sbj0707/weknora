# OIDC 原生迁移范围

Java 已注册四个精确公开 GET：`/api/v1/auth/oidc/config`、`/url`、`/start`、`/callback`。AuthFilter 与 GoCompatibilityBridge 使用相同方法/路径边界；其他方法、子路径和未知 OIDC 路径不被当作这四个公开接口。

配置来自 Go YAML（`JAVA_GO_CONFIG` 或 `weknora.go-config-path`）与 Go OIDC 环境变量，包含 Viper 弱类型、显式值与默认值规则。Provider client 保留 discovery、授权 URL、state HMAC、cookie、code exchange、userinfo、自定义字段映射及 RSA/JWKS 校验。回调与 Go 一样返回 `/#oidc_result` 或 `/#oidc_error`。新账号的 personal workspace、存储快照、membership、preferences、access/refresh token 使用生产表；保留 Go 独立写入和部分失败清理顺序。

四个 ignored Go generators 引用原 Go config、handler、service、repo 和安全 HTTP，未把重写的 Go 模拟器当作 oracle。Root 已实际重放 state 94、config 52、生产 SQLite HTTP 149、transport 18；HTTP 样本包含 151 handler calls / 232 provider requests，transport 42 requests。另有 Spring 生命周期/方法边界与实际 PostgreSQL PGobject 适配两项，合计五类 315 项 JUnit。PGobject 测试不代表真实 PostgreSQL 数据库验证。

重放：`python3 backend-java/scripts/generate-oidc-oracles.py --output-dir /tmp/weknora-oidc-replay`。State 与 HTTP 的 clock 是 Go 当前时间；重新生成后使用 Java 测试验证，不能要求跨时间原始 JSON 相同。配置样本的 root 重放与原样本语义一致。进程验证使用 `scripts/oidc_smoke.py <jar>`，检查真实 Java HTTP、provider HTTP、生产 SQLite、新旧账号、token、普通回放拒绝及手工携带有效 cookie/state 的 Go 行为，并检查 bridge 实际归属。

已知限制保留：同步 Servlet 客户端断开尚未桥接到 CallContext/JDBC 取消；服务层 provider cancellation/deadline 已对照。代理使用现有原生 ModelEgressPolicy 能力。HTTP 引擎异常后缀和 state typed-unmarshal 私有后缀按阶段/类别比较，不宣称任意 Go/JVM 引擎字节相等；公开状态、cookie、redirect 和测试内 DTO/持久化/业务错误均作严格检查。捕获仍有效的 state 与 cookie 后手工重新发送，Go 仍接受，Java 没有另加 server nonce 消费机制。全认证 guard、shared tenant storage validation、PG 与完整主后端迁移仍待完成。
