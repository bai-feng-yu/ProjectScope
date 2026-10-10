# Task SSE 快照订阅

先 `POST /tasks` 创建任务，再通过 `GET /tasks/{taskId}/events` 订阅。
响应为 `text/event-stream`，携带 `Cache-Control: no-cache` 和 `X-Accel-Buffering: no`。
UUID 非法返回 400，任务不存在返回 404，首次读取数据库失败返回 JSON 503。

```javascript
const events = new EventSource(`/tasks/${taskId}/events`);
events.addEventListener("snapshot", event => {
  const snapshot = JSON.parse(event.data);
  renderTask(snapshot); // 用完整快照替换页面状态
  if (["COMPLETED", "FAILED"].includes(snapshot.status)) {
    events.close(); // 防止终态后浏览器自动重连
  }
});
// 网络断开时 EventSource 自动重连；离开页面时调用 events.close()。
```

`snapshot` 字段：taskId、task、status、finalAnswer、failureReason、createdAt、startedAt、finishedAt、steps。
steps 使用现有步骤查询结构，包含 modelResponse、calls；calls 包含参数、结果、调用状态和失败原因。
不暴露租约字段，也不因 Task.updatedAt 的心跳变化重复发送。

首次连接立即发送当前快照（任务已经结束也一样），之后只发送有变化的快照。
终态快照发送后关闭连接。无变化时发送 `:heartbeat` 注释，前端无需处理。
每次重连都获取当前完整快照，不使用事件 ID / Last-Event-ID，不提供逐事件回放。
两次轮询之间发生的中间状态可能被合并；最终已持久化结果仍在快照中。
目前不提供模型 token 流式输出。

## 实现和配置

TaskService.getSnapshot 在一个短只读事务中读取任务、步骤、工具调用。
TaskSubscriptionService 按 taskId 管理当前实例上的连接，独立定时器轮询被订阅任务，
每轮每个 taskId 查询一次再广播。发送使用独立的 2 线程、64 队列执行器。
同一连接最多一个待处理或执行中的发送任务，慢连接不会无限堆积快照。
发送队列满、发送失败或订阅期间数据库读取失败时关闭相应连接，由客户端重新连接。
这些错误不修改任务状态；关闭订阅也不取消任务。
没有订阅时不查询数据库；此轮询独立于任务领取调度开关。

| 配置 | 默认值 | 含义 |
|---|---|---|
| agent.events.poll-interval-ms / AGENT_EVENTS_POLL_INTERVAL_MS | 1000 | 快照轮询间隔 |
| agent.events.heartbeat-interval-ms / AGENT_EVENTS_HEARTBEAT_INTERVAL_MS | 15000 | 空闲心跳间隔，实际精度受轮询影响 |
| agent.events.connection-timeout-ms / AGENT_EVENTS_CONNECTION_TIMEOUT_MS | 300000 | 单次连接生命周期，超时后客户端重连 |

完成、超时、连接错误及应用关闭都会清理订阅。多个应用实例分别维护自己的连接，
通过共享数据库观察任务变化，不要求执行任务的实例与订阅实例一致。
代理需允许长连接并关闭响应缓冲；跨域部署沿用应用的 CORS 配置。
当前仍没有用户身份与任务访问隔离，与现有查询接口一致。

本方案面向当前的小规模任务池。完整快照随历史增长，数据库查询与传输成本也会增长；
后续需要完整事件回放或更大规模时，再引入持久化事件日志与增量推送。
