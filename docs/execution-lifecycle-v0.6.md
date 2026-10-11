# ProjectScope V0.6：执行生命周期与事件架构

状态：设计提案，待实现。日期：2026-10-11。

## 1. 目标与已有基础

产品以用户服务为中心：对话承接即时需求，持续任务承接长期协助；执行方式可以是确定性代码、固定 LLM Workflow 或动态 Agent Loop。三者共用身份、预算、状态与审计机制。

本设计保留 MySQL、JDBC 调度、线程池、数据库租约与轮询；新增领域边界和恢复协议。数据库是执行状态的权威来源。事件用于通知、审计和续传，本阶段不采用以事件重建全部业务状态的 Event Sourcing。

当前代码依据：

- `AgentRunner.runExisting` 从任务输入建立新 AgentState，尚无历史上下文恢复。
- `JdbcTaskStore.recoverExpired` 将过期任务和未完成 Step 收尾为 FAILED，运行中的工具调用为 UNKNOWN，尚无自动续跑。
- `SequentialToolBatchExecutor` 逐条提交工具结果，提交成功后才执行下一条。
- `TaskSubscriptionService` 推送完整快照；尚无事件编号、用户隔离和逐事件回放。

这些是迁移起点，不代表以下功能已经实现。

## 2. 领域模型

| 对象 | 责任 | 示例 |
|---|---|---|
| Conversation / Message | 保存用户交流及回答 | 讨论求职偏好 |
| TaskDefinition | 持续委托、触发规则和执行约束 | 每周整理符合个人目标的岗位 |
| AgentRun | 一次触发对应的逻辑执行 | 本周的一次岗位研究 |
| RunAttempt | 一个 Worker 持有执行权的一段时间 | 首次执行、故障接管、审批后续跑 |
| AgentStep | 一次模型决策及其工具批次 | 搜索岗位后决定查看详情 |
| ToolInvocation | 一次具有稳定标识的工具操作 | 获取某条岗位详情 |
| ApprovalRequest | 对具体工具操作的审批 | 确认将某条信息发往外部 |
| RunEvent | 一条已提交、可续传的执行事件 | tool.completed |

TaskDefinition 1:N AgentRun；AgentRun 1:N RunAttempt、AgentStep、RunEvent；AgentStep 1:N ToolInvocation。

一次性请求允许直接创建 Run，`task_definition_id` 可空。普通对话不必创建 TaskDefinition；无需后台执行的简单回答可以走普通业务流程。需要工具决策、异步执行或审批时创建 Run。

同一个持续任务本期最多有一个非终态 Run。TaskDefinition 保存 `active_run_id`；创建 Run 和清空该引用都必须锁定 TaskDefinition。不同任务可以并发。

Run 创建时固定输入、任务版本、执行模式、模型/策略版本和预算。任务修改只影响后续 Run；授权撤销必须影响正在运行的 Run。暂停 TaskDefinition 只停止后续触发；取消已有 Run 使用单独操作。

## 3. 状态机

### TaskDefinition

`ACTIVE → PAUSED → ACTIVE`；`ACTIVE / PAUSED → ARCHIVED`。归档后不能触发新 Run，已有 Run 保留。

### AgentRun

| 原状态 | 新状态 | 触发与要求 |
|---|---|---|
| 无 | QUEUED | 接受请求并持久化；尚未持有租约 |
| QUEUED | RUNNING | 原子领取并创建新 Attempt |
| RUNNING | WAITING_APPROVAL | 保存精确的待批操作与检查点，结束 Attempt 并释放执行名额 |
| WAITING_APPROVAL | QUEUED | 审批通过；续跑时再检查授权和预算 |
| RUNNING | RETRY_WAIT | 可恢复故障且恢复策略允许，结束旧 Attempt |
| RETRY_WAIT | QUEUED | 到达退避时间，仍未取消且预算允许 |
| RUNNING | COMPLETED | 最终结果、Step 收尾与事件在同一事务提交 |
| 非终态 | FAILED | 不可恢复、重试耗尽、审批拒绝/过期或存在未解决的 UNKNOWN 副作用 |
| QUEUED / RETRY_WAIT / WAITING_APPROVAL | CANCELLED | 没有运行中的 Attempt，可立即取消 |
| RUNNING | CANCEL_REQUESTED | 用户提出取消，禁止启动新模型/工具调用 |
| CANCEL_REQUESTED | CANCELLED | Worker 到达停止边界，或租约过期后恢复器完成收尾 |

