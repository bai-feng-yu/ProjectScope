# LLM 网络超时

LlmClient 注入专用 RestClient，底层固定使用 Apache HttpClient 5。
LlmHttpConfig 管理连接池、超时以及客户端关闭；版本由 Spring Boot 管理。

| 配置 | 环境变量 | 默认值 | 意义 |
|---|---|---|---|
| llm.http.connection-request-timeout | LLM_HTTP_CONNECTION_REQUEST_TIMEOUT | 5s | 等待连接池提供连接 |
| llm.http.connect-timeout | LLM_HTTP_CONNECT_TIMEOUT | 10s | 建立网络连接 |
| llm.http.response-timeout | LLM_HTTP_RESPONSE_TIMEOUT | 120s | 等待响应、读取数据；也作为 socket 等待上限 |

支持 Duration 格式，例如 500ms、10s、2m；配置必须至少为 1ms。
这些限制不是一次请求的总时限，也不是整个 Task 的截止时间。
持续缓慢发送数据可能使总请求时间超过 response-timeout；DNS 解析也不应视为受 connect-timeout 完整约束。
HTTP 自动重试和自动重定向均关闭，避免透明地重复请求。

网络超时转换为 LlmTimeoutException，并区分 CONNECTION_POOL、CONNECT、RESPONSE_READ。
保留异常链供诊断；现有 AgentRunner 将明确的 LLM_TIMEOUT 原因保存到失败的 Step 和 Task，结束租约心跳。
没有完整有效响应就不会执行本轮工具。401、429 等 HTTP 错误仍保持原异常类型。
超时只说明本地等待结束，不保证远端停止模型计算或不产生费用。

验证：本地 HTTP 服务覆盖正常返回、延迟响应头、响应体停滞及 429 不重试；
模拟异常覆盖连接池和连接超时分类。未使用真实模型 API，也未声称已经验证公网连接/TLS 超时行为。
