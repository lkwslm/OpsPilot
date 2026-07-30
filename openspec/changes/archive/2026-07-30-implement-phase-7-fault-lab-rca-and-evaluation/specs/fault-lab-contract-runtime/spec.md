## Purpose

定义 Fault Lab 对冻结场景、Ground Truth、RCA 与 Evaluation Profile 合同的严格加载边界，以及所有场景组件可替换但不可绕过的显式能力注册规则。

## ADDED Requirements

### Requirement: 四类冻结合同严格加载
Fault Lab MUST 按仓库冻结的 `scenario.schema.json`、`ground-truth.schema.json`、`rca.schema.json` 和 `evaluation-profile.schema.json` 校验输入，MUST 拒绝未知字段、未知 major version、非法时间窗、缺失 required source/evidence 及未登记的 `scenarioId/scenarioVersion`，且 MUST NOT 猜测默认值后继续执行。

#### Scenario: 未知版本 fail closed
- **GIVEN** 一个 Schema 结构合法但 `scenarioVersion` 未登记的场景文件
- **WHEN** Fault Lab 加载该场景
- **THEN** 加载在环境重置和故障注入前以稳定合同错误失败，且不产生数据集或外部副作用

### Requirement: 场景组件通过显式 Registry 装配
ScenarioLoader、EnvironmentController、HealthChecker、LoadGenerator、FaultInjector、ArtifactCollector、TicketGenerator、GroundTruthGenerator、DatasetWriter 和 ScenarioValidator MUST 通过窄接口及显式 Registry 按场景能力装配；缺少、重复或类型不匹配的注册 MUST 阻止执行。

#### Scenario: 注入器能力未注册
- **GIVEN** 场景声明 `TOXIPROXY_LATENCY` 但 Registry 中没有唯一匹配的 FaultInjector
- **WHEN** runner 解析执行计划
- **THEN** runner 在任何负载或故障副作用发生前失败，并报告缺失的能力键
