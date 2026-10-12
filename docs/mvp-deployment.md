# Personal Agent MVP：本地验收与单实例部署

本版本面向少量用户、单实例、只读工具。真实模型 / Brave / 身份服务联调按用户决定后置；本地验收使用真实 MySQL 和隔离的 HTTP 测试服务，不将模拟结果描述成真实资料研究。

2026-10-12 本地记录：`mvnw.cmd package -q` 成功，148 项测试全部通过、无跳过；jar 已检查不包含本地敏感配置。Compose 配置校验通过，但本机 Docker Hub 连接超时导致镜像构建未完成；修复网络后需重新执行镜像构建，不应视为已部署。

## 1. 运行前提

- Java 21；仓库自带 Maven Wrapper。
- MySQL 8.4；自动化测试通过 Docker / Testcontainers 创建独立数据库，不使用开发或生产数据库。
- 对外试用前配置可信 JWT 身份服务。页面是公开静态壳，私人 API 仍需要有效 Bearer JWT；没有认证配置时拒绝全部令牌，不提供匿名后门。
- 模型须兼容现有 `/chat/completions` 协议和 function calling。长期记忆提取使用文本 JSON 校验，不依赖供应商专属 Structured Output 参数。

## 2. 服务端配置

凭据仅放服务器环境或受保护的部署配置，不提交 Git，不输入模型 Prompt，不在聊天中发送。不运行会打印完整环境变量或展开后 Compose 配置的命令。

| 环境变量 | 用途 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 直接启动 jar 时的 MySQL 连接；URL 使用 UTC 会话时间 |
| `MYSQL_ROOT_PASSWORD` | Compose 初始化 MySQL 使用，与应用密码不同 |
| `AUTH_ISSUER` / `AUTH_JWK_SET_URI` / `AUTH_AUDIENCE` | 必须成组设置；JWT 要有 sub、exp 和匹配的 iss/aud |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | 模型连接；base URL 不含 `/chat/completions` |
| `BRAVE_SEARCH_ENABLED` | 默认 false；真实研究设为 true |
| `BRAVE_SEARCH_API_KEY` | Brave 服务端订阅密钥，不能使用用户私有账户凭据代替 |
| `AGENT_MAX_STEPS` | 默认 10 步 |
| `AGENT_RUN_TIMEOUT_SECONDS` | 默认 300 秒，从领取开始计时 |
| `AGENT_MAX_RUNNING_PER_USER` | 默认 2；超出后排队，不重复领取 |

环境未配置 Brave 时，工具返回 `SEARCH_NOT_CONFIGURED`，不伪造来源。HTTP 限流、异常响应不自动重试。Brave 查询使用固定主机、禁止重定向，只返回最多五条标题/URL/摘要，不抓取任意网页。

`application-local.properties` 已从资源复制与 jar 打包中排除，Docker 构建上下文也排除它。开发者如需继续使用自己的本地配置，显式提供 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:./src/main/resources/application-local.properties`；不要将这个路径用于镜像内部署。

## 3. 自动验收

在 PowerShell 中，从仓库根目录执行：

```powershell
docker info
.\mvnw.cmd test
node --check src/main/resources/static/app.js
.\mvnw.cmd package -DskipTests
```

Docker 必须可用。`disabledWithoutDocker` 会让 MySQL 用例跳过，退出码为 0 **不等于完成验收**；检查 `target/surefire-reports/TEST-*.xml` 的 failures、errors、skipped。

重点测试：

- `PersonalMvpMySqlTests`：会话幂等、记忆确认/纠正/删除/关闭、消息与 Run 原子创建、结果发布去重、时限/并发/取消、每周触发。
- `PersonalAgentEndToEndTests`：真实 MySQL、真实 Scheduler、HTTP 模型协议与 Brave 适配器串联；跨会话记忆 → 研究工具调用 → Run/Step/SSE → 会话结果 → 周期任务及跨用户拒绝。
- `PersonalAgentRestartTests`：关闭并重新启动应用上下文，复用同一隔离 MySQL；已提交 PENDING 研究继续执行，孤儿 Attempt 安全失败，结果发布一次。不是双主机或强杀进程故障矩阵。
- `MySqlMigrationTests` / `RunAttemptUpgradeTests`：迁移、回滚、领取竞争与旧数据升级。
- `SecurityIsolationTests`：真实 JWT 签名校验，以及公开静态壳不开放私人数据 API。
- `BraveSearchTests` / `RunDeadlineTests`：本地 HTTP 合同与全请求时限；不需要真实密钥。

## 4. 启动

直接运行已打包的 jar，确保上述环境变量已在当前服务账户配置：

```powershell
java -jar target/agent-backend-0.0.1-SNAPSHOT.jar
```

或使用仓库的单实例 Compose：

```powershell
docker compose up -d --build
docker compose ps
```

Compose 将应用绑定到 `127.0.0.1:8080`，不开放 MySQL 端口。远程使用需自行配置 TLS 反向代理，并关闭 SSE 缓冲；身份服务 JWKS 地址必须能被应用容器访问，容器中的 localhost 不是宿主机。

打开 `http://localhost:8080/`，在本地页面填入身份服务签发的访问 JWT。JWT 只保留在页面内存，刷新后需重新连接；这是少量用户试用入口，不包含完整 OIDC 登录/刷新流程。API Key 不填在页面里。