COMPLETED、FAILED、CANCELLED 为终态，不能重新打开。用户要求重做时创建新的 Run，可用 `parent_run_id` 关联旧 Run。取消表示停止继续推进，不表示撤销已产生的外部效果。

完成与取消竞争时，以持有 Run 行锁并先提交者为准：取消先提交则不再提交 COMPLETED；完成先提交则取消返回终态冲突。

本期仅支持审批等待，不提供任意时刻的通用暂停。未来暂停功能也需要停在可持久化的边界上。

### RunAttempt 与 ToolInvocation

Attempt：`RUNNING → SUCCEEDED / FAILED / EXPIRED / SUSPENDED / CANCELLED`，均不可重新打开。审批等待结束当前 Attempt；审批通过后的领取创建新 Attempt，这不计入故障重试次数。

Invocation：`PENDING → WAITING_APPROVAL → PENDING → RUNNING → SUCCEEDED / FAILED / UNKNOWN`；无须审批时直接 PENDING → RUNNING。未启动操作可转为 CANCELLED。UNKNOWN 表示可能发生外部效果但结果未确认，不能当作普通失败自动重试。

Step：`RUNNING → WAITING_APPROVAL → RUNNING → COMPLETED / FAILED / CANCELLED`。工具返回业务失败可以作为反馈继续模型循环，不必导致 Run 失败。

## 4. 表结构合同

以下为逻辑表与必需约束，不是可直接执行的 Flyway DDL。ID 沿用应用生成 UUID；序号和版本使用 BIGINT；时间使用 UTC DATETIME(6)。所有用户私有记录带 `user_id`，来自可信身份或父记录。

| 表 | 主要字段 | 约束 / 索引 |
|---|---|---|
| task_definition | id, user_id, conversation_id?, title, instruction, status, version, trigger_type, trigger_config, timezone, next_fire_at?, active_run_id?, policy_json, created_at, updated_at | UNIQUE(user_id,id)；INDEX(status,next_fire_at,id)；INDEX(user_id,status,updated_at,id) |
| agent_run | id, user_id, task_definition_id?, conversation_id?, parent_run_id?, task_version?, trigger_key, input_json, execution_mode, policy_snapshot, status, version, active_attempt_id?, fence_token, next_attempt_no, retry_count, next_retry_at?, last_event_seq, min_event_seq, checkpoint_version, final_result?, failure_code?, created_at, started_at?, finished_at? | UNIQUE(user_id,id)；UNIQUE(user_id,trigger_key)；INDEX(status,created_at,id)；INDEX(status,next_retry_at,id)；INDEX(user_id,created_at,id) |
| run_attempt | id, user_id, run_id, attempt_no, fence_token, worker_id, status, heartbeat_at, lease_until, started_at, ended_at?, reason? | UNIQUE(user_id,id)；UNIQUE(run_id,attempt_no)；UNIQUE(run_id,fence_token)；INDEX(status,lease_until,run_id) |
| agent_step | id, user_id, run_id, step_number, created_attempt_id, status, model_request_id, request_snapshot, model_response?, failure_code?, created_at, finished_at? | UNIQUE(user_id,id)；UNIQUE(run_id,step_number) |
| tool_invocation | id, user_id, run_id, step_id, call_order, protocol_call_id, tool_name, tool_version, arguments_json, arguments_hash, status, execution_attempt_id?, effect_kind, retry_policy, idempotency_key, approval_id?, result?, created_at, started_at?, finished_at? | UNIQUE(user_id,id)；UNIQUE(step_id,call_order)；UNIQUE(step_id,protocol_call_id) |
| approval_request | id, user_id, run_id, invocation_id, status, action_hash, policy_version, expires_at, decision_by?, decided_at?, version | UNIQUE(user_id,id)；UNIQUE(invocation_id)；INDEX(status,expires_at,id) |
| run_checkpoint | run_id, user_id, version, schema_version, phase, active_step_id?, next_call_order?, context_json, budget_json, created_at | PRIMARY KEY(run_id,version)；只追加，Run 指向最新提交版本 |
| run_event | user_id, run_id, seq, type, schema_version, attempt_id?, step_id?, invocation_id?, payload, created_at | PRIMARY KEY(run_id,seq)；INDEX(user_id,run_id,seq) |
| request_dedup | user_id, operation, idempotency_key, request_hash, resource_id, created_at | PRIMARY KEY(user_id,operation,idempotency_key) |

