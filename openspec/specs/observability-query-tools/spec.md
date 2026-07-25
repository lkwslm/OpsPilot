# Purpose

定义面向上层调用方的厂商无关可观测查询 Tool 合同、安全边界和稳定结果语义。

## Requirements

### Requirement: 六个可观测 Tool 使用能力型合同
系统 MUST 实现 `LogQueryTool`、`MetricQueryTool`、`TraceQueryTool`、`HealthQueryTool`、`TopologyQueryTool` 和 `ConfigReadTool`，每个 Tool MUST 提供版本化输入 Schema、受控 Resource/时间窗/模板参数、边界校验和稳定结果 DTO；Tool 名称和合同 MUST NOT 绑定 Prometheus、Jaeger、Spring 或其他厂商。

#### Scenario: 六类合法 Tool 请求
- **GIVEN** 当前 Run 已授权访问目标 Resource 且对应 Source READY
- **WHEN** 分别按六个 Tool 的输入 Schema 发起合法请求
- **THEN** 每个 Tool 只使用其能力型参数完成调用并返回符合合同的结果，调用方不需要知道具体 Adapter 产品

### Requirement: Tool 只编排 core Port 与 Normalizer
Tool MUST 通过 core Port 完成 Source 选择、Adapter 执行、Observation 接收和 Evidence 规范化，MUST NOT 直接依赖厂商 Client/DTO、Spring Actuator 响应类型或 Adapter 内部分页状态。core、Agent、RCA 和 Evaluation 同样 MUST NOT 导入这些厂商类型。

#### Scenario: 架构依赖扫描
- **GIVEN** 六个 Tool 和七类 Adapter 已加入构建
- **WHEN** 执行模块与包依赖架构测试
- **THEN** 厂商客户端和 DTO 只存在于允许的 Adapter 边界，Tool、core、Agent、RCA、Evaluation 的任何越界依赖都会使测试失败

### Requirement: Tool 结果有界且只暴露事实引用
Tool 结果 MUST 只包含有界摘要、`observationBatchIds`、`evidenceBundleId`、Evidence/Artifact 引用、明确缺失项及合同允许的元数据；MUST NOT 返回原始 Source 响应或厂商 DTO。后续 Diagnosis、Hypothesis、RCA 和 Evaluation MUST 依赖 Evidence ID，而不是直接依赖 ObservationBatch。

#### Scenario: Source 返回大体积响应
- **GIVEN** Adapter 成功读取超过 Tool 内联上限的日志、时序点或 Span 图
- **WHEN** Tool 完成 Observation 接收和规范化
- **THEN** 大正文位于已校验 Artifact，Tool 只返回有界摘要与 Evidence/Artifact 引用且不泄漏原始响应

### Requirement: Tool 状态稳定区分成功、空、拒绝和失败
六个 Tool 的结果状态 MUST 限定为 `SUCCEEDED/EMPTY/DENIED/FAILED`。合法有事实结果为 `SUCCEEDED`，Source 成功但无记录为 `EMPTY`，授权或策略拒绝为 `DENIED`，Source 技术失败、无效 Batch 或 Normalizer 失败为 `FAILED`；各路径 MUST 保留审计且不得互相伪装。

#### Scenario: 未授权查询被前置短路
- **GIVEN** 调用方无权访问请求中的 Target 或 Resource
- **WHEN** Tool 执行安全中间件
- **THEN** 结果为 `DENIED`，Adapter 未被调用，不创建 Observation/Evidence 且拒绝审计完整

#### Scenario: 查询成功但没有记录
- **GIVEN** Source 调用成功并返回合法空 Batch
- **WHEN** Tool 完成规范化流程
- **THEN** 结果为 `EMPTY` 而非 `FAILED`，保留 Batch 和查询审计且不伪造 Evidence

### Requirement: Tool 拒绝任意查询与越权引用
Tool 输入 MUST 按 JSON Schema 和 allowlist 拒绝任意 PromQL、URL、SQL、厂商 DSL、未知字段、超大时间窗、跨 Target Resource 及无权 Artifact；校验 MUST 在外部 Source 调用前完成。

#### Scenario: 指标 Tool 携带任意 PromQL
- **GIVEN** 请求符合基本 JSON 结构但包含模型生成的 PromQL 或未登记模板
- **WHEN** `MetricQueryTool` 执行输入与策略校验
- **THEN** 请求被稳定拒绝，Prometheus Adapter 不被调用，审计不记录敏感查询正文
