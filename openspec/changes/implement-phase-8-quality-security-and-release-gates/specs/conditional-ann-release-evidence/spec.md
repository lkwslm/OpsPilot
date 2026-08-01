## Purpose

该能力用于把 ANN 评估限制在精确检索确实未达目标的条件分支，同时验证语言无关诊断与 Adapter 扩展边界，并生成可由发布流程消费的完整证据索引。

## ADDED Requirements

### Requirement: ANN 只能由精确检索 SLO 失败触发

系统 SHALL 先在冻结机器、同一知识 revision、模型 revision、collection、`searchable=true` 过滤条件、黄金查询和负载下完成精确检索测试。仅当有效测试的精确 candidate recall p95 不满足 `<300ms` 时，ANN 分支才可执行；否则 MUST 记录“保持 MVP 精确检索”的 `NOT_APPLICABLE` 决策且不得创建或保留 ANN 索引。

#### Scenario: 精确检索满足 SLO
- **GIVEN** 有效固定负载下精确 candidate recall p95 小于 300ms
- **WHEN** ANN 决策器评估触发条件
- **THEN** HNSW/IVFFlat 基准不运行，决策证据记录输入 digest、p95 与保持精确检索结论

#### Scenario: 精确检索未满足 SLO
- **GIVEN** 有效固定负载下精确 candidate recall p95 大于或等于 300ms
- **WHEN** ANN 决策器评估触发条件
- **THEN** 系统允许在完全相同 revision、过滤条件与查询集下比较 HNSW 和 IVFFlat，并保留精确检索基线

### Requirement: ANN 对照必须同时验证质量、成本和生命周期

若触发 ANN，系统 SHALL 同时记录 Recall@K、p95、索引大小、构建时间、WAL、写吞吐和内存，并使用 `searchable=true + model revision + collection` 的真实过滤条件验证候选完整性。系统 MUST 对每个候选索引演练创建、切换、删除和回滚，且索引选择不得以降低 Recall@K 或过滤正确性换取延迟。

#### Scenario: ANN 延迟达标但候选不完整
- **GIVEN** 某 ANN 配置满足延迟目标但遗漏符合真实过滤条件的黄金候选
- **WHEN** 对照基准执行质量与候选完整性判定
- **THEN** 该配置被拒绝，索引回滚并删除，报告保留 Recall@K 与过滤差异

#### Scenario: 索引切换后回滚
- **GIVEN** 候选 ANN 配置已在测试 revision 上完成切换
- **WHEN** 回滚演练触发
- **THEN** 查询恢复到原精确或先前索引策略、结果与 revision 一致，临时索引可安全删除且写入链无残留错误

### Requirement: 语言能力与 Source Adapter 必须保持可移植性边界

系统 SHALL 在禁用 Java Code Analyzer 和 Maven Sandbox Adapter 时运行真实诊断，报告 MUST 明确缺少代码级证据且不得产生代码级结论。新增 Loki/Tempo 测试 Adapter 时，core、四层状态机、Evidence/RCA 表结构和 A2A skill major version MUST 保持不变；只允许通过既有 Port、Registry 和规范化合同接入。

#### Scenario: 禁用代码能力
- **GIVEN** Code Analyzer 与 Maven Sandbox Adapter 均未注册
- **WHEN** 三类场景中的调查运行到报告阶段
- **THEN** 系统仍可产生带现场证据和 limitations 的受限报告，且报告中没有代码文件、代码行或补丁级结论

#### Scenario: 接入 Loki 与 Tempo 测试 Adapter
- **GIVEN** 新 Adapter 只实现既有 Source Port 与规范化输出
- **WHEN** 合同差异与真实 Adapter 测试运行
- **THEN** core API、状态转换、Evidence/RCA schema/表和 A2A skill major version 无变化，新增 Source 仍携带真实 `originSource`

### Requirement: Release Manifest 必须引用完整独立测试证据

系统 SHALL 为合同、单元、PostgreSQL 集成、Compose smoke、真实模型/A2A、15 Run Evaluation、空结果、失败恢复、安全、可观测效率、性能及可移植性生成独立报告；每份报告 MUST 记录 commit、Workflow/run identity、套件版本、开始/结束时间、通过/失败/跳过明细、URI 与 SHA-256，不得用单元测试冒充 E2E。Release Manifest SHALL 通过冻结 Schema 校验并只在所有必需门禁 `PASSED` 时标记 `READY_FOR_MANUAL_DEPLOYMENT`。

#### Scenario: 必需 Runner 或 Secret 缺失
- **GIVEN** 某必需真实模型、Runner、Secret 或测试载体不可用
- **WHEN** Release Manifest 汇总门禁
- **THEN** 对应门禁与 deliveryStatus 为 `BLOCKED`，报告说明缺失前置，不得写为 `PASSED`、`NOT_APPLICABLE` 或省略

#### Scenario: 独立层报告发生失败
- **GIVEN** 任一质量、效率、硬安全、性能或证据完整性报告为失败
- **WHEN** Release Manifest 生成器汇总证据
- **THEN** deliveryStatus 为 `FAILED`，Manifest 保留失败报告 URI/SHA-256 且不创建可领取发布结论

#### Scenario: 所有发布门禁通过
- **GIVEN** 所有必需独立报告通过、条件 ANN 有合法 `PASSED` 或 `NOT_APPLICABLE` 决策，且引用 digest 可复算
- **WHEN** 生成器构建 Release Manifest
- **THEN** Manifest 符合 `release-manifest.schema.json`、指向精确 commit 与不可变证据，并可供持续交付流程消费但不自动部署