健康检查先确认静态页面返回 200，未认证访问 `/conversations` 返回 401；这不代表数据库、模型或 Brave 都可用。再进行下面的业务检查。

## 5. 手工签收场景

1. 用户 A 新建职业规划会话，说明 Java 经历与偏好；候选记忆出现后检查准确性并确认。
2. 新建会话，询问职业方向；确认回答实际使用已确认背景。修改记忆，再次提问；检查旧版本不再被当作当前目标。
3. 删除主题记忆；再次刷新或重发同一消息不能恢复它。删除记忆不删除原始聊天，关闭记忆会停止提取和检索。
4. 发起资料研究，确认出现 Run ID；查看事件、来源、失败原因及取消。断线重连使用 Last-Event-ID，不把凭据放在 URL。
5. 用户 B 不能读取 A 的会话、记忆、Run 或周期任务；不存在与非本人资源均返回 404。
6. 授权每周研究，首次时间按北京时间填写。暂停阻止后续触发；恢复最多合并一次逾期触发。已有 Run 不被暂停操作取消。
7. 重启应用后，PENDING 任务保留；已失去租约的 RUNNING 任务最终 FAILED，而不是自动从断点续跑。失败可查，用户可用新请求显式重做。

真实密钥联调时，另行检查资料准确性、记忆相关性、引用与问题的相关性，以及搜索词没有不必要的个人信息。自动化 JSON/schema 和 Run=COMPLETED 不能替代这些人工检查。

## 6. 备份与恢复

升级前备份数据库并保护备份文件权限；对话、记忆和历史 Run 输入均可能包含个人信息。`backups/` 已被 Git 忽略，不代表加密或安全保管。

```powershell
New-Item -ItemType Directory -Force backups
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u agent_backend --single-transaction --no-tablespaces --set-gtid-purged=OFF agent_backend > /tmp/agent-backend-backup.sql'
docker compose cp mysql:/tmp/agent-backend-backup.sql ./backups/agent-backend.sql
```

先在隔离项目恢复，不直接覆盖现有数据库：

```powershell
docker compose -p projectscope-restore up -d mysql
docker compose -p projectscope-restore cp ./backups/agent-backend.sql mysql:/tmp/agent-backend-restore.sql
docker compose -p projectscope-restore exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u agent_backend agent_backend < /tmp/agent-backend-restore.sql'
```

等待隔离 MySQL healthy 后再导入；核对记录数量和迁移版本，再进行应用只读抽查。该过程是操作手册，除非验收记录明确说明，否则不代表已经在你的真实数据库演练。不要执行 `docker compose down -v` 删除正式数据卷。临时导出文件也含个人数据，完成后按运维流程清理并验证备份可恢复。

## 7. 明确限制

- 不支持 Checkpoint 自动续跑、完整审批、精细 Token 预算、任意外部写入、自进化或多机容量承诺。
- 记忆检索是主题匹配加近期有效记录，最多注入八条；只使用确认后的记忆。提取失败不自动重试；凭据模式过滤是保守补充，不是通用 DLP。
- 删除记忆会清除记忆表中的该主题版本，但不会追溯删除原聊天或历史 Run 输入快照；新检索不再使用被删条目。
- 总时限终止本地等待并阻止晚写回，不保证供应商已经撤销远程请求。已发出而未确认的工具调用保留 UNKNOWN。
- 浏览器视觉/真实交互验收和真实供应商质量验收需单独记录，不能由本地测试代替。

实现与验收记录见 [mvp-progress.md](mvp-progress.md) 和 [execution-lifecycle-v0.6.md](execution-lifecycle-v0.6.md)。
