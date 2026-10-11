# 第一批：JWT 身份与任务隔离

后端作为 OAuth2 Resource Server 接受外部身份提供方签发的 RS256 JWT。没有实现注册、密码登录或 Token 签发；身份提供方由部署方选择。

## 配置

以下三项必须一起提供：

```powershell
$env:AUTH_ISSUER='https://your-identity-provider.example/realms/projectscope'
$env:AUTH_JWK_SET_URI='https://your-identity-provider.example/realms/projectscope/protocol/openid-connect/certs'
$env:AUTH_AUDIENCE='agent-backend'
```

这些地址是示例，替换为实际身份服务的签发方和 JWKS 地址。生产环境使用 HTTPS。JWT 必须有非空 sub、exp、匹配的 iss，以及包含 AUTH_AUDIENCE 的 aud；签名、有效期和 nbf 由 Spring Security 校验。内部身份还要求当前时间严格早于 exp。

三项全空时应用仍可启动，但所有 API 请求都需认证，所有 Bearer Token 均被拒绝；配置不完整则启动失败。没有匿名模式、默认账户或自签发后门。此阶段需部署方接入真实身份服务后才能供真实用户访问。

请求携带 `Authorization: Bearer <access_token>`。不接受 X-User-Id、URL Token、请求体 userId、Cookie 或 HTTP Basic 作为身份凭据；无有效身份返回 401。

Spring Security 采用无状态 Bearer 认证，因此关闭会话和 CSRF；若未来增加 Cookie 登录，需重新设计 CSRF 防护。异步/错误分派允许完成已认证请求；初始 HTTP 请求仍必须经过认证。

## 核心调用链

1. SecurityConfig 验证 JWT 的签名、iss、aud、有效期及必要声明。
2. TaskIdentity 从已验证的 iss/sub 生成 SHA-256 十六进制 userId，长度前缀消除连接歧义。相同 issuer/sub 始终对应同一用户，改签发方会改变用户标识。
3. TaskService.submit 只把可信 userId 传给 createOwned。请求中的同名字段不参与归属。
4. JdbcTaskStore.findOwned 使用 `WHERE task_id=? AND user_id=?`；未找到和不归属统一返回 404。
5. 步骤、工具调用通过所属 Task 校验后，在同一只读事务内读取；不能跳过父任务归属检查。
6. SSE 捕获不可变 TaskIdentity，按任务、用户和身份有效期分组。每轮查询重新校验归属，轮询及发送前检查 exp，不依赖请求线程的 SecurityContext。过期后关闭流，客户端刷新 Token 后重新连接。
7. Worker 取得租约后从数据库加载任务 owner，放入 ToolExecutionContext；后台执行不依赖发起 HTTP 请求的线程身份或模型参数。现有计算/工具发现工具无用户数据访问；未来用户数据工具必须通过上下文与授权接口访问数据。

JWT 为离线校验，本批尚无账户禁用表、Token 撤销列表或在线 introspection，因此身份提供方撤销未过期 JWT 不会立即终止访问。建议签发短期 access token；即时撤销是后续能力。访问 Token 到期不取消已经接受的后台任务。

## 数据迁移

Flyway V3 给 agent_task 增加可空 user_id 和 `(user_id,created_at,task_id)` 索引。NULL 表示未认领历史/内部任务，用户接口不会返回这些记录。内部调度与恢复继续处理原任务，保留现有行为；这不等于将历史任务授予某个用户。

此批不猜测旧记录所有者。需要认领时，管理员应先停止相关执行、备份，逐条核对 task_id 和经过验证的 issuer/sub 对应 userId，再通过参数化更新认领指定 NULL 记录。已归属任务不支持在线转移归属。不要批量把所有旧任务绑定给首个登录用户。

TaskStore.create / find 是内部执行与运维接口；HTTP 只使用 createOwned / findOwned。同步 AgentRunner.run 创建的内部任务继续无归属，不能通过用户 API 查询。此兼容路径不供外部调用。

## SSE 客户端

浏览器原生 EventSource 不支持自定义 Authorization 头。当前协议请使用 fetch 流式读取或能设置该头的 SSE 客户端；必须解析完整 SSE 帧，不能假设每次网络读取正好对应一个事件。避免把 Token 放在 URL。重连需重新携带有效 Token；当前仍是快照协议，没有 Last-Event-ID 回放。

## 验证

执行 `./mvnw.cmd test`。SecurityIsolationTests 使用临时 RSA 密钥、本地 JWKS HTTP 服务和真实安全过滤链，验证匿名、伪造签名、错误 issuer/audience、缺失声明、过期身份、伪造 userId 和双用户详情/步骤/SSE 隔离。TaskEventTests 验证请求线程退出后继续使用捕获身份以及过期关闭。AgentRuntimeTests 验证后台执行取持久化 owner。

MySqlMigrationTests 覆盖 V3、归属查询和历史 NULL 隔离；需要 Docker，不可用时明确跳过，不能视为完成真实 MySQL 验证。

框架依据：[Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)。
