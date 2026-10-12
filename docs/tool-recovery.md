# 工具执行与安全恢复（V6）

本批提供能力声明、稳定幂等键、执行尝试记录、外部结果查询和人工确认。没有工具重试 API，没有自动重跑、Run 重新开启或 checkpoint 续跑；策略只描述未来恢复的必要条件，不直接授权重试。

## 工具适配器协议

AgentTool 新增 recoveryPolicy()、recoveryVersion()、execute(input, context) 和只读的 reconcile(input, context)。

| 策略 | 承诺 | 本批处理 |
| --- | --- | --- |
| READ_ONLY | 不产生业务写副作用 | 声明未来可重新查询；当前不自动重试 |
| IDEMPOTENT | 外部系统接受相同幂等键并复用同一次操作 | 允许查询外部结果；不自动重放 |
| RECONCILABLE | 可按稳定业务标识查询外部操作 | 先查询，结果不明确时继续 UNKNOWN |
| MANUAL_ONLY | 未证明具备安全恢复能力 | 禁止自动查询/重放，只允许带证据的人工确认 |

未声明工具默认 MANUAL_ONLY。现有计算、工具搜索和工具描述三个工具声明 READ_ONLY。生产代码中没有新增真实外部写适配器；测试中使用模拟外部写入服务验证协议。

策略和版本由后端注册工具决定，不接受模型或 API 传入。创建逻辑调用时保存策略及版本；执行前检查适配器没有变化，恢复查询前也检查快照与当前适配器一致。改变外部操作、参数解释、账户/业务标识或幂等语义时必须修改 recoveryVersion，不能只复用旧名称。

新调用的 idempotency_key 等于其内部 invocation_id（应用生成 UUID），创建时持久化并唯一约束。它不是模型生成的 tool_call_id，也不是参数哈希；相同参数的两次独立逻辑调用仍有不同键。执行时通过 ToolExecutionContext 传递给适配器；未来允许重试时必须复用原 invocation，而不能重新 createBatch。

声明 IDEMPOTENT 的适配器必须实现带 context 的 execute，并将键真正传递给外部系统。枚举声明与本地唯一约束都不能证明外部幂等；上线前还需验证外部键作用域、参数一致性和有效期。有效期外不能盲目重放。reconcile 必须是真正只读查询，不得在内部调用 execute，也不能把最终一致性查询的“暂未找到”解释为 NOT_EXECUTED。

## 正常执行与不确定结果

1. 在事务内校验 Run/Attempt/fence/租约，写调用 RUNNING、执行尝试和事件。
2. 提交后在事务外调用工具。
3. 返回后再次校验租约，同事务保存调用结果、执行尝试结果和事件。

tool_execution_attempt 将逻辑调用与实际执行记录分离，关联 Run Attempt。当前 Run 不重试，所以通常只有一条执行记录。开始写入或结果写入失败，状态、记录和事件会一并回滚。

找不到工具、参数/schema 校验失败属于明确失败。进入 execute 后的普通异常视为 ToolUncertain，持久化为 UNKNOWN，并停止当前工具批次、收尾 Run；不再把网络异常一律记作 FAILED。InvalidToolArgumentException 仅允许适配器在产生副作用之前抛出。框架没有为所有正常工具调用增加统一超时，适配器仍须设置连接/请求超时。

租约过期或中途宕机时，已开始调用及其 RUNNING 执行记录标记 UNKNOWN，尚未开始调用标记 FAILED。失去执行权的旧 Attempt 不能写回迟到结果。数据库 fencing 无法撤销已经发往外部系统的请求。

## 恢复 API

GET/POST /runs/{runId}/tools/{invocationId}/recovery，沿用 Bearer JWT 和用户归属校验；跨用户或错误 Run/Invocation 组合返回 404。

GET 返回调用的策略/键/版本/恢复结论，以及原始执行记录 executions、恢复审计 recoveries。审计中的 JSON 数据库列当前作为序列化字符串返回。GET 不执行查询适配器，也不修改状态。

POST 查询外部结果：

```json
{"requestId":"客户端生成的 UUID","action":"RECONCILE"}
```

POST 人工确认成功：

```json
{
  "requestId":"客户端生成的 UUID",
  "action":"CONFIRM",
  "outcome":"SUCCEEDED",
  "evidence":"在外部系统核对到的业务单号及核对依据",
  "output":{"按该工具的输出 schema 提供":"实际结果"}
}
```

人工确认未执行使用 outcome=NOT_EXECUTED，必须填写 evidence，且不携带 output。证据限 2000 字符。成功输出按注册工具的输出 schema 校验；适配器缺失或非历史版本不匹配时不能确认成功，需先恢复兼容适配器。历史 legacy 调用允许按当前工具 schema 人工确认，但不会自动提高其 MANUAL_ONLY 策略。

只有已终止 Run 中的 UNKNOWN 调用可以恢复，活动 Run 返回 409。人工确认只表示任务所有者的声明，不等于系统独立核实，应填写可核对的依据。操作不会恢复原 Run/Step 为成功，不会生成新 Run Attempt，也不会发起工具执行。

## 结果、并发与审计

- SUCCEEDED：补写逻辑调用的成功结果；原执行记录仍保留 UNKNOWN。追加工具成功和恢复完成事件。
- NOT_EXECUTED：保留 UNKNOWN 原始调用状态，记录恢复结论；阻止进一步重复处理。仅为未来重试提供判断依据，不自动执行。
- IN_PROGRESS / INDETERMINATE：保持 UNKNOWN，允许之后使用新 requestId 再次查询。

请求先锁 Run，取得一个 30 秒有效的恢复 claim，保存 requestId、操作者、动作和请求摘要，再释放事务执行查询。外部查询使用独立的 2 线程、16 队列有界执行器，最多等待 10 秒；超时、异常、排队拒绝、结果不合法或适配器变化都按 INDETERMINATE 处理，不推断未执行。取消线程不保证外部 I/O 已停止，因此适配器仍需配置传输超时；迟到查询结果不会进入提交路径。

同一调用不能有两个有效恢复 claim。相同 requestId 和内容已完成时返回当前视图，不重复调用适配器；运行中、过期或不同内容复用 requestId 返回 409。claim 过期后，下一个新请求会将旧 claim 记录为 EXPIRED。提交时再次校验身份有效期、归属、claim 状态和有效期，旧 claim 无权覆盖后继结果。

恢复状态、审计完成和事件同事务提交。事件仅携带标识与状态；详细证据保存在受鉴权的恢复审计中。证据可能含敏感业务信息，不要填写密码或令牌。

恢复可能在 Run 终态之后追加事件。原 SSE 连接已结束，不会自动重新开启；用户发起恢复后可刷新详情/恢复视图，或用旧事件游标重新连接获取新事件。

## 升级和验证

V6 给旧调用补入稳定键，策略一律保守迁移为 MANUAL_ONLY、版本 legacy。对有执行状态的旧调用仅导入一条 legacy 执行记录，不编造历史重试次数；PENDING 不创建执行记录。

数据库迁移会修改表并回填已有记录，部署时应停止旧写入实例，完成迁移后统一运行新版本。正常业务写入必须通过应用事务，不能绕过协议直接更新数据库。本次开发验证仅使用 Testcontainers 临时 MySQL，未迁移开发/生产库。

测试覆盖：外部写成功但响应丢失、同键去重适配器契约、UNKNOWN 查询补回、人工确认、未执行结论不重跑、并发 claim、claim 过期、旧 Attempt 写回、输出校验、归属/身份有效期、事件/执行记录失败回滚和历史迁移。模拟适配器测试不替代真实供应商幂等与对账契约测试。
