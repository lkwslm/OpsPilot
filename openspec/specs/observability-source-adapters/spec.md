# Purpose

定义厂商可观测 Source 到 canonical Observation 的统一 Adapter 边界和稳定失败语义。

## Requirements

### Requirement: 首期七类 Adapter 提供统一能力
系统 MUST 实现 `PrometheusMetricAdapter`、长期并行的 `JaegerV1TraceAdapter`/`JaegerV2TraceAdapter`、`JsonlLogAdapter`、`SpringActuatorHealthAdapter`、`StaticComposeTopologyAdapter`、独立 `HttpHealthAdapter` 和受控 Spring/文件配置 Adapter，并让它们通过同一 Source Adapter 共享合同；Adapter MUST 只返回 canonical Observation 或稳定失败，不得向 core 暴露厂商 DTO、Client 或异常。

#### Scenario: 七类 Adapter 执行共享成功合同
- **GIVEN** 每类 Adapter 都连接到对应的真实或合同允许的固定响应 Source
- **WHEN** 共享合同套件执行合法查询
- **THEN** 每个 Adapter 返回符合 `observation-batch.schema.json` 的领域结果，core 不需要识别 Prometheus、Jaeger 或 Spring 类型

### Requirement: Jaeger v1 与 v2 长期并行兼容
系统 MUST 将 Jaeger v1 与 v2 作为 Trace 能力下长期并行支持的版本 Adapter，而不是迁移前后的替换关系。两者 MUST 使用独立稳定 Adapter ID/version、`connectionRef`、查询客户端和健康探针，并 MUST 共享版本化查询模板、canonical Mapper、`ObservationBatch` 和稳定失败合同。引入或启用 v2 MUST NOT 退役、覆盖或隐式替换 v1。

#### Scenario: 单独配置任一 Jaeger 版本
- **GIVEN** 当前环境只配置一个 READY 的 Jaeger v1 或 Jaeger v2 Source
- **WHEN** 使用同一 Trace 查询模板执行合法查询
- **THEN** 对应版本 Adapter 独立返回符合相同 canonical 合同的 Batch，Tool 和 core 无需感知 Jaeger API 版本

#### Scenario: 显式并行查询 Jaeger v1 与 v2
- **GIVEN** 同一 Resource/Signal 同时配置 READY 的 Jaeger v1 与 v2 Source，并声明合法多 Source 角色
- **WHEN** 请求显式要求同时查询两个 Source
- **THEN** 两个 Adapter 分别执行并生成独立 Batch，各自 Adapter ID/version、Source 和 provenance 完整；任一版本失败不会触发向另一版本的静默 failover

### Requirement: 查询只能使用版本化模板和有界参数
Adapter MUST 只接受 Resource、信号类型、时间窗和 `queryTemplateId + typed parameters`，并在内部把受控模板映射为厂商查询；调用方和模型 MUST NOT 提交任意 PromQL、URL、SQL 或厂商 DSL。配置 Adapter MUST 额外限制 allowlist key、响应大小、层级和敏感字段。

#### Scenario: 提交任意 PromQL 或 URL
- **GIVEN** 调用请求包含未登记的 PromQL、任意 URL 或模板之外的参数
- **WHEN** Metric、Health 或 Config Adapter 校验查询
- **THEN** 请求在网络调用前被拒绝并记录稳定错误，不执行用户提供的表达式或地址

#### Scenario: 配置快照请求包含非 allowlist key
- **GIVEN** Config Adapter 的请求混有允许与未允许的配置 key
- **WHEN** Adapter 生成配置快照
- **THEN** 未允许或敏感 key 不会进入 Observation/Artifact，违规请求按合同被拒绝且响应保持有界

### Requirement: 一次调用只产生一个 Source 的 ObservationBatch
每次 Adapter 调用 MUST 生成一个确定 Source 的 `ObservationBatch`，Batch source MUST 记录正确的 `sourceKind/sourceId/adapterVersion/connectionRef/scope/query parameterHash/timeWindow`；独立 Source 调用 MUST NOT 被拼成同一 Batch。

#### Scenario: 分别查询 Prometheus 与 Jaeger
- **GIVEN** 同一 Resource 和时间窗需要指标与 Trace
- **WHEN** 系统分别调用 Prometheus 与 Jaeger Adapter
- **THEN** 产生两个独立 Batch，各自来源和查询哈希正确，多源聚合只在 Normalizer/EvidenceBundle 中发生

### Requirement: 联邦记录保留实际 originSource
联邦或 Cloud Source 的 Batch source MUST 表示联邦入口，每条来自底层系统的 Record MUST 填写实际 `originSource`；非联邦记录 MAY 继承 Batch source。无法确定实际来源的联邦 Record MUST 被拒绝。

#### Scenario: 联邦响应缺少实际来源
- **GIVEN** 联邦入口返回一条无法解析 `originSource` 的 Record
- **WHEN** Adapter 构造或校验 ObservationBatch
- **THEN** 对应 Record 不得以联邦入口冒充实际来源，默认拒绝整个 Batch并返回 `OBSERVATION_BATCH_INVALID`

### Requirement: 空结果与技术失败严格区分
只有 Source 调用成功且无 Record 时，Adapter MUST 返回合法空 Batch并使 Tool 得到 `EMPTY`。Source 未配置 MUST 返回 `SOURCE_NOT_CONFIGURED`；不健康、超时、鉴权、限流耗尽和取消等技术失败 MUST 生成关联 `ChainFailure`；Schema、来源或 Artifact 无效 MUST 返回 `OBSERVATION_BATCH_INVALID`，均 MUST NOT 伪装为空结果或触发静默 failover。

#### Scenario: 成功但没有记录
- **GIVEN** Source READY、鉴权成功且查询正常完成但时间窗内没有数据
- **WHEN** Adapter 返回结果
- **THEN** 返回 observations 为空的合法 Batch，调用状态为 `EMPTY` 且保留查询与来源审计

#### Scenario: Source 超时或鉴权失败
- **GIVEN** 查询命中超时或 Source 拒绝凭证
- **WHEN** Adapter 结束调用
- **THEN** 返回关联 `ChainFailure` 而不是空 Batch，不自动调用其他 Source

### Requirement: 共享合同覆盖有界和部分结果语义
每个首期 Adapter MUST 通过真实成功、合法空、分页/限流、超时、鉴权失败、无效 Schema、取消、脱敏、Artifact 哈希和来源追溯测试。默认任一无效 Record MUST 拒绝整个 Batch；只有 Adapter 合同显式允许部分结果时，才 MUST 记录 rejected count 与原因并设置 `complete=false`。

#### Scenario: 合同允许的部分结果
- **GIVEN** Adapter 合同明确允许部分结果且响应中同时含有效和无效 Record
- **WHEN** Adapter 完成校验
- **THEN** 仅有效 Record 进入 Batch，质量信息记录 rejected count、原因及 `complete=false`，无效内容不能形成 Evidence
