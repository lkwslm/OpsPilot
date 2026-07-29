## Purpose

本规格定义 Phase 6 的 product-rest-sse-api 能力边界、约束、可验证场景，以及阶段验收所需的稳定行为合同。

## Requirements

### Requirement: 完整实现冻结 OpenAPI 操作
产品服务 MUST 实现 `opspilot-v1.yaml` 中的 `createIncident`、`getIncident`、`startIncidentRun`、`resumeIncidentRun`、`cancelIncidentRun`、`getIncidentState`、`getIncidentReport`、`streamIncidentEvents`、`listToolCalls` 和 `decideApproval`，并使 Controller、DTO、Mapper、状态码和媒体类型通过生成合同测试；MUST NOT 留下未实现 operation。

#### Scenario: OpenAPI operation 覆盖
- **GIVEN** 冻结 OpenAPI 文件和启动后的 8080 产品服务
- **WHEN** 运行逐 operation 合同套件
- **THEN** 每个 operation 均有可达实现且请求/响应通过 Schema，不存在 501、占位响应或未映射 operationId

### Requirement: 所有写请求强制幂等键
所有写 operation MUST 要求 `Idempotency-Key`，并按 `principal + operation + key` 持久化 request hash、处理状态和原始 HTTP 响应；同 key 同请求 MUST 重放原状态码/headers/body，同 key 异请求 MUST 返回 409 且不产生副作用。

#### Scenario: 创建 Incident 幂等重放与冲突
- **GIVEN** principal 已用一个 key 成功创建 Incident
- **WHEN** 先重放相同请求再用同 key 发送不同 body
- **THEN** 相同请求返回原 201 响应和同一 incidentId，不同请求返回 409 且 Incident 数量不变

### Requirement: 统一错误响应与 HTTP 语义
REST 边界 MUST 返回符合机器合同的 `ErrorResponse` 和 `requestId`，并严格使用 201/202/400/404/409 等冻结语义；响应 MUST NOT 泄露 Secret、内部堆栈、原始 Provider/A2A 正文或跨主体资源存在性。

#### Scenario: 报告未就绪返回 409
- **GIVEN** 一个属于当前 principal 但尚未完成报告的 Incident Run
- **WHEN** 调用 `getIncidentReport`
- **THEN** 返回 409 和稳定 ErrorResponse/requestId，不返回空报告、202 占位正文或内部状态堆栈

### Requirement: Incident 与 Run 归属强制校验
所有带 `incidentId`、`runId`、approvalId、eventId 或 tool call 引用的操作 MUST 在读取或修改前校验 principal、Incident 和当前/目标 Run 归属；跨 Run 或跨主体引用 MUST fail closed 且不得泄露对象存在性。

#### Scenario: 跨 Run 取消被拒绝
- **GIVEN** principal A 的 Incident 路径中携带 principal B 的 Run 或 approval 引用
- **WHEN** 执行 cancel、resume 或 decideApproval
- **THEN** 请求被稳定拒绝，两个 Run 状态均不变且响应不暴露 B 的标识细节

### Requirement: SSE 按持久事件与 Last-Event-ID 重放
`streamIncidentEvents` MUST 只发送事务提交后持久化并归属目标 Run 的事件，按稳定 eventId 顺序从 `Last-Event-ID` 之后重放；跨 Run/主体游标 MUST 返回零结果或稳定拒绝，MUST NOT 泄漏其他 Run 事件。

#### Scenario: 断线重连不丢不重
- **GIVEN** 客户端已消费到某个 eventId 后断线且服务继续提交事件
- **WHEN** 客户端以相同 Incident/Run 和 Last-Event-ID 重连
- **THEN** 仅按顺序收到该 ID 之后的已提交事件，不包含未提交、已消费或其他 Run 事件

### Requirement: 慢消费者与断连不阻塞核心事务
SSE 投影、连接写入或慢消费者失败 MUST 与核心状态事务隔离；失败只影响对应 outbox/event 的重试或连接，MUST NOT 回滚、重写、伪造核心状态，也不得无限缓存未发送正文。

#### Scenario: SSE 客户端停止读取
- **GIVEN** Run 持续产生事件且一个 SSE 客户端达到慢消费者上限
- **WHEN** 服务无法在边界内发送更多事件
- **THEN** 连接被受控关闭或降速，核心事务继续提交，客户端可用 Last-Event-ID 从持久表恢复
