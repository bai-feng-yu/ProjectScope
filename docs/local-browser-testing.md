# ProjectScope：当前功能与本地浏览器测试指南

更新日期：2026-10-12。本文对应当前代码，面向 Windows / PowerShell 本地开发。部署、备份说明见 [mvp-deployment.md](mvp-deployment.md)，自动验收记录见 [mvp-progress.md](mvp-progress.md)。

## 1. 目前支持哪些功能

| 功能 | 现在可以做什么 | 使用边界 |
|---|---|---|
| 持续对话 | 新建会话、查看列表、继续旧会话、保存用户消息和助手回答 | 页面最多读取最近 100 条消息；单次回答使用近期最多 20 条非失败消息，不是无限上下文 |
| 长期记忆 | 对话后提取候选；查看来源消息 ID；确认、替换同主题、纠正、删除、关闭记忆 | 只有确认后的记忆参与跨会话回答；不是自动保存所有信息，也没有向量数据库 |
| 跨会话个性化 | 在新会话使用当前用户已确认的背景和偏好 | 主题匹配与近期记录检索，最多注入八条；真实模型的准确性仍需人工判断 |
| 后台资料研究 | 把研究消息和 Run 一起保存，后台 Agent 调用工具，结果回到原会话 | 普通“发送对话”不调用搜索；需点击“发起资料研究” |
| Brave 搜索 Tool | `web_search` 返回最多五条标题、HTTP(S) 链接及摘要 | 需要服务端启用和密钥；不抓取任意网页全文，摘要不等于全文核验 |
| 过程追踪 | 查看 Run 状态、SSE 事件、最终答案、失败原因；接口可查询步骤和 Attempt | 页面为最小调试界面，事件以文本展示，不是完整可视化工作流编辑器 |
| 取消与资源限制 | 取消排队/执行中 Run；最大步骤、总运行时限、每用户并发限制 | 默认 10 步、300 秒、每用户 2 个 RUNNING；不能撤销已发生的远程操作 |
| 每周协助 | 创建每周研究任务、查看最近运行、暂停、恢复 | 固定北京时间 Asia/Shanghai；不支持任意 cron、修改频率、删除/归档任务 |
| 多用户隔离 | 会话、记忆、Run、周期任务按已验证 JWT 用户隔离 | 没有内置注册、密码登录、Token 签发或自动刷新 |
| 可靠执行 | 请求幂等、最终事务、结果发布去重、持久事件、租约失效安全收尾 | Worker 丢失租约后失败；不自动从断点续跑 |
| 工具结果核对 | 通过 Tool Recovery API 查询/人工记录不确定结果 | 没有对应浏览器面板，不等同于自动重新执行工具 |

其他已注册工具有 `search_tools`（搜索工具目录）、`describe_tool`（查看工具说明）和 `CalculateTool`。不要把 `search_tools` 当成互联网搜索；现有 CalculateTool 仅实现乘法分支，不是通用计算器。

未支持：高风险外部写入（邮件发送、文件删除、表单提交等）、完整审批、Checkpoint 自动续跑、精细 Token 配额、自动修改 Prompt/Skill、文件上传/RAG、多机高并发承诺。当前界面也没有会话删除、消息编辑或完整 OIDC 登录页。

## 2. 先选择测试方式

| 你想做的事 | 必须准备 | 能验证什么 |
|---|---|---|
| 不提供真实密钥，运行自动测试 | Java 21、可用 Docker | 真实隔离 MySQL、模型/搜索 HTTP 测试服务、权限、事务、产品链路和应用重启 |
| 只打开页面看布局 | Java 21、可连接的 MySQL，或自行提供静态资源预览 | 页面结构；没有身份配置时不能操作私人数据 API |
| 浏览器创建会话、查看历史、操作已有数据 | Java 21、MySQL、身份服务配置、有效 JWT | 已认证数据操作；无模型配置时发送消息会失败 |
| 浏览器真实对话和记忆提取 | 上一行 + 模型 API Key、模型名、模型地址 | 真实模型回答、候选记忆和跨会话使用 |
| 浏览器完整研究闭环 | 上一行 + Brave 密钥并启用搜索 | 实际搜索、引用、个性化研究和周期研究 |

