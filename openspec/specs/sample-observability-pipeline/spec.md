# Purpose

定义 Sample 服务到可观测后端的真实遥测管线以及 Jaeger v1/v2 并行导出语义。

## Requirements

### Requirement: 跨服务 Trace 上下文连续
三个 Sample 服务 MUST 通过 OpenTelemetry SDK 或 Agent 与 Collector 传播 HTTP、数据库和跨服务调用上下文，并为一次订单业务调用形成可关联的端到端 Trace；Trace MUST 可由已配置的 Jaeger v1、Jaeger v2 或两者分别查询，遥测后端类型及版本 MUST NOT 泄漏到 core 合同。

#### Scenario: 一次订单调用形成连续 Trace
- **GIVEN** Sample、OTel Collector 和 Jaeger 均健康
- **WHEN** 客户端通过网关完成一次涉及订单、库存和数据库的调用
- **THEN** 网关、两个业务服务和数据库 Span 共享连续上下文且可在 Jaeger 查询，core 中没有 Jaeger 或 OTel 客户端类型

#### Scenario: 双版本后端查询同一 Trace
- **GIVEN** Jaeger v1 与 v2 Source 均健康且 Collector 将同一 OTel Trace 导出到两个后端
- **WHEN** 系统显式分别查询两个 Source
- **THEN** 两个版本均可返回该 Trace 的 canonical Batch，Normalizer 只计算一个规范化事实并保留两个来源的 provenance

### Requirement: 指标和日志包含受控关联信息
Sample 服务 MUST 暴露 Prometheus HTTP、JVM 和 Hikari 指标，并输出结构化 JSON 日志；日志只可包含 allowlist 内的 `requestId/traceId/runId` 等关联字段，所有业务与遥测时间 MUST 使用 UTC。

#### Scenario: 日志指标与 Trace 关联
- **GIVEN** 一次带 `requestId` 和 `runId` 的业务请求已经完成
- **WHEN** 查询该时间窗的 JSONL、Prometheus 指标和 Jaeger Trace
- **THEN** 三类信号可通过允许的资源、时间和关联标识交叉定位，时间为 UTC 且日志中不包含凭证或未允许字段

### Requirement: 遥测后端具备受控运行配置
Compose MUST 为 Prometheus、Jaeger v1/v2 和 OTel Collector 配置内部网络、健康检查和明确保留期；依赖未健康、数据未在 deadline 内可见或导出失败 MUST 产生可诊断失败，MUST NOT 以构造记录填补。

#### Scenario: Collector 不健康
- **GIVEN** OTel Collector 启动失败或健康检查超时
- **WHEN** Compose 执行遥测纵切检查
- **THEN** 纵切失败并保存 Collector 诊断信息，不生成伪造 Trace 或把缺失遥测标记为成功
