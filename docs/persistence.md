# MySQL 持久化与任务接口：阶段 1–5

已提供数据源、Flyway 自动迁移、三张表及对应的只读 Java record/状态枚举。
阶段 2 已将 Runner 接入 MySQL。阶段 3 将 HTTP 接口拆为提交、详情查询和步骤查询。
阶段 4 使用 MySQL 保存待执行任务，按本机空闲容量轮询调度；数据库同时记录工具轮次、
失败轮次和最终回答轮次。阶段 5 增加租约、心跳和运行中断收尾。

## 启动配置

使用 MySQL 8.4 或更高兼容版本。先创建空数据库 `agent_backend`（utf8mb4）及应用账号，
为账号授予该数据库的建表、修改表、创建索引、外键和读写权限。
Flyway 创建表，不负责创建 MySQL 实例、数据库或账号。

在 PowerShell 设置以下环境变量后启动：

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/agent_backend?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME='agent_backend'
$env:DB_PASSWORD='<数据库密码>'
.\mvnw.cmd spring-boot:run
```

也可以复制 `src/main/resources/application-local.example.properties` 为已被 Git 忽略的
`application-local.properties`，填入真实配置并启用 `local` profile。
连接池上限通过 `DB_POOL_SIZE` 配置，默认 10。
正常启动现在需要可连接的 MySQL；连接或迁移失败时启动失败。

Spring Boot 4 使用 `spring-boot-starter-flyway` 接入自动迁移，MySQL 支持由 `flyway-mysql` 提供，
版本均由 Spring Boot 管理：[官方说明](https://docs.spring.io/spring-boot/how-to/data-initialization.html)。
首次启动执行 `V1__create_task_tables.sql` 并生成 `flyway_schema_history`。
后续迁移新增 `V2__...sql` 等文件，不修改已应用的 V1。禁用 Flyway clean，不自动 baseline 已有表。

## 数据约定

| 表 | 记录含义 | 主要约束 |
|---|---|---|
| `agent_task` | 一次用户任务 | task_id 主键；status + created_at 索引用于后续领取 |
| `agent_step` | 一次模型请求，包括最终回答轮次 | task_id + step_number 唯一；关联 Task |
| `agent_tool_call` | 一次工具调用 | step_id + call_order 唯一；step_id + tool_call_id 唯一；关联 Step |

应用生成 UUID 字符串作为 taskId、stepId、invocationId。
toolCallId 原样保留模型协议 ID，只在同一 Step 内唯一，不作为全局主键。
step_number、call_order 均从 1 开始，查询时必须显式排序。
外键禁止删除仍有下级记录的父记录，不自动级联删除历史。

任务输入和最终答案使用 LONGTEXT；模型完整响应、调用参数和结果使用 JSON。
model_response 在模型响应前可以为空；result 在调用尚未完成或结果未知时可以为空。
结果 JSON 保留 ToolSuccess/ToolFailure 结构，failure_reason 保存执行中断等原因。
JSON 列验证 JSON 语法；工具参数的 Schema 验证仍由 ToolCall 负责。

DATETIME(6) 和 Java LocalDateTime 均按 UTC 解释。连接参数强制 MySQL 会话使用 UTC；
如自定义 DB_URL，保留时间参数。业务代码后续生成时间时也必须使用 UTC。

## 状态与写入约束

- Task：PENDING → RUNNING → COMPLETED / FAILED；PENDING 在明确无法执行时也可失败。
- Step：RUNNING → COMPLETED / FAILED。
- Call：PENDING → RUNNING → SUCCEEDED / FAILED / UNKNOWN；未开始的调用中止时可标 FAILED。
- COMPLETED、SUCCEEDED、FAILED、UNKNOWN 都是各自状态域内的终态。
- UNKNOWN 表示可能已经产生副作用但结果未确认，不允许自动重试。

数据库 CHECK 约束目前只限制合法状态值和正序号，不负责判断旧状态到新状态的转换。
Store 通过带预期旧状态的条件 UPDATE 限制转换，检查影响行数；
终态禁止继续修改。Java record 是数据库快照，不提供执行或状态转换逻辑。
AgentState 保留运行时状态；TaskStore 另外记录创建后的 PENDING 状态。
V2 迁移新增 worker_id、heartbeat_at、lease_until 及过期扫描索引。

## 验证

```powershell
.\mvnw.cmd test
.\mvnw.cmd '-Dtest=MySqlMigrationTests' test
```

原有工具测试单独排除数据源自动配置，不访问开发数据库。
MySqlMigrationTests 使用 Testcontainers 创建独立的 MySQL 8.4 临时容器，验证自动迁移、重复启动、
唯一约束、外键、合法状态、JSON 及 Unicode 存储。需要 Docker 服务及镜像下载能力；
Docker 不可用时这组测试会明确显示 skipped，不能视为已完成 MySQL 验证。

## 同步持久化执行顺序

```text
TaskStore.create(PENDING) → start(RUNNING)
→ StepStore.create(RUNNING)
→ LlmClient.request
→ StepStore.saveResponse（完整原始响应）
→ LlmClient.parseDecision（含截断保护）
→ ToolInvocationStore.createBatch（整个批次在一个短事务中插入）
→ 每次调用：start → ToolCall → finish（逐条提交）
→ StepStore.complete
→ 下一轮，或 TaskStore.complete
```

最终答案轮次也会创建并完成 Step；网络失败时该 Step 保留空 model_response。
截断/参数 JSON 解析异常时保留原始模型响应，但不注册或执行任何工具调用。
ToolFailure 会作为调用 FAILED 写入 result，之后继续执行本轮其他调用和下一轮模型请求。
工具失败本身不会直接导致整个任务失败。

调用结果必须成功保存后才执行下一个工具。外部请求和工具执行期间不持有数据库事务；
Store 的单条更新自动提交，createBatch 的短事务保证插入中途失败不会留下半个批次。
终态更新不能重复调用；重复写入或 ID 不匹配会抛出状态冲突并停止执行。

## 异常与存储故障

Runner 异常或轮次耗尽时尝试保存 Task FAILED 和原因；当前失败 Step 也会收尾。
调用清理只更新 PENDING/RUNNING：PENDING → FAILED，RUNNING → UNKNOWN，已保存结果保持不变。
UNKNOWN 不会自动重试。后续工具异常不会撤销此前已提交的工具结果。

如果数据库不可用，系统无法保证失败原因一定落库。Runner 会停止继续调用工具并尝试各项清理，
仍不可写入时在日志和响应 failureReason 中标明 `Failure persistence incomplete`。
创建 Task 本身失败时不会请求模型。内部同步 run 返回的 taskId 可能没有对应数据库记录；
HTTP 提交接口在这种情况下返回 503，不返回接收成功响应。
结果提交后连接断开等情况也可能造成客户端无法确认提交结果，不进行自动重试。
进程崩溃后的 RUNNING 由租约过期扫描收尾，不自动重跑。

MySqlMigrationTests 另外覆盖 Store 的终态保护、批次插入回滚、完整运行、部分执行失败、
原始截断响应保存以及步数耗尽。无 Docker 时这些检查会跳过；单元测试仍验证写入顺序、
写库失败停止执行以及失败收尾降级。

## 阶段 3：提交与查询 API

旧的 `POST /tasks` 同步结果响应已替换为 `202 Accepted`，客户端应改为轮询查询。

```http
POST /tasks
Content-Type: application/json