父子关系使用 `(user_id,parent_id)` 复合外键引用父表 `(user_id,id)`，防止不同用户记录被错误关联。活动 Attempt、活动 Run 引用须校验归属本 Run/TaskDefinition；循环外键需要在建表后补充。CHECK 限制状态枚举和正序号，状态转换由条件 UPDATE 控制。历史删除走显式清理流程。

Checkpoint 至少包含：协议版本、已提交对话上下文或稳定引用、模型响应、待执行调用批次、下一条调用位置、累计预算与下一阶段。模型请求发出前持久化请求快照；返回后先保存响应，再进入工具阶段。恢复不允许仅依赖内存 AgentState。

ToolInvocation 是跨 Attempt 稳定的逻辑操作。幂等键由 invocation_id 派生并绑定外部账户与工具，不随 Attempt 改变；幂等支持由工具适配器声明并验证，不能让模型声明。

## 5. 执行权与事务

统一锁顺序：有持续任务时先 TaskDefinition，再 Run，再 Attempt / Step / Invocation / Approval。需要扫描子表时先收集候选 ID，再按该顺序锁定和重新校验；不能持有 Attempt 锁后倒序锁 Run。外部模型、工具调用和 SSE 网络发送均在事务之外。

| 操作 | 同一短事务内的写入 |
|---|---|
| 提交请求 | 幂等记录、Run、初始 Checkpoint、run.created；必要时更新 active_run_id |
| 领取 Run | 验证 QUEUED，递增 fence_token / attempt_no，创建 Attempt，设置 active_attempt_id，Run → RUNNING，run.started / run.resumed |
| 保存模型结果 | 校验租约，保存原始响应、结构化决策和调用批次，推进 Checkpoint，追加 model.completed |
| 开始工具调用 | 校验租约、取消状态、当前授权、审批绑定与预算，Invocation → RUNNING，追加 tool.started |
| 提交工具结果 | 校验租约，写结果与状态，推进 Checkpoint，追加 tool.completed / tool.failed |
| 请求审批 | Approval + Invocation/Step 等待状态 + Checkpoint，Run → WAITING_APPROVAL，Attempt → SUSPENDED，清空 active_attempt_id，追加 approval.required |
| 审批决策 | 校验归属、过期时间、状态与 action_hash；通过则 Run → QUEUED，拒绝则 FAILED；追加决策事件 |
| 最终完成 | 最后 Step、最终结果、Checkpoint、Attempt 与 Run 终态、run.completed；清空 TaskDefinition.active_run_id |
| 取消 / 故障收尾 | 对应状态、未完成子记录、Attempt、Checkpoint 与事件；终态时清空 active_run_id |

开始模型请求同样需要租约、授权、预算和取消检查，并记录 model.started。Heartbeat 更新不产生用户事件。

Worker 携带 `ExecutionContext(userId, runId, attemptId, fenceToken)`。每次持久化写入都在父 Run 锁下验证：active_attempt_id 相符、fence_token 相符、Attempt 为 RUNNING 且数据库时间未超过 lease_until。终态、等待状态禁止旧 Worker 推进。