目前**没有一条命令启动可长期访问的“假模型 + 假 JWT 浏览器演示模式”**。测试类里的临时身份/供应商服务仅用于自动测试，测试结束就关闭。如果暂时没有身份服务或真实密钥，可以先跑第 3 节；不要为了看到页面效果关闭认证，也不要把自动化测试的通过当成真实浏览器交互验收。

## 3. 先跑不依赖真实密钥的自动测试

打开 PowerShell：

```powershell
Set-Location C:\UserData\project\agent-backend
java -version
docker info
.\mvnw.cmd test
```

Java 应为 21；Docker Desktop 必须已启动并能运行 Linux 容器。测试使用独立的 MySQL 8.4 容器，不要求预先创建本地业务数据库。

只测试产品主线可以运行：

```powershell
.\mvnw.cmd test '-Dtest=PersonalMvpMySqlTests,PersonalAgentEndToEndTests,PersonalAgentRestartTests,SecurityIsolationTests,BraveSearchTests'
```

查看测试汇总，不能只看 Maven 退出码：

```powershell
$testSuites = Get-ChildItem target/surefire-reports/TEST-*.xml | ForEach-Object {
    [xml]$report = Get-Content -LiteralPath $_.FullName
    $report.testsuite
}
[pscustomobject]@{
    Tests = ($testSuites | Measure-Object tests -Sum).Sum
    Failures = ($testSuites | Measure-Object failures -Sum).Sum
    Errors = ($testSuites | Measure-Object errors -Sum).Sum
    Skipped = ($testSuites | Measure-Object skipped -Sum).Sum
}
```

最近一次完整验收为 148 项通过、0 失败、0 错误、0 跳过。Docker 不可用时部分用例会跳过，这不算完成 MySQL 验收。只跑部分测试时，报告目录可能保留旧报告；核对文件时间，不把混合报告当作本次全量结果。

## 4. 准备浏览器交互环境

建议先走“已有本地 MySQL + 本地 jar”路线，不依赖应用镜像构建。之前 Docker Hub 拉取 Java 基础镜像遇到网络超时；这不影响你在本机直接运行 Java。

### 4.1 准备专用测试数据库

使用 MySQL 管理客户端连接你自己的本地 MySQL 8.4，创建独立数据库，例如：

```sql
CREATE DATABASE projectscope_local_test CHARACTER SET utf8mb4;
```

为这个数据库创建专用测试账户，并授权其进行表结构迁移及数据读写。账户、密码由你在 MySQL 管理工具里设置；不要把 root 账户用于日常运行，不要指向有真实用户数据的库。Flyway 会在应用启动时自动建表和升级，无需手工导入各个 migration SQL。

在准备启动应用的同一个 PowerShell 窗口设置：

```powershell
$env:DB_URL='jdbc:mysql://127.0.0.1:3306/projectscope_local_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME='替换为测试数据库账户'
```

下面的辅助函数通过隐藏输入读取秘密，不把值写在命令历史或打印到终端；环境变量仍会被子进程继承，不是加密凭据仓库：

```powershell
function Set-LocalSecret([string]$Name) {
    $secureValue = Read-Host "请输入 $Name" -AsSecureString
    $secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        [Environment]::SetEnvironmentVariable(
            $Name,
            [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer),
            'Process'
        )
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
        $secureValue.Dispose()
    }
}
Set-LocalSecret DB_PASSWORD
```

### 4.2 准备身份服务和 JWT

当前后端是 JWT 验证方，不是签发方。你需要从自己选择的开发身份服务获取以下信息，并申请两个测试用户 A/B 的 access token（测试隔离时使用）。

```powershell
$env:AUTH_ISSUER='替换为身份服务实际签发方地址'
$env:AUTH_JWK_SET_URI='替换为身份服务实际JWKS公钥地址'
$env:AUTH_AUDIENCE='替换为该API配置的audience'
```

令牌要求：RS256 签名、非空 `sub`、未过期的 `exp`、匹配的 `iss`，且 `aud` 包含 `AUTH_AUDIENCE`。三个配置必须一起设置；`AUTH_JWK_SET_URI` 必须能被后端访问。

获得令牌的入口取决于你的身份服务：使用它提供的开发登录流程或客户端授权流程获取 access token。项目没有 `/login`、`/register` 或 `/token` 接口，不能在项目里直接申请 JWT；随便编码一个 JSON、在在线网站生成未可信签名的 JWT、只填写用户名，都无法通过认证。不要把实际令牌粘贴到在线 JWT 调试网站。

