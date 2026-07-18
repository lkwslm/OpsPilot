# evidence-contract-chain Specification

## Purpose
TBD - Defines the Phase 0 contract and acceptance criteria for evidence-contract-chain.

## Requirements

### Requirement: 五类真实 Source Adapter
系统 MUST（必须）为 Prometheus、Jaeger、JSONL、Actuator 和 Compose 建立最小真实 Adapter 与稳定 descriptor，不得用 Mock/Fake 或厂商 DTO 作为领域事实。

#### Scenario: 五类 Adapter 读取真实响应
- **GIVEN** 五类 Source 的最小真实测试端点或数据源
- **WHEN** 分别调用每个 Adapter
- **THEN** 每个 Adapter 返回符合共享合同的 ObservationBatch 或明确技术结果，且 descriptor 稳定可追溯

### Requirement: 单 Source ObservationBatch 与 provenance
一次调用只能生成一个 Source 的 ObservationBatch，并 MUST（必须）保留 Resource、Source、query hash、原始 Artifact、内容哈希和 provenance。

#### Scenario: Evidence 反查原始记录
- **GIVEN** 一次成功 Source 调用产生的 ObservationBatch
- **WHEN** 将 Observation 规范化为 Evidence 并执行追溯
- **THEN** 每条 Evidence 都能反查 Batch、Record、Source、Resource 和带哈希 Artifact

### Requirement: 共享 Adapter 合同套件
五类 Adapter MUST（必须）通过同一套成功、合法空结果、超时、鉴权失败、Schema 无效、取消、脱敏和哈希错误测试。

#### Scenario: Adapter 共享失败语义
- **GIVEN** 任意一种 Source Adapter
- **WHEN** 依次触发共享套件的正常与失败输入
- **THEN** Adapter 对每类输入返回冻结的合同结果，不静默切换 Source 或伪造数据

### Requirement: Evidence 是唯一事实层
Observation MUST（必须）规范化为不可变 Evidence 后才能进入 Agent、RCA 和 Evaluation；这些下游组件不得接收 Prometheus、Jaeger、JSONL、Actuator 或 Compose 的厂商 DTO。

#### Scenario: 厂商 DTO 不能进入下游
- **GIVEN** 一个 Source 的原始厂商响应
- **WHEN** 尝试绕过 EvidenceNormalizer 传给 Agent、RCA 或 Evaluation
- **THEN** 编译期边界或合同测试拒绝该路径