{"task":"计算 1 + 2"}
```

```http
HTTP/1.1 202 Accepted
Location: /tasks/<taskId>

{"taskId":"<UUID>","status":"PENDING"}
```

`PENDING` 表示接收时的初始状态；Worker 可能在响应发送前就已开始执行。
`202` 只表示接收成功，不表示任务最终成功。

| 接口 | 返回内容 |
|---|---|
| `GET /tasks/{taskId}` | taskId、task、status、finalAnswer、failureReason、创建/开始/结束/更新时间 |
| `GET /tasks/{taskId}/steps` | 按 stepNumber 排序的数组，每轮包含按 callOrder 排序的 calls、原始模型响应及失败原因 |

查询直接读取 MySQL，不依赖 AgentState 或后台线程。进程重启后仍可查询历史记录。
步骤中可以出现正在执行的调用、已完成调用和失败轮次；任务刚提交时 steps 为 `[]`。
参数、原始响应和结果作为 JSON 对象/数组返回，不是二次编码的 JSON 字符串。
对外时间统一为 UTC ISO-8601（例如 `2026-10-08T01:02:03Z`）。
单个查询在只读事务内读取；MySQL 默认 REPEATABLE READ 隔离级别下使用一致性快照。
详情与步骤是两次独立请求，轮询期间状态可能变化。

错误约定：

- 缺失/空白 task、非法 JSON、非法 taskId 格式：400。
- 合法 taskId 对应任务不存在：404。
- 数据库存储操作失败：503，code 为 `TASK_STORAGE_UNAVAILABLE`。
- 本机执行容量耗尽不影响接收：任务仍以 PENDING 持久化并返回 202，等待后续调度。

## 阶段 4：数据库轮询与受控并发

```text
HTTP → TaskService.submit → 事务写入 PENDING → 202
PendingTaskScheduler → 查询本机可用执行名额 → 按名额读取 PENDING 候选
→ LocalTaskDispatcher 预占执行名额 → TaskWorker → AgentRunner.runExisting
→ 条件更新 PENDING 为 RUNNING → 只有更新成功的执行者请求模型/调用工具
```

`TaskService.submit` 仅负责事务写库，不调用线程池。调度器只能看到已提交的任务。
候选查询按 created_at、task_id 排序，使用现有索引，LIMIT 等于本机空闲名额。
没有容量就不查询数据库。候选查询本身不领取任务，领取动作在 Worker 中完成。
多个实例可能读到同一候选，但 PENDING → RUNNING 条件更新最多只允许一个执行者继续。
Runner 的同步 run(task) 保留供内部调用，HTTP 不使用该路径。

本机使用信号量预占容量，并记录 inFlight ID，避免候选在开始执行前重复占用名额。
执行结束或提交线程池失败时释放名额。线程池没有等待队列；等待任务始终保存在 MySQL。
线程池拒绝时不更改数据库状态、不回退到调度线程执行，后续轮询可再次尝试。
数据库暂时不可用时记录错误，下一次轮询继续尝试。

配置：

| 环境变量 | 默认值 | 含义 |
|---|---|---|
| `AGENT_EXECUTOR_WORKERS` | 2 | 每个应用实例的并发执行上限 |
| `AGENT_SCHEDULER_POLL_INTERVAL_MS` | 1000 | 上一次轮询结束到下一次开始的间隔（毫秒） |
| `AGENT_SCHEDULER_ENABLED` | true | 是否启用本机调度，可用于只接收/查询任务的实例 |

阶段 3 的 `AGENT_EXECUTOR_QUEUE_CAPACITY` 已移除，数据库承担待执行队列。
应用重新启动后会自动扫描此前的 PENDING，不需要重新提交 HTTP 请求。
RUNNING 不在待执行扫描范围内，执行中崩溃遗留的任务由租约恢复处理，不自动重跑。
这些限制针对后台调度；直接在业务代码中调用同步 Runner 不受调度器名额控制。

TaskApiTests 验证 HTTP 契约、输入校验、数据库查询、错误响应；LocalTaskDispatcherTests
验证名额、重复候选抑制及拒绝后重试。PendingTaskSchedulerTests 验证容量限制和轮询恢复。
MySqlMigrationTests 验证待执行查询与并发领取；TaskApiMySqlTests 验证完整提交、调度、查询链路，
以及不经 HTTP 提交的历史 PENDING 也会被领取。真实 MySQL 测试在无 Docker 时明确跳过。

## 阶段 5：心跳、租约与中断恢复

每次领取生成新的 UUID 执行者标识，`TaskLease(taskId, workerId)` 随执行链显式传递。
领取时以 MySQL UTC 时间设置 heartbeat_at 和 lease_until；心跳在独立线程池运行，
模型请求或工具执行耗时较长时也能续租。正常返回或异常退出时取消心跳。

续租只允许 RUNNING、执行者匹配且租约仍有效的任务；已过期租约不能被旧执行者重新激活。
续租被拒绝或心跳写库发生异常时，本地 LeaseSession 标记失效，后续检查停止推进任务。
心跳表示执行者仍存活，不是模型/工具超时机制；当前不会仅因任务耗时长就判定中断。

所有运行中 Task/Step/Call 写入都要求显式 TaskLease，并在短事务内先锁定父 Task，
检查执行者、RUNNING 和租约有效期，再执行写入。统一先锁 Task 的顺序，使恢复操作与写入串行化，
避免失效执行者覆盖恢复结果。failPending 仅允许处理未领取的 PENDING，不绕过运行中所有权检查。

TaskRecoveryService 分批扫描过期 RUNNING。每个任务在一个事务内：

1. 锁定 Task，重新检查是否仍然过期；续租已成功或已终结时跳过。
2. 将 RUNNING 调用标为 UNKNOWN，将 PENDING 调用标为 FAILED。
3. 将 RUNNING Step 和 Task 标为 FAILED，记录 `Task execution interrupted: worker lease expired`。

已保存的调用结果、已完成的 Step 保持不变；任意写入失败时整次恢复回滚，后续扫描重试。
普通运行失败的 TaskStore.fail 也在同一事务内补齐未完成的子记录收尾。
恢复可以被多个实例重复触发，只有仍过期且未终结的任务会发生转换。

| 环境变量 | 默认值 | 含义 |
|---|---|---|
| `AGENT_LEASE_DURATION_MS` | 60000 | 租约长度，必须大于心跳间隔 |
| `AGENT_LEASE_HEARTBEAT_INTERVAL_MS` | 10000 | 单任务心跳间隔 |
| `AGENT_LEASE_RECOVERY_INTERVAL_MS` | 10000 | 恢复扫描间隔 |
| `AGENT_LEASE_RECOVERY_BATCH_SIZE` | 100 | 每次扫描的任务数量上限 |

至少一个实例需要保持 `AGENT_SCHEDULER_ENABLED=true` 才会进行待执行和过期扫描。
手动调用 Runner 时也会建立租约与心跳。`GET /tasks/{taskId}` 新增 workerId、heartbeatAt、leaseUntil，
PENDING 时为 null，终结后保留最后记录供排查；状态是终结与否的判断依据。

升级时先停止不支持租约的旧版本实例，再部署新版本。Flyway 自动执行 V2；
旧 RUNNING 记录的 lease_until 为 null，会按执行中断统一收尾。PENDING 继续等待正常领取。

租约保护数据库状态，不能撤销已产生的外部副作用，也不能强制停止任意工具代码。
已获准的外部调用在进程暂停、租约过期后仍可能继续执行；进一步保证需要工具端幂等或执行者令牌校验。
因此 UNKNOWN 不自动重试，恢复后的 FAILED 不重新排入待执行队列。