如果尚未选定或部署身份服务，这一步就是完整浏览器交互的前置缺项。可以先完成自动测试和静态页面检查，不能跳过认证继续私人数据操作。详见 [authentication.md](authentication.md)。

### 4.3 配置模型

```powershell
$env:LLM_BASE_URL='替换为模型服务的兼容API基础地址'
$env:LLM_MODEL='替换为该服务实际可用的模型标识'
Set-LocalSecret LLM_API_KEY
```

基础地址不要包含 `/chat/completions`，后端会自动追加。模型需要支持此协议及 function calling，才能运行研究 Agent。对话后的记忆提取是额外一次模型请求，会增加时间和使用量；不保证每句话都生成候选。

### 4.4 配置 Brave（研究需要）

```powershell
$env:BRAVE_SEARCH_ENABLED='true'
Set-LocalSecret BRAVE_SEARCH_API_KEY
```

只测试聊天时可先设置 `$env:BRAVE_SEARCH_ENABLED='false'`。浏览器的令牌输入框只接收身份 JWT，**不要填写模型或 Brave 的 API Key**。

### 4.5 确认调度与安全默认值

```powershell
$env:AGENT_SCHEDULER_ENABLED='true'
$env:AGENT_MAX_STEPS='10'
$env:AGENT_RUN_TIMEOUT_SECONDS='300'
$env:AGENT_MAX_RUNNING_PER_USER='2'
```

启动后再修改这些进程环境变量不会自动更新运行中的 Java 配置，需要重启应用。若你之前设置过 `SPRING_CONFIG_ADDITIONAL_LOCATION`，也检查它是否指向旧的本地配置；外部配置中的具体属性可能覆盖你预期的值，不能同时使用几套互相冲突的配置。

## 5. 启动并打开浏览器

继续在已配置环境变量的同一个 PowerShell 窗口执行：

```powershell
.\mvnw.cmd package -DskipTests
java -jar target/agent-backend-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1
```

`-DskipTests` 只跳过本次测试，不代表已经验证；建议先完成第 3 节。命令中的 loopback 绑定只供本机测试，不把服务暴露到局域网。数据库/Flyway 初始化成功、Web 服务启动后再打开：

```text
http://localhost:8080/
```

不要双击 `index.html` 使用 `file://`，也不需要另起前端 npm 服务；HTML/CSS/JS 与 API 都由 Spring Boot 同源提供。如果 8080 被占用，可在 Java 启动命令追加 `--server.port=8081`，浏览器相应改为 8081。

另开一个终端做基础检查：

```powershell
curl.exe -s -o NUL -w '%{http_code}' http://localhost:8080/
curl.exe -s -o NUL -w '%{http_code}' http://localhost:8080/conversations
```

预期分别是 `200` 和 `401`。后者是正确的未认证拒绝，不是启动失败。页面能显示不代表模型、数据库业务接口或搜索全部可用。

在页面顶部粘贴 A 的 JWT **正文**，不要带 `Bearer ` 前缀，点击“连接”。浏览器会自动加上 Authorization 头。成功后应看到已连接提示和会话/记忆/任务列表。刷新页面会清除内存中的令牌，需要重新连接；页面里的“刷新”按钮只刷新数据，不会退出登录。

## 6. 按顺序完成浏览器测试

### 6.1 持续对话与记忆候选

1. 左侧输入“职业规划测试”，点击“新建对话”。
2. 中间输入：`我有三年 Java 后端开发经历，目前希望寻找 Agent 应用开发岗位，偏好远程工作。`
3. 点击“发送对话”，等待回复与记忆提取完成。
4. 检查右侧“长期记忆”是否出现候选，核对内容、FACT/PREFERENCE/INFERENCE 类型及来源消息 ID。
5. 只确认准确的候选。候选内容不能直接编辑；可以先确认后纠正，或删除该主题并重新表达。

预期：聊天被保存；候选未经确认不会作为跨会话记忆使用。提取失败或内容不适合长期保存时可以没有候选，这不自动说明聊天失败。当前界面展示来源 ID，但没有点击来源直接跳转原消息的功能。

### 6.2 跨会话、纠正、删除与关闭

