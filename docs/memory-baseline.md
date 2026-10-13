# Memory 评测基线 v1

日期：2026-10-13。目标是测量当前实现，不先修改记忆算法提高成绩。

## 当前状态

| 阶段 | 数据准备 | 正式模型成绩 |
|---|---|---|
| LongMemEval-S cleaned | 56 题，六种问题类型及 abstention 各 8 题；2,629 个会话、13,818 条用户消息 | 尚未完成；不是通过 |
| PersonaMem v1 32k | 28 题，七类各 4 题；2,496 条用户消息 | 尚未执行 |
| Memora weekly/software_engineer | 15 题，remembering/reasoning/recommending 各 5 题；163 个历史会话 | 尚未执行 |

真实模型连通性检查已完成：一个**自建、非基准题**，导入 2 条历史消息，提取并确认 3 条记忆；新会话正确提及 Java 后端、远程工作及个人 Agent，关闭记忆的对照回答表示不知道用户偏好。共 4 次模型请求、约 119 秒。这不能替代 56 题基准成绩。

评测框架的本地回归包含：1 个真实 MySQL + 本地模型协议的框架测试、2 个评分函数测试、3 个记忆生命周期测试，以及 2 个 Python 抽样测试。确定性测试不是第三方基准成绩。

## 数据来源与固定版本

