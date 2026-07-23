# bounded-supervisor-use-cases Specification

## Purpose
定义 Core 调查用例、有界 Supervisor 调度、预算、取消、恢复与停止语义，确保调查编排在资源边界内可终止、可恢复并产生确定的业务结果。

## Requirements

### Requirement: 核心 Use Case 合同
core MUST 定义创建、启动、恢复、取消 Incident Run、接收 A2A 结果和生成报告的 Use Case 输入输出，且这些合同 MUST NOT 使用 Controller DTO 或框架/SDK 类型。

#### Scenario: 用测试 Port 调用 Use Case
- **GIVEN** 纯领域输入和内存测试 Port
- **WHEN** 执行创建并启动 Incident Run
- **THEN** Use Case 返回领域结果且无需 Spring 容器、AgentScope 或 A2A SDK

### Requirement: 有限计划与持久化先于委派
Supervisor MUST 生成有限计划、串行执行 step，并在委派前持久化 step；补证回环和 `WAITING_INPUT` 恢复后 MUST 按显式规则重新规划。

#### Scenario: 委派前 checkpoint 失败
- **GIVEN** 新 step 已规划但 checkpoint 保存失败
- **WHEN** Supervisor 准备委派
- **THEN** 不执行 A2A 或 Tool 调用，Run 以受控失败保持可恢复

### Requirement: 不可由模型提高的多维预算
策略 MUST 同时约束轮数、Agent 调用、Tool、A2A、Token、费用和 deadline；模型输出不得提高或绕过任何预算。

#### Scenario: 模型请求增加预算
- **GIVEN** 当前 Run 已接近冻结预算上限
- **WHEN** 模型决策请求增加轮数或费用额度
- **THEN** 策略忽略该请求并按原预算继续或停止

### Requirement: 新颖性与有界停机
策略 MUST 使用动作/补证指纹、Evidence novelty 和连续无进展计数，并执行冻结停止优先级；预算耗尽或 `NO_PROGRESS` MUST 进入受限报告而非无限重试。

#### Scenario: 连续无新 Evidence
- **GIVEN** 连续轮次产生相同动作指纹且没有新 Evidence
- **WHEN** 达到无进展阈值
- **THEN** Run 以 `NO_PROGRESS` 停止新调用并转入受限报告路径

### Requirement: CAS 取消与迟到结果
取消 MUST 以 CAS 持久化意图，取消后不得创建新 step；系统 MUST 收敛下游取消，迟到 Artifact 只能用于审计而不得复活状态或进入分析。

#### Scenario: 取消后收到迟到 Artifact
- **GIVEN** Run 已记录取消意图且下游 Task 随后返回 Artifact
- **WHEN** 处理迟到结果
- **THEN** Artifact 仅绑定取消审计，Run 和 attempt 终态不改变且不触发新分析

### Requirement: core 不复制 Agent 运行循环
core MUST 只定义 AgentScope Adapter 所需事件、中断和策略 Port，不得实现第二套业务 Agent `while` loop。

#### Scenario: 架构检查运行循环所有权
- **GIVEN** core 应用与策略包
- **WHEN** 架构测试扫描框架驱动循环和 AgentScope 类型
- **THEN** core 只包含可单步评估策略，循环实现或框架类型使测试失败
