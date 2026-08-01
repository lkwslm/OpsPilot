## Why

阶段 07 已建立三个冻结故障场景、可复现数据集、结构化 RCA 与独立确定性 Evaluation，但尚未证明真实多 Agent/RAG 链在重复运行、合法空结果、依赖故障、重启并发、安全攻击和固定负载下满足发布标准。阶段 08 必须把这些检查固化为不可临时降级、可由持续交付消费且可追溯到原始 Artifact 的硬门禁，才能形成可信的发布结论。

## What Changes

- 冻结 commit、temperature、六角色 Prompt/模型、知识 revision、Scenario、Evaluation Profile、Token budget 和开发机身份，并建立不可变的发布 Run ledger。
- 编排三个场景各 5 次、共 15 次相互独立的真实质量 Run，锁定原始 RCA/Evaluation/调用/Token/时长证据，按总体及单场景阈值聚合且禁止改变分母。
- 建立 `KB_EMPTY`、真实 `NO_MATCH` 和零历史案例矩阵，证明正常空结果仍可形成有证据、有明确限制且不伪造引用的结论。
- 建立能力×故障×关键性技术失败矩阵，覆盖 Provider、KnowledgeAgent、A2A endpoint、Tool/Source 的不可用、超时、鉴权和 Schema 错误，并严格区分终止失败与允许缺失。
- 建立 Agent/Task/step/Run 状态、CAS、单活动 Run、租约、取消、重启、Task 对账、Artifact 校验、outbox 及依赖恢复矩阵，确保可选 projector 失败不回滚已提交诊断状态。
- 把 Ground Truth、文件/Artifact、沙箱/Shell、Prompt injection、SSRF/allowlist、A2A、跨域授权、Source provenance、Registry、中间件、权威表和厂商 DTO 等攻击面逐项登记并执行负向测试与泄密扫描。
- 复核结构化日志、Trace、低基数指标、关联 ID、Dashboard/告警数据以及 Token/成本账本；逐 Run 分维度执行 InvestigationEfficiency 门禁，不合成为单一分数。
- 在固定开发机、50,000 active Chunk 和 20 检索 QPS 下验证 API、SSE、精确召回、完整 RAG、端到端、重启恢复和资源稳定性。
- 仅当精确检索未达 SLO 时，在同一 revision 与真实过滤条件下比较 HNSW/IVFFlat；同时验证禁用代码能力与新增 Loki/Tempo 测试 Adapter 时的可移植性不变量。
- 为每层测试生成独立、带 commit、Workflow/run identity、套件版本、时间、结果、URI 和 SHA-256 的报告，汇总为 Release Manifest；任何硬失败或环境缺失分别输出 `FAILED` 或 `BLOCKED`，不得伪装通过。
- 不降低 Evaluation Profile 阈值，不减少 required Evidence，不关闭 Rerank，不使用 Mock/vector-only/关键词/固定结果替代真实链路，也不把本阶段开发机性能阈值解释为生产 SLO。

## Capabilities

### New Capabilities

- `release-evaluation-baseline`：冻结发布配置、Token budget、环境身份与 Run ledger，并阻止基线漂移或未就绪环境进入正式质量运行。
- `repeated-quality-release-gate`：编排并聚合三场景各 5 次独立真实质量 Run，执行总体、单场景和硬失败发布判定。
- `bounded-empty-outcome-assurance`：验证空知识库、无匹配和无历史案例的正常业务语义、受限 RCA 与引用真实性。
- `capability-failure-release-matrix`：按能力关键性验证 Provider、A2A、Tool/Source 技术失败的重试、终止、缺失证据与质量分母隔离。
- `recovery-concurrency-release-matrix`：验证四层状态、CAS、租约、取消、重启、对账、Artifact 与 outbox 的并发恢复不变量。
- `security-release-gate`：执行完整攻击目录、授权与来源边界负向测试、危险动作审计和多载体敏感信息扫描。
- `observability-efficiency-release-gate`：复核遥测关联、低基数指标、Token/成本账本，并逐维执行调查效率预算。
- `performance-resource-release-gate`：在固定数据规模、负载与硬件身份下执行延迟、吞吐、恢复和资源稳定性门禁。
- `conditional-ann-release-evidence`：以精确检索 SLO 作为 ANN 唯一触发条件，验证检索索引生命周期、可移植性不变量并生成可消费 Release Manifest。

### Modified Capabilities

无。本变更新增阶段 08 的发布验证与证据能力，不改变阶段 00～07 已冻结的产品、场景、RCA、Evaluation 或 A2A 运行合同。

## Impact

- 新增阶段 08 的 release evaluation 配置/Profile、运行编排与账本、质量聚合、空结果/失败/恢复/安全测试目录、性能 harness、资源采集器、条件 ANN 基准与发布证据索引。
- 测试触及 `fault-lab`、六个 Agent 服务、`opspilot-evaluation`、产品 REST/SSE、Knowledge/Embedding/Rerank、Artifact、PostgreSQL、Prometheus、Jaeger、Toxiproxy 和各 Observability Source Adapter，但不扩大其生产权限。
- 依赖阶段 07 的三个有效场景、真实多 Agent/RAG 链、封账 RCA、独立 Evaluation 和冻结 machine contract；任何前置能力、Secret、Runner、真实模型或固定环境缺失时对应发布门禁保持 `BLOCKED`。
- 证据输出位于 `outputs/phase8/08-WPxx/`，最终由 Release Manifest 引用各层独立报告、原始样本与 SHA-256；专门失败注入和安全负向 Run 与 15 次正常质量聚合物理、逻辑隔离。
