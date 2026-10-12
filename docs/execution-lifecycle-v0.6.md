# ProjectScope V0.6：Personal Agent MVP 计划与执行架构参考

状态：按产品 MVP 重新收敛；执行基础已有实现，产品闭环尚未完成。原设计日期：2026-10-11；计划调整日期：2026-10-12。

本文以第 0～3 节作为近期实施与验收依据。附录保留原平台级架构与历史差距，不再要求先完成全部平台能力才能交付 MVP。计划纳入不代表已经实现；本次只调整文档，不修改代码。

## 0. 目标、取舍与近期范围

### 0.1 产品目标与边界

近期目标不是继续完善通用 Agent 执行平台，而是交付一个能够理解、记住并协助用户的 Personal Agent：

> 用户讨论职业规划，系统保存对话并提取可确认的重要经历和偏好。几天后在新会话提问，系统正确使用相关背景，允许用户纠正目标变化。用户授权进行资料研究后，Agent 使用受限搜索/读取工具，结合个人记忆给出有来源的分析；用户能查看任务过程和结果，之后可建立并暂停每周研究任务。

MVP 面向少量用户、受限工具和单服务实例部署，不是可自主使用任意外部写入工具的多租户 SaaS 平台。继续使用 MySQL、现有 Scheduler、Lease、RunEvent 和 SSE，不新增消息队列、通用工作流平台或向量数据库作为前置依赖。

明确冻结 Checkpoint 自动续跑、完整审批、精细 Token/成本预算和高级分布式调度开发。租约过期后 Run 标记 FAILED 是 MVP 接受的行为；用户查看原因后可显式创建新 Run，不承诺断点恢复。保留现有 Tool Recovery 用于核对结果，不扩建自动重放流程。

### 0.2 原“5 项严重差距”的重新判定

原统计针对完整平台设计，不再全部作为 MVP 阻塞项。新的取舍如下：

| 原编号 | 原差距 | MVP 决策 | 必须保留的约束 |
|---|---|---|---|
| G1 | 生命周期事务未完全收敛 | 只收尾 A1；A2/A3 延期 | 最后 Step、最终结果、Run/Attempt 与完成事件统一提交；暂不建设 Checkpoint 联合提交和通用 Lifecycle 抽象 |
| G2 | 创建 Run 无请求幂等 | B1 最小版必须完成 | 相同用户/操作/键及相同请求返回同一 Run；内容冲突返回 409；并发提交不重复创建 |
| G3 | 无 Checkpoint、接管续跑 | 明确延期，不阻塞 MVP | 崩溃后安全失败、保留记录、拒绝旧 Worker 写回；手动重新发起是新 Run，不冒充恢复 |
| G4 | 动态授权和凭据边界不完整 | 保留 D1 最小安全边界 | 用户隔离、后端工具白名单、可信凭据选择及执行前检查；不开高风险外部写入，以此避免引入完整审批系统 |
| G5 | 无跨 Attempt 精细预算 | 改为三个运行限制 | 最大步骤数、Run 总执行时限、每用户并发上限；不开放自动重试，因此暂不建设跨 Attempt Token 预留和结算 |

简化不等于移除安全性：不允许跨用户读取记忆/任务、不允许模型自行授权工具、不允许重复请求创建重复 Run，也不允许以“简化取消”为由产生终态反转。

### 0.3 近期只保留 9 个 MVP 交付包

将原功能点按用户价值合并，不把它们拆成另一套庞大的基础设施待办。M4 为新增的 Personal Memory 主线，优先于持续任务和任何高级恢复功能。

