## ADDED Requirements

### Requirement: 冻结物理模块清单
系统 MUST 仅按设计建立 `opspilot-core`、`opspilot-tools-default`、`opspilot-agent-runtime-agentscope`、`opspilot-a2a`、`opspilot-adapters/*`、`opspilot-server`、`opspilot-evaluation`、`sample-system`、`fault-lab` 和 `deployment`；`opspilot-adapters` MUST 只作为聚合目录，且不得为单个接口新增 Maven 模块。

#### Scenario: 根构建验证模块清单
- **GIVEN** 设计冻结的模块清单和父子 POM
- **WHEN** 从根工程执行模块清单检查与 Maven `verify`
- **THEN** 构建只包含允许模块并成功，任何额外单接口模块或把 adapters 聚合目录实现为大模块都会使检查失败

### Requirement: 核心与 A2A 包边界
`opspilot-core` MUST 以 `domain/application/port/policy` 表达责任边界，`opspilot-a2a` MUST 以 `contract/client/server` 分隔协议、客户端和服务端代码。

#### Scenario: 包结构边界检查
- **GIVEN** core 与 A2A 模块源码
- **WHEN** 架构测试扫描包及类型依赖
- **THEN** 每类代码位于规定责任包内，跨边界放置或反向依赖被拒绝

### Requirement: 核心依赖纯净性
`opspilot-core` MUST NOT 导入 Spring、AgentScope、A2A SDK、JPA、Prometheus、Jaeger、Infinity 或任何厂商 SDK，架构测试 MUST 对每类禁用依赖提供可证明失败的规则。

#### Scenario: 注入禁用框架依赖
- **GIVEN** 一条指向任一禁用框架或厂商包的 core 测试依赖
- **WHEN** 执行架构测试
- **THEN** 测试明确报告非法导入并失败

### Requirement: 单向模块依赖与唯一装配入口
系统 MUST 固定 `core ← tools-default / agent-runtime / a2a / adapters / evaluation ← server` 依赖方向；Adapter 实现之间不得依赖，Adapter 不得反向调用 Server，专业 Agent 不得依赖 Supervisor Repository，且只有 `opspilot-server` 可承担 composition root。

#### Scenario: 检测跨模块非法调用
- **GIVEN** 一条 Adapter 间依赖、Adapter 到 Server 依赖、专业 Agent 到 Supervisor Repository 依赖或非 Server 装配代码
- **WHEN** 执行模块架构测试
- **THEN** 对应依赖被定位并使构建失败