- [LongMemEval 官方仓库](https://github.com/xiaowu0162/LongMemEval)，数据使用 [cleaned 数据集](https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned)，MIT。revision：`98d7416c24c778c2fee6e6f3006e7a073259d48f`。
- [PersonaMem 官方仓库](https://github.com/bowen-upenn/PersonaMem)，[v1 数据集](https://huggingface.co/datasets/bowen-upenn/PersonaMem)，MIT。revision：`a8076d5608c93ba2a28983cd78aa99b01a163ae7`。不混用 v2/v3。
- [Memora 官方仓库](https://github.com/geniesinc/Memora)，Apache-2.0。revision：`a6493188efc836d6511ed5e4163fe3ba87da30ff`。

固定 seed 为 `20261013`，每类按 `SHA256(seed:question_id)` 排序后轮流抽样，不挑选短历史或容易题。LongMemEval 拒答题通过 `_abs` 后缀独立分层。原始数据和运行输出位于 Git/Docker 忽略的 `.memory-eval/`。每个输入包保存 `manifest.json`（版本、题目 ID、来源哈希、输入哈希和数量）、`input.json`、`gold.json`。不把大体积数据集提交到仓库。

PersonaMem 按官方 `end_index_in_shared_context` 截断，禁止读取未来消息；完整选项供回答模型选择，正确选项只出现在评分文件。Memora 按问题日期限定历史，操作标签、memory/forgetting evidence 和评分子问题均不进入待测模型上下文。

## 当前系统适配协议

1. 每批新建 Testcontainers MySQL，使用真实迁移；不读取本地配置中的数据库地址，不接触浏览器数据。
2. 每题独立用户。测试专用导入器将历史原文写入 Conversation/Message，保留 user/assistant 角色和顺序。没有生产历史导入 API，因此这不是浏览器 HTTP 导入验收。
3. 对每条用户历史调用现有 `MemoryService.extract`。不将多个消息合并成一条，不把标准答案当候选，不重写生产提取提示词。助手历史保存但不提取，数据集 system persona 不作为用户证词提取。
4. 模拟用户逐条确认候选；同主题使用 `replaceExisting=true`。这是明确的测试确认策略，不能声称产品已有自动确认能力。相同提取请求在单批内复用首次响应，原始响应缓存留在输出目录供审计。
5. 使用新建空会话调用实际 `ConversationService.send`，记录检索记忆及来源会话 ID。模型看不到完整历史或 gold。问题日期作为问题前缀，历史日期尚不注入提取提示词。
6. 再关闭记忆、新建空会话，取得同题 no-memory 对照，避免上一轮答案成为对照上下文。
7. 每题独立 JSON 记录状态、耗时、导入量、候选确认量、有效记忆量、检索内容及两份答案。只有评分完成才能报告质量指标。

模型配置只读取 `llm.base-url`、`llm.model`、`llm.api-key`，不输出密钥。默认读取用户指定的 `src/main/resources/application-local.properties`；字面配置优先于环境变量，未设置时使用 `LLM_*`。暂不支持 Spring 占位符展开。普通 Maven 测试只用本地 fixture，不调用付费模型。

默认串行处理题目，避免慢模型在四路并发下触发限流或排队超时。上游超时默认 300 秒；应用到本地代理的超时比它多 30 秒。对 408、429、常见 5xx、连接异常和超时最多重试两次，使用 1/2 秒退避；一次题目仍因供应商失败中断时，等待 30 秒并用新隔离用户重放该题，已成功的相同提取请求由本批缓存复用。连续五个调用最终失败才打开熔断，不再因累计的偶发错误永久停止。达到调用上限的批次为 `INCOMPLETE`。每题结束立即保存结果，传输错误写入不含密钥、请求正文和回答的 `provider-errors.jsonl`。

**目前不能从题目中途恢复**：临时数据库退出即清理，缓存用于审计，不自动加载到新一轮；不要把未完成题目排除后报告全量成绩。套件脚本会在指定根目录下创建 `attempt-<UTC时间>`，所以相同命令可安全重跑且不会覆盖失败证据。单阶段脚本仍要求使用新的输出目录。

## 评分口径

- LongMemEval：输出 `hypothesis`，使用独立评分阶段读取参考答案，记录逐题判断和分类型结果。当前自带评分为 ProjectScope 单模型 judge，不是官方评测脚本或排行榜成绩；生成与评分可能使用同一模型，存在自评偏差。可将逐题结果的 `question_id`/`hypothesis` 导出给官方 `evaluate_qa.py` 复核。
- PersonaMem：严格匹配 `(a)`～`(d)` 标签，多选或解释性输出不得猜测正确选项。分别统计 memory/no-memory 各类型准确率；重点检查偏好演变及偏好一致推荐类别。无记忆对照用于衡量选项自身可猜测性。
- Memora：使用官方 `evaluation_questions` 子问题，分别判断需要保留和需要遗忘的信息是否出现在答案中。按官方定义逐题计算 `FAMA=max(0, MPA−λ×(1−FAA))`，`λ=N_forget/(N_presence+N_forget)`，然后按类型求均值。当前为单 judge，不复现官方多 judge 投票配置。
- 报告同时列 expected/observed/graded 数量；解析错误是 `UNSCORED_ERROR`，不是正确，也不能当作整个基准通过。部分成绩不得脱离覆盖率引用。

## 执行命令

在仓库根目录，使用 Java 21、Python 3.12、Docker Desktop。无需为数据准备安装 Python 三方包。

```powershell
python tools/eval/prepare.py --benchmark longmemeval --count 56
python tools/eval/prepare.py --benchmark personamem --count 28
python tools/eval/prepare.py --benchmark memora
```

下列命令会调用付费模型。先检查供应商额度；LongMemEval-S 完整历史不是“只发 56 次问题”，其提取阶段需要上万次请求。不要为缩短耗时默默丢弃历史。

```powershell
.\tools\eval\Run-MemoryBaseline.ps1 `
  -InputFile .memory-eval/data/longmemeval-s-56-seed20261013/input.json `
  -OutputDirectory .memory-eval/runs/longmemeval-s-first `
  -MaxModelRequests 15000
```

PersonaMem、Memora 使用各自目录的 `input.json`，输出目录必须不同。建议按 LongMemEval → PersonaMem → Memora 顺序执行。模型请求上限仅约束生成阶段，不含后续 judge 调用；judge 每题一次逻辑调用，对限流、常见 5xx、连接异常和超时最多额外重试两次。

三阶段顺序执行（任何一阶段生成或评分不完整都会停止，不会把失败当作通过继续）：

```powershell
.\tools\eval\Run-MemorySuite.ps1 -OutputDirectory .memory-eval/runs/baseline-v1 -AllowPaidModel
```

本地生成请求上限分别为 15,000 / 3,000 / 2,000；重试尝试也计入上限。Memora 相同历史的提取通过单批响应缓存复用，不重复向供应商发送十五遍。同样仍使用供应商侧额度限制。批次中途退出无法自动续跑，请保证运行期间机器不休眠。慢模型可显式调整，例如 `-ProviderTimeoutSeconds 600 -MaxRetries 2`；只有确认供应商并发额度充足时才提高 `-Parallelism`。

2026-10-13 首次完整套件尝试在 LongMemEval 历史提取阶段失败：12 次上游尝试中 7 次成功、5 次失败，失败发生在旧的 120 秒边界附近；旧版只记录计数，无法从遗留产物证明具体异常类型。旧版又以“累计三次错误”打开全局熔断，使剩余题目立即失败。该批次状态为 `INCOMPLETE`，没有基准分数。上述传输策略和诊断记录用于修复这一失败模式。

```powershell
# 上一步已生成测试 classpath。评分单独执行，gold 不传给记忆运行器。
$evalClasspath = 'target/test-classes;target/classes;' + [IO.File]::ReadAllText((Join-Path (Get-Location) '.memory-eval/classpath.txt')).Trim()
java -cp $evalClasspath agent_backend.eval.MemoryScoring longmemeval `
  .memory-eval/data/longmemeval-s-56-seed20261013/gold.json `
  .memory-eval/runs/longmemeval-s-first `
  src/main/resources/application-local.properties
```

无需付费的回归：

```powershell
python -m unittest discover -s tools/eval -p 'test_*.py'
.\mvnw.cmd test '-Dtest=MemoryBaselineTests,MemoryScoringTests,MemoryLifecycleBaselineTests'
```

## 本轮应如实暴露的局限

- 提取提示词偏向职业信息，对其他领域的 LongMemEval/PersonaMem 问题可能少记或不记。
- 不提取助手发言，不保留结构化事件日期；助手信息题和时序题不能预先宣称支持。
- 检索只考虑最近 100 条有效记忆，主题文本匹配后最多返回 8 条；没有语义检索。
- 同主题替换依赖模型输出稳定 topic，不自动识别同义主题或跨主题矛盾。
- 用户确认由测试策略模拟。正式成绩必须注明 assisted confirmation，而不是自主记忆成绩。
- 自然语言“忘记 X”没有删除动作处理器。Memora 中仍可能复述失效信息；不能用评分标签替它删除。
- 显式 API 纠正、删除和关闭记忆有独立数据库契约测试，但删除不会追溯擦除旧聊天或已保存 Run 输入。这不是全局数据擦除承诺。

先保存这版原始成绩，再根据错误类型改进；不能在同一基线中悄悄调整提取、检索或确认规则。