| 编号 | 交付包 / 原编号 | 最小范围与完成标准 | 当前状态 |
|---|---|---|---|
| M1 | 单次执行收尾（A1、B1 最小版） | 统一最终完成事务；创建 Run 的幂等记录与 Run/初始事件同事务提交。键按用户+操作隔离，绑定确定的请求摘要；同内容返回原资源，不同内容 409；MVP 不自动清理去重记录，至少随 Run 保留，不建设通用幂等平台 | 部分基础已有；A1/B1 尚待实现 |
| M2 | 最小安全边界（D1、H 按需） | 对话、记忆、Run、周期任务统一按当前用户隔离；仅开放后端白名单内的只读工具，每次调用前检查。模型参数不能指定用户或凭据；服务级搜索凭据与用户私有凭据分离，未接入安全凭据选择前不开用户账户连接。保留 JWT 有效期与归属检查；被禁用用户/工具不能启动新调用 | 现有 Run 鉴权已有；新资源、白名单及禁用检查待补 |
| M3 | 持续对话（I1） | Conversation/Message 持久化、会话列表与历史读取；前端能继续旧会话或开启新会话。同步回答不强制创建后台 Run | 未实现 |
| M4 | Personal Memory / User Model（新增） | 从对话提取结构化记忆候选，支持确认、更新、跨会话检索、纠正、删除；回答使用最新有效的用户背景，并能追溯来源。具体合同见第 1 节 | 未实现，产品主线 |
| M5 | 消息与后台任务衔接（I2） | 请求消息与 Run 关联在同一数据库事务中保存；任务最终结果通过同事务或有唯一去重键的同库后台发布记录交付到会话。失败可见，重发不产生两份结果消息；无需 MQ | 未实现 |
| M6 | 个性化研究场景（I3） | 接入至少一个实际资料搜索/读取适配器，结合相关个人记忆输出有来源的职业方向研究；用户能在最小交互界面中看到消息、运行过程、成功或失败结果。不是只返回接口 JSON 或仅有模拟工具 | 现有 Agent Loop/SSE 可复用；实际产品场景未实现 |
| M7 | 运行限制与简化停止（F1、E1/E2 最小版） | 保留最大步骤数与 HTTP 超时，新增持久 Run 截止时间、每用户并发限制和基础停止操作；在调用边界阻止继续推进。仅补当前状态下的取消/完成最小竞争保护，不做审批/重试组合状态机 | 步数/HTTP 超时已有，其余待补 |
| M8 | 简单周期协助（G-T1/G-T3 最小版） | 用户显式创建/查看/暂停/恢复周期研究任务；复用数据库 Scheduler，先支持明确的每周频率。触发生成普通 Run 并交付会话结果；第一个个性化对话/研究版本可用后再做 | 未实现，MVP 最后一阶段 |
| M9 | 核心端到端与基础部署（J 最小版） | 真实 MySQL 的迁移/事务/幂等测试，多用户数据隔离，记忆纠正与跨会话使用，研究及周期任务完整流程；完成单实例部署说明、凭据配置、数据库备份和一次启动/重启演练 | 现有执行层测试已有；产品验收和部署待补 |

M1、M2 是小范围基础收尾，不得借机扩建整个平台。M7/M9 随各阶段实施，不应排成阻止 M3/M4 的长期工程。完整 MVP 包含 M8；首个可试用版本可先交付 M1～M7 的个性化对话与研究闭环，并明确尚不支持周期协助。

### 0.4 运行、停止和周期任务的最小合同

- 运行限制：现有最大步骤数继续生效；总时限从 Run 首次开始执行计时，持久化 deadline。启动下一次模型/工具调用前检查，外部请求超时不超过剩余时间；到期拒绝继续推进并收尾。不能把“停止等待响应”描述成外部请求必然已被撤销。
- 用户并发：领取 Run 时按用户串行检查运行名额，达到上限保留排队，不仅依赖进程内计数；完成、失败、取消及租约过期后释放名额。提交排队与执行中 Run 区分，避免把全局线程池大小误当用户上限。
- 简化停止：排队 Run 可立即取消；执行中记录停止请求，下一安全边界不启动新调用。若实现 CANCEL_REQUESTED/CANCELLED，同步补齐最小数据库状态、事件和收尾逻辑；取消与完成必须通过同一 Run 行锁或等价条件写入确保先提交者获胜，终态不反转。只延期审批/重试组合下的复杂竞争矩阵。
- 周期任务：先支持每周固定时间，首版限定并明确展示 Asia/Shanghai 时区，保存该时区标识及下次 UTC 触发时间。明确忙时跳过、停机合并为一次最新到期执行；不接受尚未支持的时区/规则。任意多时区、DST、复杂 cron、节假日历及事件触发延期，不能以静默转换代替明确限制。
- 即使 G-T2 独立手动触发 API 后置，M8 仍必须保留最小防重复和不重叠保证：锁任务、唯一触发键、创建 Run 与更新 next_fire_at 同事务；同一任务已有活动 Run 时跳过，不搭建分布式调度平台。
- 暂停周期任务只停止后续触发，不等于取消已有 Run；停止当前执行走独立的简化取消入口。失败记录可查，用户可手动新建研究，不自动补跑全部历史或自动续跑。
- 模型 Usage/费用可先做观测统计，未知值保持未知，不作为已实现的精细额度管理。MVP 不开放发送邮件、删除文件、自动提交表单等高风险外部写操作。

