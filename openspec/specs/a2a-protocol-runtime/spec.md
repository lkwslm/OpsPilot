## Purpose

本规格定义 Phase 6 的 a2a-protocol-runtime 能力边界、约束、可验证场景，以及阶段验收所需的稳定行为合同。

## Requirements

### Requirement: 锁定 A2A v1.0.1 官方对象
A2A 实现 MUST 锁定已验证的官方 release/SDK 或 proto 生成对象 `v1.0.1`；六张 Agent Card MUST 设置 `protocolVersion: "1.0"` 并声明唯一 Agent 身份、skill、HTTP+JSON、streaming、安全方案和允许媒体类型。

#### Scenario: 六张 Card 通过官方合同
- **GIVEN** Supervisor 与五个专业服务各自 origin 的 Agent Card
- **WHEN** 使用锁定官方对象和 v1.0 Schema 校验
- **THEN** 六张 Card 均通过且 Agent 身份、skill、媒体类型和安全声明与部署清单一致

### Requirement: 实现完整 HTTP+JSON 操作集合
A2A Client/Server MUST 实现 `send/stream/get/cancel/subscribe`，协议请求 MUST 使用 `Content-Type: application/a2a+json` 和 `A2A-Version: 1.0`；不支持的版本、绑定或媒体类型 MUST fail closed。

#### Scenario: 五类操作真实往返
- **GIVEN** 通过 Compose 网络可达的专业 A2A 服务
- **WHEN** 依次执行 send、stream、get、subscribe 和 cancel
- **THEN** 抓包显示真实 HTTP+JSON、固定版本 header 和媒体类型，且不存在 Spring Bean 直调路径

### Requirement: 身份、skill 与 required extension 校验
Server MUST 在创建或读取 Task 前校验调用方服务身份、目标 Agent、skill scope、输入/输出媒体类型、协议版本和所有 required extension；未知 required extension MUST 拒绝，未知 optional extension MUST 按合同忽略或回显支持状态。

#### Scenario: 未知 required extension 被拒绝
- **GIVEN** 一个身份有效但声明未知 required extension 的 send 请求
- **WHEN** A2A Server 校验 capability
- **THEN** 请求在创建 Task 前失败，Task Store 和专业 Agent runtime 均无新增记录

### Requirement: messageId 与请求哈希幂等
Server MUST 以 `messageId + request hash` 持久化幂等结果；相同 messageId 和相同请求 MUST 返回原 Task，相同 messageId 和不同请求 MUST 返回冲突且不修改原 Task。

#### Scenario: 重放与冲突
- **GIVEN** 一个已成功创建 Task 的 messageId
- **WHEN** 先重放相同请求再发送不同内容
- **THEN** 相同请求返回原 Task 且无重复副作用，不同请求返回稳定冲突且原 Task 不变

### Requirement: 覆盖全部 TaskState 并拒绝非法状态
Mapper 和状态机 MUST 覆盖官方全部 TaskState；`UNSPECIFIED` MUST 作为 `InvalidAgentResponse` 拒绝且不得持久化为有效状态，未知枚举不得映射为成功，终态 Task MUST NOT 离开终态或接受续写。

#### Scenario: UNSPECIFIED 与终态续写
- **GIVEN** 一个返回 `UNSPECIFIED` 的远端响应和一个已 `COMPLETED` 的 Task
- **WHEN** 分别尝试持久化响应和追加状态/Artifact
- **THEN** 两者均被拒绝，前者无有效状态行，后者保持原终态与原 Artifact

### Requirement: 最终结果只通过完整 Artifact 交付
状态 Message MUST 只用于进度、澄清请求和限制说明；可供 Supervisor 接收的可靠最终结果 MUST 通过完整 Artifact 交付，并携带媒体类型、Schema version、Task/Run 归属、SHA-256 和引用信息。

#### Scenario: 状态消息不能冒充最终结果
- **GIVEN** 一个 `COMPLETED` 状态 Message 含结论文本但没有完整 Artifact
- **WHEN** Supervisor 处理远端完成事件
- **THEN** 该消息只进入进度审计，attempt 不以业务成功接收且不会产生 Evidence/Hypothesis