1. 新建“下一步选择”会话，询问：`根据你记住的我的背景，我适合优先准备哪些能力？`
2. 核对回答是否使用已确认背景，而不是虚构经历。
3. 修改已确认记忆为“更偏好混合办公”，点击“保存纠正”；再新建会话提问，验证使用新版本。
4. 对冲突候选，普通“确认”可能返回 409；只有确实要覆盖同主题事实时才点“确认并替换同主题”。
5. 点击“删除主题记忆”，确认删除范围；或关闭“启用”开关，再用新会话测试。

删除会清除该主题的全部记忆版本，不只是一个候选。关闭记忆停止提取和检索，并清除候选；已确认记录仍保留，可重新启用或显式删除。

必须使用新会话验证删除/纠正对长期记忆的影响：旧会话的原始消息仍包含旧偏好，历史 Run 输入快照也不追溯删除。不要把模型从当前聊天历史读到旧信息误判成“删除记忆失效”。

### 6.3 资料研究与运行过程

1. 选择接收结果的会话，输入：`请研究 Java 后端工程师转向 Agent 应用开发需要的能力，搜索公开资料，结合我确认过的背景给出建议，并标明来源链接。`
2. 点击“发起资料研究”，不要点普通“发送对话”。
3. 查看研究消息上的“查看运行”，检查 Run 状态和下方事件文本。
4. 等待终态，核对最终答案、资料来源，以及是否真正调用了 `web_search`。浏览器开发者工具 Network 中也可查看 `/runs/{id}/steps` 等接口响应。
5. 如果结果已完成但会话里暂时没有新回复，点页面“刷新”；Run 最终答案和会话发布是可靠衔接，但存在短暂发布延迟。

若 Brave 未配置，工具会返回 `SEARCH_NOT_CONFIGURED`；限流时可能返回 `SEARCH_RATE_LIMITED`。模型可能解释工具错误后正常结束 Run，所以 **COMPLETED 不等于成功搜索到资料**。必须同时检查工具结果与回答；引用仍需人工打开原站核对。

研究是会产生真实供应商用量的操作，测试时用短问题和少量请求。避免输入身份证号、密码、令牌或不必要的私人信息。

### 6.4 取消与失败提示

研究仍在 PENDING/RUNNING 时点击“取消当前 Run”。预期最终为 CANCELLED，或完成已先提交而保持 COMPLETED；不能出现已取消又被晚到结果改为完成。

较快的任务可能来不及手工取消，不必为了制造竞争反复调用真实供应商。精确取消/完成竞争、运行时限和每用户并发已由自动测试覆盖。取消不保证远端请求已被撤销；已经接受的后台 Run 也不会因关闭浏览器而取消。

模型请求失败时，普通聊天保留失败的用户消息；研究则可查询 Run 的失败原因。修复配置后重试是创建新消息/新 Run，不是断点恢复。同一会话若提示有回复进行中，先刷新；崩溃遗留的同步 PENDING 消息超过三分钟后，由下一次新消息提交处理过期。

### 6.5 每周任务：不需要等一周测试首次触发

1. 选择一个会话，在右侧填写“每周研究 Java Agent 岗位需要的能力变化”。
2. 将首次执行设为**北京时间的未来 2～3 分钟**，点击“授权每周执行”。时间输入即使电脑不是北京时间，也按 Asia/Shanghai 解释。
3. 等待到期后点击页面“刷新”，应出现最近 Run；查看运行和对应会话结果。
4. 首次触发后下一次为一周后的同一时间，不是每隔几分钟重复。
5. 另建一个未来几分钟到期的测试任务，提前点“暂停”；到期后刷新，确认没有新 Run。测试完成后暂停所有不再需要的任务，避免后续产生真实调用费用。

暂停不取消已经提交的 Run；如需停止当前执行，另点取消。恢复逾期任务可能立即合并触发一次。任务已有 PENDING/RUNNING 时跳过本次触发；用户达到运行上限也跳过本次触发，不无限补跑。

### 6.6 用户隔离

1. 记录 A 的一个会话 ID 和 Run ID，点击“断开”。
2. 用另一个 `sub` 的用户 B 的有效 JWT 连接。两枚同一用户的新旧 Token 不算两个用户。
3. B 的列表不能包含 A 的会话、记忆或周期任务。
4. 可在开发者工具控制台执行下面的同源只读请求；在浏览器弹窗输入 B 的 JWT，不要硬编码到脚本或发送给别人：