### 0.5 原 30 项的去向

保留旧编号供历史差距追踪，但不再逐项全部排进近期迭代：

| 原模块 | 纳入 MVP 的部分 | 延期部分 |
|---|---|---|
| A | A1 → M1 | A2、A3：模型批次/Checkpoint 大事务、通用 Lifecycle 抽象 |
| B | B1 最小版 → M1；只保留该操作必需的作用域/冲突/保留规则 | B2 通用命令幂等、B3 完整保留治理 |
| C | 维持现有失败收尾和 Tool Recovery | C1、C2、C3 全部：Checkpoint、接管和自动重试 |
| D | D1 最小安全边界 → M2 | D2、D3 完整审批；高级授权体系与审批绑定 |
| E | E1/E2 简化版及取消/完成的最小正确性 → M7 | E3 审批、重试、多阶段取消的完整组合矩阵 |
| F | F1 收敛为步数、总时限、用户并发 → M7 | F1 精细预算部分、F2、F3：Token 预留、跨 Attempt 结算、重试预算 |
| G | G-T1/G-T3 简化版 → M8；复用最低限度活动 Run 防重约束 | G-T2 独立手动触发 API、高级调度与完整任务版本治理 |
| H | 新资源用户隔离、必要外键/唯一约束随 M2/M3/M4/M8 落地 | H1 完整 API 版本治理、H2 全库租户复合外键改造、H3 事件清理与保留窗口；现有 /runs 与 /tasks 暂保留 |
| I | I1/I2/I3 → M3/M5/M6，Personal Memory 新增为 M4 | 自进化、自动修改 Prompt/Skill 并上线；Memory 不再延期 |
| J | 核心事务、迁移、权限、产品 E2E 和基础部署 → M9 | J1 全部双进程故障矩阵、J2 任意写工具供应商认证/容量压测、J3 高级多机交付演练 |

延期项没有默认交付日期。只有真实用户场景证明需要，或准备开放外部写操作、多实例规模化运行时，才重新评估对应设计；不能因原文中出现“本期必须”而自动恢复旧优先级。

## 1. Conversation、Personal Memory 与用户纠正

### 1.1 最小数据与处理流程

Conversation、Message 和 Memory 均有可信 user_id 与稳定 ID；跨资源关联必须检查同用户，不能由模型输入决定归属。先用 MySQL 存结构化用户事实/偏好和必要索引，按主题、更新时间、有效状态检索；只有实际检索质量不足时再评估向量检索。

记忆的最小字段包括：用户、类型/主题、内容、来源会话/消息、事实或推断标识、确认状态、版本、更新时间、有效/失效状态。User Model 先由有效记忆组成，不另建复杂画像推理平台。

处理流程：

1. 保存用户消息，使用当前会话和少量相关的有效记忆构建回答上下文。
2. 使用结构化输出提取候选事实/偏好，校验 schema、用户归属和来源；提取失败不阻塞已经保存的对话，也不清空既有记忆。
3. 模型推断作为候选而不是已确认事实。MVP 先由用户确认候选进入长期记忆；明确要求“记住”的内容可走显式保存动作，并展示保存结果。
4. 更新同一主题时保留来源和版本；冲突候选提示用户确认，不能悄悄覆盖。用户明确纠正优先于旧记忆，旧版本失效后不得继续作为当前偏好检索。
5. 新会话回答检索当前用户的相关有效记忆；无足够依据时询问，不编造用户经历或把旧目标当作现在的目标。