心跳允许在 Run 为 RUNNING / CANCEL_REQUESTED 时短暂续租，以完成取消收尾；取消另设完成期限，超过期限不再续租。续租不能复活已过期 Attempt。恢复器抢锁后重查租约，标记过期、清除执行权并决策后续状态。

fence_token 约束本系统数据库写入；外部工具只有主动验证令牌才能获得同样保护。即便已取消或租约已过期，已经发出的外部调用仍可能完成。

每次用户可见状态更新持有 Run 行锁，分配 `seq = last_event_seq + 1`，写事件并更新 last_event_seq，然后一起提交。回滚时三者一起回滚；同 Run 的并发事件按提交协议串行化。事件插入失败则状态更新也失败。

操作级 request_dedup 解决 HTTP 响应丢失后的重复提交。相同用户、操作和键且请求哈希一致返回原资源；内容不一致返回 409。幂等键保留期限必须在 API 合同中公布，过期后不能承诺去重。

## 6. 故障恢复与副作用

| 中断位置 | 恢复处理 |
|---|---|
| 模型请求前 | 从已提交请求/检查点继续 |
| 模型请求已发出但响应未保存 | 允许在策略范围内重发；可能重复计费，计入预算预留，不宣称模型 exactly-once |
| 响应和工具批次已保存，调用尚未开始 | 使用原批次继续，不重新生成决策 |
| 已保存工具成功结果 | 直接复用结果，不重复调用 |
| 只读工具 RUNNING 后中断 | 按工具策略退避重试，可获得较新的数据；结果注明观察时间 |
| 支持幂等的写工具结果未知 | 先用幂等键查询外部状态；只有适配器保证在有效期内重复键不会重复执行时才能重试 |
| 非幂等写工具 RUNNING 后中断 | Invocation → UNKNOWN，Run → FAILED，failure_code=TOOL_OUTCOME_UNKNOWN；禁止自动续跑 |
| 等待审批期间重启 | 从数据库继续等待，无 Worker、无租约占用 |

不能用一次新的“批准”消除 UNKNOWN：先核对外部结果；本期提供查询和人工记录处理结论，重新执行必须创建新的显式请求。

自动重试使用 RETRY_WAIT、next_retry_at、指数退避和上限，计数在 Run 上跨 Attempt 累计。审批续跑也共享总模型步数、Token 预算和执行时限；等待审批使用独立过期时间，不占运行时限。未知模型 Usage 保留估算/预留，不能记为零。

分阶段启用：先保留现有“过期即失败”语义；检查点重建与只读工具恢复测试通过后，才开启对应恢复策略。非幂等外部写入仍维持 UNKNOWN 边界。

## 7. 审批、身份与隔离

HTTP 身份来自认证系统，资源访问通过 `requireOwnedRun(userId,runId)` 等接口；不存在和不归属统一返回 404。Worker 从可信 Run 加载 user_id；模型参数不决定身份、工具凭据或资源归属。

approval.action_hash 覆盖工具名/版本、规范化参数、外部账户和操作范围。批准只适用于该操作。参数或目标变化需要新的调用和审批；审批通过后仍按当前授权检查，撤销授权不能被旧审批覆盖。

审批与过期清理都锁同一 Run 和 Approval；第一笔有效提交获胜。拒绝/过期本期使 Run 失败，并收尾等待 Step 和 Invocation。取消等待 Run 时将待批记录标为 CANCELLED。

API、Step 查询、事件回放、记忆检索和 SSE 使用相同归属规则。SSE 每批读取前重新检查账户/访问权；凭据失效或权限撤销后关闭连接，不只在连接建立时校验。原始模型响应、凭据及内部诊断不放入普通事件；工具结果按字段裁剪，详细轨迹通过单独授权接口访问。

认证机制单独落地为身份边界，不在本设计中绑定某种 JWT 库。浏览器原生 EventSource 不能随意设置 Authorization 头，客户端可使用支持流式读取的请求或同源安全 Cookie；不得把长期凭据放进 URL。

## 8. SSE 与事件合同

