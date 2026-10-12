# Run / Attempt 实现

> V6 更新：工具恢复策略、稳定幂等键、执行记录与受鉴权恢复入口见 [tool-recovery.md](tool-recovery.md)。不包含自动重试或 Run 续跑。

> V5 更新：Run 持久化事件、同事务写入和可回放 SSE 已实现，见 [run-events.md](run-events.md)。旧 /tasks 快照流保持兼容；/runs 事件流使用新协议。

## 范围

现有 agent_task 是一次执行（Run），保留表名、task_id 和 /tasks 接口以兼容已有调用。新增 /runs 接口明确执行语义。持续 TaskDefinition、检查点续跑、自动重试和审批尚未实现；事件日志已在 V5 实现。

Run 继续使用 PENDING → RUNNING → COMPLETED / FAILED；PENDING 对应设计文档中的 QUEUED。本期没有重新打开终态 Run 的 API，因此正常 Run 只有一次 Attempt；表结构、递增令牌和查询接口已为后续多次尝试准备。

## 数据与执行权

- agent_task 保存输入、用户归属、结果、Run 状态，以及 active_attempt_id、fence_token。
- run_attempt 保存 attempt_id、attempt_number、fence_token、worker_id、心跳、租约、开始/结束时间及原因。状态为 RUNNING → SUCCEEDED / FAILED / EXPIRED。
- agent_step.created_attempt_id 指向创建该步骤的 Attempt；ToolInvocation 通过 Step 关联执行。未来单步跨 Attempt 续跑还需单独记录具体工具执行 Attempt。
- Attempt 的主键为 (run_id,attempt_id)，同一 Run 的 attempt_number 与 fence_token 分别唯一。Run 的活动指针使用复合外键，不能指向另一 Run 的 Attempt。
- Attempt 归属继承 Run，历史查询在 SQL 中联合校验 Run.user_id；历史迁移不能绕过上一批的 NULL 归属隔离。

## 原子领取

JdbcTaskStore.start 在一个短事务中：

1. SELECT … FOR UPDATE 锁定 PENDING 且无活动 Attempt 的 Run。
2. 读取数据库 fence_token 并递增；调用方提供的 token 不作为分配依据。
3. 插入 RUNNING Attempt，使用数据库 UTC 时间设置心跳和租约。
4. 设置 Run.active_attempt_id、fence_token，状态改为 RUNNING。
5. 返回数据库分配后的 TaskLease(runId,workerId,attemptId,fenceToken)。TaskLeaseManager 用返回值启动心跳。

同一 Run 的竞争者在同一行锁上串行化；败者不创建 Attempt。插入成功但 Run 更新失败时整个事务回滚。模型调用、工具执行和网络发送不在事务内。

两个字段的作用不同：attemptId 指定一次执行，fenceToken 指定该 Run 的执行代次。当前每次领取同时递增 attempt_number 和 fence_token；将来若需不创建 Attempt 的独立撤权，应将两者分开分配。

## 写入校验与故障收尾

JdbcLeaseGuard 是统一写入边界，按 Run → Attempt 顺序锁行并检查：

```text
Run.status == RUNNING
Run.active_attempt_id == lease.attemptId
Run.fence_token == lease.fenceToken
Attempt.worker_id == lease.workerId
Attempt.fence_token == lease.fenceToken
Attempt.status == RUNNING
Attempt.lease_until > 数据库当前时间
```

所有 Step/Tool/结果写入和心跳续租都经过这一边界。旧 Attempt、错误 token、过期租约和终态执行不能继续写入。续租只更新 Attempt，不再修改 Run 的心跳列；旧 /tasks 详情中的租约字段由最新 Attempt 投影，终态后仍可查看最后执行者。

成功：同事务写 Run COMPLETED、Attempt SUCCEEDED 并清空活动指针。失败：同事务收尾未完成子记录，写 Run FAILED、Attempt FAILED。最终模型 Step 的已有提交边界保持不变；V5 中每次状态写入与对应事件同事务提交。

恢复器首先锁 Run，再锁其活动 Attempt 并重新检查租约。过期时同事务将运行中工具标 UNKNOWN、未开始工具标 FAILED、未完成 Step/Run 标 FAILED、Attempt 标 EXPIRED，清空活动指针。已保存结果不覆盖；任何写入失败整笔回滚。重复恢复无效果。

没有自动重跑，UNKNOWN 也不自动重试。令牌保护本系统数据库写入，不能撤销已发出的外部副作用。后续允许接管之前必须实现检查点和工具幂等策略。

## API

以下接口都需要上一批实现的 Bearer 身份：

| 接口 | 行为 |
|---|---|
| POST /runs | 请求体 `{ "task": "..." }`，返回 202、runId/status、Location: /runs/{runId} |
| GET /runs/{runId} | Run 状态、输入、结果、activeAttemptId、fenceToken |
| GET /runs/{runId}/attempts | 按 attemptNumber 排序的历史，含状态、时间、结束原因与 legacy 标记 |
| GET /runs/{runId}/steps | 现有步骤协议 |
| GET /runs/{runId}/events | V5 可回放 SSE；首次快照含序号，重连通过 Last-Event-ID 或 after 补发 |
| GET /tasks/{taskId}/attempts | 兼容路径查询同一份历史 |

无任务或不属于当前用户统一 404。已接受但尚未领取的 Run 返回空 Attempt 列表。时间字段使用 UTC ISO-8601。历史列表当前不分页，因为本期没有多次自动尝试；引入重试时需增加分页与上限。

## V4 升级

V4 新建 run_attempt，把有可确认执行信息的旧记录导入一条 legacy Attempt，保留原心跳、租约、结果和时间；没有依据的时间保持 NULL，不伪造历史重试。PENDING 且从未执行的 Run 不产生历史 Attempt。旧 RUNNING 无租约记录导入后仍由恢复器收尾。

迁移移除 agent_task.worker_id/heartbeat_at/lease_until，租约只以 Attempt 为准；同时补回历史 Step 的 Attempt 关联。

部署前停止所有旧 Worker、备份并检查活动执行，随后执行 Flyway V4，再启动新版本。MySQL DDL 迁移不保证整个文件原子回滚；中途失败需检查已完成步骤后恢复，不能盲目重复执行或直接运行旧二进制。应用产生新写入后不能通过退回 V3 代码完成回滚，需要兼容转换或前向修复。

## 验证入口

`./mvnw.cmd test` 包含：

- MySQL 并发领取只有一个赢家、一个 Attempt。
- 错误 Attempt/token、过期执行者写入被拒绝。
- 领取和结束时注入数据库错误，验证 Run/Attempt/子记录回滚。
- 真实 V3 数据升级至 V4，覆盖待执行、运行、成功、缺失租约的旧记录及重复迁移。
- HTTP 新旧接口、Attempt 历史、用户隔离与 UTC 时间。
- TaskLeaseManager 使用数据库返回的 token 续租。

数据库测试使用 Testcontainers 临时 MySQL，不访问开发数据库；Docker 不可用时会跳过，报告时必须单独说明。