### 1.2 用户控制与隐私底线

- 最小界面可查看记住了什么、来源是什么，并支持修改、删除和关闭记忆提取；不只提供隐藏的后台提取流程。
- 删除/纠正必须影响后续检索与缓存；删除记忆不是删除原始聊天，两者在界面中区分。关闭自动提取或删除某条记忆后，不能在后台重扫同一来源又悄悄恢复，应保留必要的抑制标记而不是旧敏感内容。
- 不从外部网页指令或模型臆测直接写入用户画像；外部资料按不可信内容处理。未经用户明确要求，不自动沉淀敏感属性；密码、令牌和工具凭据不得写入 Memory。
- 发往搜索服务的查询尽量不携带用户身份和私密经历；回答可在后端结合记忆与公开资料。个性化不等于把全部记忆传给外部工具。
- MVP 验收不要求“自动记住一切”，要求少量相关背景可以正确保存、跨会话使用、纠正和删除，且始终按用户隔离。

## 2. 实施顺序与可试用里程碑

| 阶段 | 交付内容 | 阶段出口 |
|---|---|---|
| P0：冻结扩建、收尾基础 | M1；M2 的工具准入；保留已有回归测试 | 创建请求不重复，完成事务一致；无未经允许的写工具可执行。不建设自动续跑 |
| P1：个性化对话可用 | M3、M4；新资源隔离与最小交互界面 | 用户能持续对话，在新会话使用已确认记忆，并能纠正/删除 |
| P2：受限研究可用 | M5、M6、M7；边做边补 M9 | 用户发起实际研究，结合记忆生成有来源结果，能观察成功/失败和请求停止；具备运行时限与用户并发限制 |
| P3：周期协助与 MVP 签收 | M8、M9 剩余验收和基础部署 | 用户授权每周研究、查看结果、暂停后不再产生新触发；完整产品闭环可试用 |

安全与资源限制必须在对应功能向用户开放前到位。M9 贯穿各阶段，不等到最后才测试。除非调整产品范围，不以附录中的任意高级功能作为 P1/P2 的新增前置任务。

## 3. MVP 完成标准与当前证据

### 3.1 范围与验收清单

“纳入”表示计划范围，不表示已通过。以下产品标准取代原 14 项平台场景作为近期签收入口：

| 编号 | 纳入 MVP | 必须观察到的结果 | 当前情况 |
|---|---|---|---|
| V1 | 是 | 用户 A/B 的会话、消息、记忆、任务和周期结果互不可读写；猜 ID 返回 404 | 当前 Run 相关隔离已有；新资源待实现 |
| V2 | 是 | 重复提交同一研究请求只生成一个 Run；最终 Step/Run/Attempt/事件提交一致 | 待补 M1 |
| V3 | 是 | 对话能生成有来源的记忆候选，确认后持久化；修改/删除后旧内容不再进入有效检索 | 待补 M3/M4 |
| V4 | 是 | 新会话能正确使用相关经历和偏好；目标变化后使用新版本，不凭空补全；能解释记忆来源 | 待补 M4，使用固定多轮样例人工核对与自动回归 |
| V5 | 是 | 用户能提交真实只读研究任务，结果结合个人背景并附资料来源；白名单外工具被拒绝，查询不泄露不必要的私密信息 | Agent Loop 基础已有；真实场景待补 |
| V6 | 是 | 消息提交与 Run 关联不丢失；最终结果只发布一次；状态、失败原因和 SSE 可查询 | 执行与事件已有；消息衔接待补 |
| V7 | 是 | 步数、总时限、用户并发上限有效；停止后不再启动新调用，取消/完成竞争不反转终态 | 步数/HTTP 超时已有；其他待补 |
| V8 | 是，最后阶段 | 用户创建每周任务、收到结果；暂停阻止后续触发；同一时刻不重复创建、不与该任务未结束 Run 重叠 | 待补 M8 |
| V9 | 是 | Worker 崩溃后安全收尾 FAILED，历史结果保留、旧 Worker 写回被拒绝；用户可显式创建新 Run | 收尾基础已有；产品端失败提示和重新发起待补 |
| V10 | 是 | 在单实例部署和真实 MySQL 上跑通上述流程；迁移、权限、事务及基础重启测试有记录 | 现有后端测试已有；产品部署验收待补 |
| X1 | 否，延期 | Worker 崩溃后从 Checkpoint 自动续跑 | 不承诺 |
| X2 | 否，禁止开放 | 任意外部写入工具自主执行 | 工具准入必须拒绝，不以免责声明代替限制 |
| X3 | 否，延期 | 自动修改 Prompt/Skill 并上线 | 不承诺 |
| X4 | 否，延期 | 多机高并发部署、完整故障矩阵和容量压测 | 不作为 MVP 门槛 |

