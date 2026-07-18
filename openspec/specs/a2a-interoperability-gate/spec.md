# a2a-interoperability-gate Specification

## Purpose
TBD - Defines the Phase 0 contract and acceptance criteria for a2a-interoperability-gate.

## Requirements

### Requirement: 锁定 A2A 1.0.1 合同
系统 MUST（必须）将官方 release、SDK 或官方 proto 生成对象锁定到 `v1.0.1`，六张 Agent Card 的 `protocolVersion` 必须为 `1.0`，并声明 HTTP+JSON、skill 和允许的媒体类型。

#### Scenario: 六张 Card 通过锁定合同
- **GIVEN** 六张最小 Agent Card
- **WHEN** 使用锁定的 A2A 1.0.1 对象合同进行校验
- **THEN** 所有 Card 均以 `protocolVersion: "1.0"` 通过且包含各自 skill 和媒体类型

### Requirement: 持久 Task 与完整互操作
跨进程 A2A 实现 MUST（必须）支持 `send/stream/get/cancel/subscribe`，Task 状态与 Artifact 必须持久化，并能在 Server/Client 重启或 stream 断开后恢复获取。

#### Scenario: 断流重启后恢复
- **GIVEN** 一个正在 stream 且已持久化状态和 Artifact 的 Task
- **WHEN** stream 断开并重启 Server/Client 后执行 `get` 或 `subscribe`
- **THEN** 客户端从持久 Store 恢复 Task 状态和 Artifact，不重复已完成副作用

### Requirement: messageId 幂等与冲突
系统 MUST（必须）以 `messageId + request hash` 实现幂等；相同 messageId 和相同请求必须返回原 Task，相同 messageId 和不同请求哈希必须返回冲突。

#### Scenario: 重复相同请求返回原 Task
- **GIVEN** 一个已经接收的 messageId 和请求哈希
- **WHEN** 再次发送完全相同请求
- **THEN** 返回原 Task 身份且不创建新副作用

#### Scenario: 重复标识但内容不同
- **GIVEN** 一个已经接收的 messageId
- **WHEN** 使用不同请求哈希再次发送
- **THEN** 返回明确冲突且不修改原 Task

### Requirement: Artifact 身份与权限校验
写入领域表前 MUST（必须）校验 Artifact 媒体类型、major Schema、SHA-256、Task 身份、调用方身份和访问权限；专业 Agent 数据库角色不得写 `opspilot` 领域表。

#### Scenario: 非法 Artifact 不进入领域表
- **GIVEN** 一个媒体类型、Schema major、哈希、Task 身份或权限不合法的 Artifact
- **WHEN** A2A 接收方处理该 Artifact
- **THEN** 请求失败且领域表没有新增或修改记录

### Requirement: 终态单调与真实网络路径
Task 进入终态后不得离开终态；取消竞争、重复发送和恢复不得造成重复副作用。专业 Agent 调用 MUST（必须）经过 Compose 网络的真实 HTTP+JSON，禁止 Spring Bean 直调。

#### Scenario: 抓包证明跨进程调用
- **GIVEN** 六进程 Compose 中的一次专业 Agent 调用
- **WHEN** 收集代理日志或抓包证据并禁用网络路径
- **THEN** 正常调用可见为真实 HTTP+JSON，禁用网络后调用失败且不存在进程内快捷路径