新增 `/api/v1/runs/{runId}/events`，SSE `id` 为本 Run 内十进制 seq；客户端按 `(runId,seq)` 去重。payload 含 schemaVersion 和资源标识。持久事件覆盖 Run、模型调用、工具调用与审批的关键边界；模型 Token 增量不在本阶段的持久事件合同内。

初次连接：短 REPEATABLE READ 只读事务读取快照及同一视图中的 last_event_seq=S，返回 `snapshot`，id=S；随后分页读取 `seq>S ORDER BY seq`。不得读完快照后在另一事务取最新水位，否则会漏事件。

断线重连：收到合法 Last-Event-ID=L 后，从 L+1 开始回放并接续轮询；此时不先发送更晚的快照来覆盖待回放历史。请求头优先于显式 after 参数。无 cursor 才执行初次快照流程。

本期事件与 Run 同寿命保留，预留 min_event_seq 以支持后续清理。非法/超前游标返回 400；未来游标早于保留窗口时，在打开流前返回 409 EVENT_CURSOR_EXPIRED，客户端重新获取快照。历史迁移不伪造中间事件，只写 run.imported 快照边界。

终态事件必须在此前事件发送后再关闭。若重连 cursor 已到终态最后一条事件，返回 204，客户端停止重连。心跳是无 id 的 SSE 注释，不推进 cursor。

继续通过数据库轮询支持跨实例订阅。每条连接保留独立 cursor；同一 Run 可共享有界读取结果，但不能把最新 cursor 广播给落后的连接。慢消费者队列超过上限时断开，由持久事件续传。重复投递允许，丢弃未送达事件并推进 cursor 不允许。网络中断不取消 Run。

## 9. API 边界

| API | 行为 |
|---|---|
| POST /api/v1/runs | 创建一次执行，支持 Idempotency-Key，返回 202 + runId |
| GET /api/v1/runs/{id} | 用户可见快照、状态、最后事件序号 |
| GET /api/v1/runs/{id}/steps | 分页读取可见执行步骤 |
| GET /api/v1/runs/{id}/events | 按上述合同订阅 |
| POST /api/v1/runs/{id}/cancel | 请求取消；已取消返回当前结果，已完成/失败返回 409 |
| POST /api/v1/approvals/{id}/decision | approve / reject，支持幂等键；重复一致决策返回原结果，冲突返回 409 |
| POST /api/v1/task-definitions | 创建持续委托，初期仅手动/定时 |
| PATCH /api/v1/task-definitions/{id} | 携带预期 version 修改、暂停或归档；版本冲突 409 |
| POST /api/v1/task-definitions/{id}/runs | 手动触发；已有活动 Run 返回 409 并附其 ID |

消息路由由应用服务按需求选择执行路径。需要后台执行时，保存消息和创建 Run 在同一事务内，响应关联 messageId/runId。最终结果与关联助手消息的发布也应原子提交或使用同库后台任务可靠衔接。

定时调度：按 next_fire_at 扫描；事务中锁定 TaskDefinition、重查 ACTIVE 与到期条件，以 definitionId + version + 计划 UTC 时间生成稳定 trigger_key。创建 Run 与推进 next_fire_at 同事务提交。暂停/修改与调度通过同一锁串行化。

本期忙时跳过本次触发；停机错过多个时间点时合并为一次最新到期执行，不补发全部历史。时区保存 IANA 标识；夏令时缺失时刻跳过、重复时刻只执行一次。触发配置/API 返回这些规则。用户输入时区转换为 UTC 后保存触发时间。事件触发延后，不预先引入消息队列。

## 10. 模块责任

- Application Service：用户命令、消息路由、授权入口、幂等与事务编排。
- RunLifecycleService：状态转换、执行权、取消、审批续跑、最终结果与事件原子提交。
- Task Scheduler / Dispatcher：扫描到期/待运行记录、容量控制、提交 Worker。
- AgentRunner：读取 Checkpoint，决定下一阶段；不直接写业务状态或推送 SSE。
- Tool Gateway：Schema、权限、审批绑定、凭据选择、幂等适配和调用。
- Model Gateway / Context Builder：模型协议及上下文构建；输入快照可追踪。
- RecoveryService：过期执行权收尾与安全恢复决策。
- EventQueryService / SubscriptionService：授权后的有序事件查询和连接管理。