核心验收样例：同一用户先确认“有 Java 后端经历、希望寻找 Agent 岗位”，在新会话中得到使用该背景的回答；再将目标纠正为另一方向，后续回答不继续沿用旧目标。第二个用户无法读取上述消息、记忆或研究任务。随后实际运行一次公开资料研究，结果回到原会话；最后创建并暂停每周研究，验证暂停与触发边界。

Memory 质量不能只靠 schema 通过判断，需同时检查事实准确、相关性、时效性和用户纠正是否生效。研究结果也不能只以 Run=COMPLETED 判断合格，必须可读、可追溯来源并与用户问题相关。

### 3.2 已有基础与报告边界

截至本次文档调整，已有 JWT/Run 归属隔离、Run/Attempt 与租约 fencing、模型/工具结果持久化、状态事件事务、SSE 回放，以及 Tool Recovery 查询和人工确认。原始 AgentRunner 仍从输入开始执行，过期 Run 安全失败，不自动续跑。三个现有生产工具为计算、工具搜索和工具描述，不能把“工具搜索”误当作已接入真实外部资料搜索。

最近已有测试报告为 124 项通过、0 失败、0 跳过，包含真实 MySQL 测试。本次仅修改计划，未重跑代码测试。报告不证明 Conversation、Memory、周期任务、真实业务场景或生产部署已经完成。

实现说明：[run-attempts.md](run-attempts.md)、[run-events.md](run-events.md)、[tool-recovery.md](tool-recovery.md)。个人记忆是现在的产品主线，不再与自进化一起笼统推迟。

## 附录 A：原平台级架构合同（保留参考，不作为近期整体任务清单）

以下保留原第 2～10 节，供将来引入续跑、审批或多实例部署时评估。其“本期”“必须”等措辞属于原平台设计上下文；近期范围、顺序及验收以本文第 0～3 节为准。已有安全不变量继续保留，尚未开放的能力不能被解释为已经支持。

<details>
<summary>展开原执行生命周期、表结构、事务、恢复、审批与 API 设计</summary>

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


</details>

## 附录 B：原 14 项平台验收台账（历史基线，不是 MVP 阻塞清单）

截至 2026-10-12，原平台口径为 5 项已有验证、3 项部分达到、6 项未实现。该数字只记录既有实现，没有因范围缩减而把未实现项改成通过。表中 A1～J3 为旧功能编号，按第 0.5 节决定保留或延期；G1～G5 按第 0.2 节重新判定。

<details>
<summary>展开原平台验收场景与证据</summary>

