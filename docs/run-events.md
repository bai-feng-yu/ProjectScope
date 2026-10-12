# 持久化 Run 事件与 SSE 回放

> V6 更新：工具恢复策略、稳定幂等键、执行记录与受鉴权恢复入口见 [tool-recovery.md](tool-recovery.md)。不包含自动重试或 Run 续跑。

## 本批实现

V5 增加 `agent_task.last_event_seq` 和 `run_event`。事件主键为 `(run_id, seq)`，每个 Run 从 1 单调递增。事件包含 `type`、`schemaVersion`（当前 1）、Run/Attempt/Step/Invocation 标识、UTC 时间和状态载荷。

现有 Run 迁移为一条 `run.imported`（seq=1），仅表示迁移时观察到的状态；不伪造过去的模型、工具或 Attempt 事件。新 Run 从 `run.created` 开始。

## 原子写入

所有应用状态写入均在同一 Spring JDBC 事务中调用 `JdbcRunEvents`。写入顺序为：锁 Run → 校验 Attempt/租约/fence → 更新状态 → 分配事件序号并插入事件 → 提交。事件插入失败时，状态、序号以及同事务此前插入的事件全部回滚。

Run 锁将同一 Run 的并发写入串行化；不同 Run 不共享序号或锁。心跳只更新租约，不生成事件。SSE 只读取已提交数据，没有进程内事件队列，也不在数据库事务里发送网络数据。

事件覆盖：

- `run.created`、`run.started`、`run.completed`、`run.failed`。
- `model.started`（创建模型轮次）、`model.completed`（模型响应持久化）。
- `step.completed`、`step.failed`。
- `tool.queued`、`tool.started`、`tool.succeeded`、`tool.failed`、`tool.unknown`。
- `attempt.succeeded`、`attempt.failed`、`attempt.expired`；Attempt 开始由带 attemptId 的 `run.started` 表示。

失败和租约过期清理会逐条记录受影响工具/步骤。已开始但执行结果不确定的工具标记 `UNKNOWN`，不会自动重试。普通事件仅携带状态和标识，不复制 prompt、模型原文、工具参数/结果或故障原文。需要详情时使用已有的受鉴权详情接口。

注意：直接 SQL 修改业务状态绕过应用事件写入协议，不能用于正常业务写入。所有运行实例必须先统一升级，不能让 V4 旧实例继续写入 V5 数据库，否则无法保证事件完整性。

## SSE 协议

入口：`GET /runs/{runId}/events`，请求须携带有效 Bearer JWT；每次读取校验 Run 归属，发送时再次检查身份有效期。

首次连接不传游标：返回 `snapshot` 事件，`id` 为一致性快照的序号。载荷为 `{seq, snapshot}`，其中 snapshot 复用现有 TaskSnapshot 字段（含 taskId）。快照和序号在同一个显式 REPEATABLE READ 事务中读取，随后仅发送更大序号的事件，避免读取快照期间发生的更新被跳过。

重连：传 `Last-Event-ID: 42`，或 `?after=42`。请求头优先。只按序发送 `seq > 42` 的历史/新事件，不插入一个更晚的快照。`after=0` 从最早事件开始。

示例帧：

```text
id:43
event:tool.started
data:{"runId":"...","seq":43,"type":"tool.started","schemaVersion":1,"attemptId":"...","stepId":"...","invocationId":"...","payload":{"status":"RUNNING"},"createdAt":"..."}

```

每次查询最多 100 条；只有发送成功才移动连接游标。发送忙时延后查询，不丢弃事件。发送队列满或数据库/连接错误会关闭流，客户端使用最后处理成功的序号重连；连接可落到任何共享同一主库的实例。网络可能导致重复交付，客户端应以 `(runId, seq)` 去重。不要把“服务器 send 成功”理解为“客户端已持久确认”。

终态仍会排空所有剩余分页后关闭连接。终态且游标已追平时返回 HTTP 204，停止自动重连。未传游标的终态 Run 返回最终快照后关闭。负数、非整数、溢出或超出当前最大序号的游标返回 400；不属于当前用户的 Run 返回 404。建流前存储失败返回 JSON 503。心跳为 SSE 注释，没有 `id`。

旧 `/tasks/{taskId}/events` 保持原来的无游标快照协议。`/runs/{runId}/events` 不再是旧协议的别名，调用方需按上述协议接入。浏览器原生 EventSource 不支持自定义 Authorization 请求头，应使用支持 Bearer header 的 fetch/SSE 客户端；不要把令牌放入 URL。

## 边界与验证

当前不清理历史事件，因此暂不实现过期游标/retention 协议。实施保留期前，须补齐最小可用序号、过期游标响应和重新获取快照流程。首次完整快照仍随历史步骤数量增长，尚未做快照体积限制。数据库轮询、每连接读取和有限发送线程适用于当前规模，尚未做容量压测。

这是可恢复的进度交付与执行追踪，不是 Event Sourcing，也不是模型 checkpoint 恢复、工具 exactly-once 或自动重试。数据库仍是业务状态和事件的共同事实来源。

验证包括：真实 MySQL V3→V5 升级、事件插入故障回滚、并发无间隙序号、租约过期子状态事件、一致性快照、跨 reader 回放、归属隔离，以及 MockMvc SSE 游标、分页终态、背压、鉴权过期和异常边界。
