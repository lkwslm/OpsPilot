# OpenAI-Compatible Chat Provider

## Purpose
定义 OpenAI-Compatible Chat Provider 的配置、普通与流式结果、能力探针以及稳定失败映射合同。

## Requirements

### Requirement: OpenAI-Compatible Chat 端点配置无虚假默认值
`OpenAICompatibleChatModelProvider` MUST 默认使用 `https://api.deepseek.com` 作为 base URL，模型名与 API Key MUST 保持未配置状态直到显式提供；资源相对路径 MUST 通过 URI 规则与 base URL 合并，不得无条件重复拼接 `/v1`。

#### Scenario: 不重复拼接版本路径
- **GIVEN** base URL 分别为 `https://api.deepseek.com` 和已包含 `/v1` 的兼容端点，且资源路径配置合法
- **WHEN** Adapter 构造 Chat 请求 URI
- **THEN** 每种配置只包含一次预期版本路径并指向正确资源，模型与凭据未被隐式填充

### Requirement: 普通与流式 Chat 结果统一规范化
Adapter MUST 支持普通响应与所需流式响应，将消息、工具调用、结构化输出、finish reason、实际模型 identity 和 Usage 映射到统一领域结果。流式中断、取消或协议无效 MUST NOT 产生最终消息；已接收 Usage 与片段只能进入脱敏审计和可恢复失败状态。

#### Scenario: 流式完整结束形成最终结果
- **GIVEN** 一个要求流式与工具调用的合法请求
- **WHEN** 真实端点按协议返回完整事件序列和结束标记
- **THEN** Adapter 只在完整结束后生成一个最终领域结果，工具参数、finish reason、identity 和 Usage 均可核验

#### Scenario: 流式中断不提交半成品
- **GIVEN** 端点已返回部分文本或工具参数但尚未发送完整结束标记
- **WHEN** 网络中断或父 deadline 取消请求
- **THEN** 调用返回可恢复的 `ChainFailure`，不发布最终消息，并审计已接收片段摘要与 Usage 而不保存完整敏感内容

### Requirement: 工具与结构化输出按已验证能力执行
Adapter MUST 只为 capability snapshot 已验证的模型发送工具调用、JSON Schema 或所需流式请求。结构化输出解析失败时，只有 profile 明确允许且预算/deadline 足够，系统才可执行最多一次结构修复；不得把无法验证的文本当成结构化结果。

#### Scenario: 未验证结构化能力被拒绝
- **GIVEN** 模型 capability snapshot 未包含调用方要求的 JSON Schema 能力
- **WHEN** 调用方提交结构化输出请求
- **THEN** 请求在发送前以 `MODEL_CAPABILITY_UNVERIFIED` 或等价稳定错误失败，不执行普通文本降级

### Requirement: Chat 能力使用真实最小请求逐项探测
启动探针 MUST 对模型 identity、普通 Chat、工具调用、结构化输出及 profile 所需流式能力分别执行最小真实请求，并记录配置版本、UTC 时间、Usage 与脱敏结果。静态协议名称或模型声明 MUST NOT 代替真实探针。

#### Scenario: 任一 required 能力失败
- **GIVEN** 一个 profile 同时要求工具调用、结构化输出和流式
- **WHEN** 真实端点仅通过普通 Chat 与工具调用探针
- **THEN** 未通过的能力不进入快照，该 profile 不可用于业务调用且 readiness 保持 DOWN

### Requirement: Provider 错误与取消映射稳定且脱敏
Adapter MUST 将 400、401、403、404/模型不存在、429、502/503/504、网络错误、Schema 错误、流中断、超时和取消映射为稳定 `ChainFailure` 分类，并保留 retryable、attempt、HTTP request/trace correlation 和脱敏摘要。Adapter MUST NOT 自行重试或切换 Provider。

#### Scenario: 鉴权失败不泄密且不可重试
- **GIVEN** 真实端点返回 401 或 403
- **WHEN** Adapter 规范化响应
- **THEN** 返回不可重试的鉴权 `ChainFailure`，关联 ID 完整，authorization header、API Key 与完整响应正文不出现在任何输出中
