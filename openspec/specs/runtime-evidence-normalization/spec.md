# Purpose

定义运行时 Observation 到不可变 Evidence 的校验、Artifact、质量和 provenance 规则。

## Requirements

### Requirement: Observation 接收边界执行完整校验
系统 MUST 在 Observation 可用于规范化前校验机器 Schema、Source READY、Adapter ID/version、Target/Run/Resource 归属、查询时间窗、UTC/时钟偏移、freshness/completeness/sampling/truncation、脱敏和长度限制。任何关键校验失败 MUST 拒绝整个 Batch 并返回 `OBSERVATION_BATCH_INVALID`，除非对应 Adapter 合同显式允许部分结果。

#### Scenario: Batch 引用跨 Target Resource
- **GIVEN** Batch 的 Source 属于当前 Target，但一条 Record 引用另一个 Target 的 Resource
- **WHEN** Observation 接收流水线验证归属
- **THEN** Batch 被拒绝并留下失败审计，不创建可进入分析层的 Evidence

### Requirement: 大响应先形成受控 Artifact
超过内联上限的原始响应、全量时序点或 Span 图 MUST 先通过 `ArtifactAccessService` 写入受控 Artifact；Observation 保存引用前 MUST 校验调用方访问权限、Run/Task 归属、媒体类型、大小和 SHA-256。哈希漂移、越权或不可读 Artifact MUST 阻止事实生成。

#### Scenario: Artifact 内容遭篡改
- **GIVEN** Observation 引用的 Artifact 元数据 SHA-256 与实际对象内容不一致
- **WHEN** 接收流水线准备规范化该 Observation
- **THEN** 系统拒绝 Batch 或对应合同允许的 Record，记录完整性失败且不生成 Evidence

### Requirement: 无法形成事实的 Observation 只保留审计
未通过事实质量、归属、来源或完整性门禁的 Observation MAY 按审计策略持久化其安全摘要与失败原因，但 MUST NOT 出现在 EvidenceBundle、Hypothesis、RCA 或 Evaluation 的事实引用中。

#### Scenario: 过期且不完整的 Observation
- **GIVEN** 一个 Schema 合法但超出 Incident window、严重不完整且无法支持事实的 Observation
- **WHEN** Normalizer 评估其质量
- **THEN** Observation 可作为审计记录保存，EvidenceBundle 不包含对应 Evidence 且原因可追溯

### Requirement: Evidence 与 provenance 不可变且可全链路回溯
`EvidenceNormalizer` MUST 将有效运行 Observation 转换为不可变 Evidence，并创建 `provenanceRefs(kind=RUNTIME_OBSERVATION)`；每条 Evidence MUST 可回溯到 Batch、Record、Source、Adapter version、Resource、查询哈希和有效 Artifact。已生成 Evidence MUST NOT 因 Source 配置或拓扑后续变化被覆盖。

#### Scenario: Source 配置更新后回查历史 Evidence
- **GIVEN** Evidence 已由某 capability snapshot、Source 配置和拓扑版本生成
- **WHEN** Source 实例配置或当前拓扑随后更新
- **THEN** 历史 Evidence 仍解析到原 Batch、Record、Source、Artifact 和拓扑版本，内容与 provenance 不变

### Requirement: 同源与跨源派生重复不得重复计数
Normalizer MUST 使用 OTel 原始 Trace/Span/metric/log 身份、派生关系和来源信息执行同源去重与跨源相关；同一 OTel 数据经 Prometheus 和 Jaeger 二次导出 MUST NOT 被计为两个独立事实，但独立 Source 的交叉验证 MUST 保留各自 provenance。

#### Scenario: 同一 OTel 事件经两个后端出现
- **GIVEN** Prometheus 与 Jaeger 的两个 Batch 可追溯到同一 OTel 原始事件或派生链
- **WHEN** Normalizer 聚合两个 Batch
- **THEN** EvidenceBundle 只计算一个规范化事实并保留两个来源引用，不提高独立证据计数