| 编号 | 场景 | 必须观察到的结果 | 当前状态 | 证据或待补事项 |
|---|---|---|---|---|
| S1 | 两个 Worker 竞争同一 Run | 一条活动 Attempt，一个 fence_token 持有者 | 已有验证 | MySqlMigrationTests.concurrentClaimHasExactlyOneWinner；真实 MySQL 并发领取仅一个成功，独立 Attempt/fence 测试补充执行权约束 |
| S2 | 旧 Worker 在接管后写结果 | 被拒绝，不能覆盖新状态；不能声称外部效果被撤销 | 部分达到 | 已验证租约失效、错误 fence、收尾后拒绝写回；尚未实现新 Attempt 接管续跑。对应 C2/J1 |
| S3 | 状态写入成功但事件插入失败 | 整个事务回滚，不出现无对应事件的状态 | 已有验证 | MySqlMigrationTests.creationAndTerminalStateRollbackWhenEventInsertFails；ToolRecoveryMySqlTests 补充执行记录、恢复事件故障回滚。G1 的跨操作事务缺口仍需另行关闭 |
| S4 | 提交成功但 HTTP 回包丢失 | 原幂等键返回同一 Run，无重复任务 | 未实现 | 当前提交每次生成 UUID，无创建 Run 请求幂等。对应 B1/B3 |
| S5 | 工具结果提交后进程崩溃 | 恢复复用结果，无重复执行 | 部分达到 | 已验证过期收尾保留成功结果，但尚无重建上下文并复用结果继续执行。对应 C1/C2/J1 |
| S6 | 非幂等工具已发出但未存结果 | UNKNOWN + FAILED，不自动重试 | 已有验证 | 已有中途异常、租约过期、UNKNOWN 持久化和 MANUAL_ONLY 禁止自动查询/重试验证；可控假工具覆盖外部效果已发生窗口，不代表真实供应商已验证 |
| S7 | 审批等待时重启、审批前撤销授权 | 等待可恢复，续跑不能执行已撤权工具 | 未实现 | 无审批等待、恢复与动态撤权路径。对应 D1/D2/D3 |
| S8 | 审批与过期/取消并发 | 一个有效决策，无两次领取或副作用 | 未实现 | 无审批与取消状态机。对应 D3/E3 |
| S9 | 取消与完成并发 | 符合先提交者规则；无终态反转 | 未实现 | 无取消命令及完成竞争处理。对应 A1/E1/E2/E3 |
| S10 | SSE 快照读取期间产生事件 | 快照水位之后事件完整可达 | 已有验证 | MySqlMigrationTests.snapshotAndWatermarkRemainInOneRepeatableReadView 与 RunEventTests 的水位后接续测试 |
| S11 | SSE 中断后在其他实例重连 | 从 cursor 后有序回放，允许重复，无遗漏 | 部分达到 | 已验证独立 reader 从同库分页回放、MockMvc 重连协议；缺少两个真实服务进程的连接切换验收。对应 J1 |
| S12 | 用户 B 查询/订阅用户 A 的 ID | 404，无原始内容或存在性泄漏 | 已有验证 | SecurityIsolationTests、MySqlMigrationTests 和 ToolRecoveryMySqlTests 覆盖当前已有接口；不代表未来审批、记忆或动态授权已覆盖 |
| S13 | 同一调度时刻被多个实例扫描 | 一个 Run；next_fire_at 与创建结果一致 | 未实现 | 现有 Pending 调度是领取已创建 Run，不是 TaskDefinition 定时触发。对应 G-T2/G-T3 |
| S14 | Attempt 多次重试/审批续跑 | 总预算不重置；重试上限生效 | 未实现 | 无跨 Attempt 预算及完整重试/审批续跑。对应 C3/D3/F1/F2/F3 |

测试源码：[MySqlMigrationTests](../src/test/java/agent_backend/MySqlMigrationTests.java)、[ToolRecoveryMySqlTests](../src/test/java/agent_backend/ToolRecoveryMySqlTests.java)、[RunEventTests](../src/test/java/agent_backend/task/RunEventTests.java)、[SecurityIsolationTests](../src/test/java/agent_backend/SecurityIsolationTests.java)。测试报告仅证明已执行的测试，不证明全部设计目标。


</details>

原“先完成全套恢复/审批/预算，再做用户场景”的实施顺序已废止。未关闭的平台验收项继续保留记录，但不再统一阻止受限 Personal Agent MVP 发布；MVP 是否完成只按第 3 节及其必要安全约束判断。