所有执行方式调用相同 Lifecycle API，避免 LLM Workflow 绕过预算或用户隔离。新增业务映射可渐进引入 MyBatis，但本期不以替换 JDBC 为前提。

## 11. 迁移与实施顺序

1. 身份与归属：新增用户归属及受保护查询；覆盖原有任务、步骤、工具和 SSE。旧数据仅通过明确的所有者映射回填，无法确认的记录隔离，不能分配给任意登录用户。
2. 引入 Run / Attempt：新建表、保留旧 ID 映射。停止旧 Worker，等待运行任务结束或按原规则收尾，再迁移；旧 agent_task 映射一次 Run，不伪造持续 TaskDefinition。PENDING → QUEUED，终态原样映射；历史 Attempt 仅按可证明的记录导入，缺失信息标为 legacy。旧 API 通过适配器读写唯一的新存储，不长期双写。
3. 原子状态与事件：所有写入收敛到 LifecycleService，接入 run_event 与一致性快照，再启用新 SSE。旧 SSE 暂保留快照协议，避免旧客户端把新事件当快照。
4. 检查点与只读恢复：从持久记录重建 AgentState，工具批次位置明确；通过故障注入后开启有限自动重试。保留未知副作用失败语义。
5. 审批与取消：加入等待状态、释放执行名额、审批后重新授权、新 Attempt 续跑与取消竞争处理。
6. 持续任务：增加 TaskDefinition 和手动触发，再增加定时规则、忙时跳过与停机合并；最后接入真实 Personal Service 场景。

迁移前备份、记录行数与状态分布；离线校验 ID、用户归属、子表关联和终态结果后才切换。新写入开放前可回切旧库；开放后回滚需兼容新状态的数据转换或前向修复，不能直接启动旧二进制。现有 Flyway V1/V2 不修改，后续实施新增迁移。

## 12. 验收场景

| 场景 | 必须观察到的结果 |
|---|---|
| 两个 Worker 竞争同一 Run | 一条活动 Attempt，一个 fence_token 持有者 |
| 旧 Worker 在接管后写结果 | 被拒绝，不能覆盖新状态；不能声称外部效果被撤销 |
| 状态写入成功但事件插入失败 | 整个事务回滚，不出现无对应事件的状态 |
| 提交成功但 HTTP 回包丢失 | 原幂等键返回同一 Run，无重复任务 |
| 工具结果提交后进程崩溃 | 恢复复用结果，无重复执行 |
| 非幂等工具已发出但未存结果 | UNKNOWN + FAILED，不自动重试 |
| 审批等待时重启、审批前撤销授权 | 等待可恢复，续跑不能执行已撤权工具 |
| 审批与过期/取消并发 | 一个有效决策，无两次领取或副作用 |
| 取消与完成并发 | 符合先提交者规则；无终态反转 |
| SSE 快照读取期间产生事件 | 快照水位之后事件完整可达 |
| SSE 中断后在其他实例重连 | 从 cursor 后有序回放，允许重复，无遗漏 |
| 用户 B 查询/订阅用户 A 的 ID | 404，无原始内容或存在性泄漏 |
| 同一调度时刻被多个实例扫描 | 一个 Run；next_fire_at 与创建结果一致 |
| Attempt 多次重试/审批续跑 | 总预算不重置；重试上限生效 |

状态和锁竞争使用真实 MySQL 集成测试；外部调用使用可控假服务注入“调用已发生、结果尚未提交”的窗口。SSE 测试覆盖 cursor、水位、慢消费者和跨实例。实现完成后才记录测量结果，不把设计指标写成已验证成果。

本阶段第一批可交付结果：已鉴权的单次 Run，独立 Attempt，原子 RunEvent，以及可续传 SSE。Personal Memory 和自进化后续使用这些接口；其业务语义不塞入运行状态机。
