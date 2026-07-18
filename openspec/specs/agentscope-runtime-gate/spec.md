# agentscope-runtime-gate Specification

## Purpose
TBD - Defines the Phase 0 contract and acceptance criteria for agentscope-runtime-gate.

## Requirements

### Requirement: AgentScope 类型隔离与结构化交互
AgentScope Spike MUST（必须）通过项目自有 Chat/Tool Port 适配框架类型，框架类型不得进入 core；Spike 必须验证结构化 Decision/Result、Tool calling、最多一次结构修复及只保存决策摘要而不保存隐藏推理。

#### Scenario: 结构错误只修复一次
- **GIVEN** 真实模型第一次返回不符合 Decision/Result Schema 的结构
- **WHEN** AgentScope Adapter 执行结构化调用
- **THEN** 运行时最多发起一次结构修复，返回可校验结果或以明确 reasonCode 失败，并且 core 未暴露 AgentScope 类型

### Requirement: 可审计 Middleware 与持久状态
运行时 MUST（必须）通过 Middleware 或事件采集 round、动作指纹、Usage、checkpoint 和取消信号，并将状态持久化到 PostgreSQL `AgentStateStore`，按 `(serverAgentId,userId,sessionId)` 隔离。

#### Scenario: 重启后恢复隔离会话
- **GIVEN** 两个不同状态键已保存 checkpoint
- **WHEN** Agent 进程重启并分别恢复会话
- **THEN** 每个会话仅恢复自身状态，审计记录包含恢复前后的 checkpoint 身份

### Requirement: 有界终止与取消
运行时 MUST（必须）分别处理最大轮次、deadline、外部取消、重复动作和连续无新 Evidence，并以冻结 reasonCode 终止；一旦中止，不得继续调用 Tool 或 Model。

#### Scenario: 每种终止原因独立生效
- **GIVEN** 可分别触发五种停止条件的测试输入
- **WHEN** 对每种条件执行 Agent 循环
- **THEN** 循环以对应 reasonCode 停止，停止事件之后不存在任何 Tool 或 Model 调用

### Requirement: 状态保存失败时关闭执行
状态保存失败 MUST（必须）使运行 fail-closed，不得退化到内存、本地文件或其他未声明状态存储，也不得继续调用 Tool 或 Model。

#### Scenario: 注入状态存储故障
- **GIVEN** PostgreSQL `AgentStateStore` 在保存 checkpoint 时失败
- **WHEN** Agent 尝试进入下一轮
- **THEN** 运行立即以明确技术失败结束，不产生后续 Tool/Model 调用，也不创建内存或文件回退状态

### Requirement: 框架能力证据
第 24.5 节每项 AgentScope 断言 MUST（必须）有独立自动化测试，报告必须记录实际 Maven 坐标、License 和被调用的真实 API。

#### Scenario: Spike 报告可审计
- **GIVEN** AgentScope 自动化套件执行完成
- **WHEN** 生成测试报告
- **THEN** 每条冻结断言均能映射到独立测试，报告包含准确坐标、License、API 和 commit