```javascript
(async () => {
  const accessToken = window.prompt('输入用户 B 的 access JWT，仅用于本次本地检查');
  if (!accessToken) return;
  const response = await fetch('/runs/替换为用户A的Run-ID', {
    headers: {Authorization: `Bearer ${accessToken}`}
  });
  console.log('状态码：', response.status);
})();
```

预期为 404；不存在的 ID 也返回 404。浏览器原生 prompt 不是遮蔽输入，注意周边隐私并避免录屏；也可只运行自动化隔离测试。开发者工具请求头含令牌，不要分享未脱敏截图。

### 6.7 保存与重启

完成一次聊天后，在运行 Java 的窗口按 Ctrl+C，再用同样命令和环境配置启动；浏览器重新连接相同用户，应能读取之前的数据。

不要删除数据库或重建不带原数据的数据卷。该手动检查验证持久化和正常重启，不等于强杀 Worker 的故障演练；孤儿租约和重启拾取 PENDING 研究由 `PersonalAgentRestartTests` 覆盖。

## 7. 常见问题

| 现象 | 优先检查 |
|---|---|
| 页面打不开 | Java 是否启动完成、端口是否正确、数据库和 Flyway 是否报错 |
| 页面能打开但连接是 401 | AUTH 三项、JWT 签名/issuer/audience/exp；输入框不要加 Bearer 前缀 |
| API 返回 403 | 用户是否被禁用；工具调用还需符合白名单 |
| API 返回 404 | 当前 JWT 是否属于资源所有者；是否复制了错误 ID |
| API 返回 409 | 幂等键内容冲突、同会话已有回复进行中、记忆版本变化或同主题冲突；先刷新 |
| 连接后聊天失败 | 模型 base URL/模型名/API Key 是否正确、兼容协议是否支持、供应商是否超时/限流 |
| 有回答但没有记忆候选 | 是否启用记忆、内容是否值得长期保存、提取模型是否返回有效 JSON；候选提取是尽力执行 |
| 研究一直 PENDING | `AGENT_SCHEDULER_ENABLED`、线程容量和每用户运行上限；检查服务器日志 |
| 事件流失败或不更新 | JWT 是否过期、Network 中 SSE 请求是否失败；刷新 Token 后重新连接并点击“查看运行” |
| 没有搜索来源但 Run 完成 | 检查工具是否被调用、是否返回配置/限流错误；不能只看终态 |
| 周期任务没有执行 | 时区、是否暂停、调度是否启用、是否 SKIPPED_BUSY / SKIPPED_OVERLAP |
| 修改本地 properties 不生效 | 本地敏感配置不打进 jar；须显式设置 `SPRING_CONFIG_ADDITIONAL_LOCATION`，并重启 |
| Docker 测试成功但真实聊天不可用 | 测试用的是临时 HTTP 服务和身份夹具，没有替你配置真实供应商或常驻身份服务 |

不要通过删除 Flyway 历史或修改已应用 migration 来绕过迁移错误，也不要为了避免 401 放开私人 API。只读检查后，保留脱敏错误信息定位原因。

## 8. 本次手工验收记录模板

复制以下清单到自己的测试记录，未实际操作的项不要勾选：

- [ ] 专用数据库，未使用真实用户数据；应用启动成功。
- [ ] 未认证首页 200，私人 API 401；A 可以连接。
- [ ] 新建/继续会话正常，重启后数据仍在。
- [ ] 候选准确、确认后跨会话可用，来源可见。
- [ ] 纠正/删除/关闭记忆符合预期。
- [ ] Brave 实际被调用，来源有效，回答与用户背景相关。
- [ ] Run 事件、最终答案和失败提示可见；取消行为正确。
- [ ] 首次周期触发有结果；暂停后不触发，测试任务已暂停。
- [ ] B 无法读取 A 的资源。
- [ ] 页面布局、按钮反馈、令牌过期和重连体验已人工检查。

记录日期、浏览器、模型标识、涉及的 Run ID 和失败项；不要记录 Token、API Key、密码或完整私人聊天内容。此前 148 项自动测试通过，并不意味着这些真实浏览器验收项已替你完成。
