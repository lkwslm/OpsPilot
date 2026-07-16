# 阶段 07：Fault Lab、三个场景、RCA 与确定性评测

## 目标

实现可复现的故障实验链、三个冻结场景、Agent input/Ground Truth 隔离、结构化 RCA 与不依赖 LLM-as-a-Judge 的确定性 Evaluation。

## 前置门禁

- Sample、Source Adapter、真实多 Agent、RAG、Artifact 与产品 API/SSE 完整可用。
- `scenario.schema.json`、`ground-truth.schema.json`、`rca.schema.json`、`evaluation-profile.schema.json` 及示例保持冻结。

## 实施内容

1. 用 Python 3.11+ 实现 ScenarioLoader、EnvironmentController、HealthChecker、LoadGenerator、FaultInjector、ArtifactCollector、TicketGenerator、GroundTruthGenerator、DatasetWriter、ScenarioValidator。
2. 场景统一执行“重置 → 健康 → 基线 → 注入 → 持续负载 → 采集 → 恢复 → 导出 → 校验”，任何失败均在 `finally` 恢复环境。
3. 实现 `dependency-latency-inventory/1.0.0`：Toxiproxy 下游延迟 `3000ms ± 100ms`、注入 120 秒，在总计 240 秒的持续负载中严格校验基线、Trace 父子关系、故障成立/恢复条件、必选/辅助 Evidence 和禁止误判。
4. 实现 `database-pool-exhausted-order/1.0.0`：4 个受控长事务、90 秒持有、2 秒 connection timeout、故障注入窗 120 秒和总计 240 秒持续负载；不得停止共享 PostgreSQL。
5. 实现 `service-instance-stopped-inventory/1.0.0`：由 Fault Lab 停止原 inventory 容器 90 秒、不删卷，在总计 240 秒的持续负载中验证 readiness、连接失败、Prometheus target 和恢复。
6. 每次执行生成唯一 datasetRunId，记录 commit、Compose 摘要、镜像 digest、模型配置摘要、随机种子、UTC 三窗口和全部 Artifact SHA-256。
7. 输出固定数据集目录：`input/` 只读给 Agent，`ground-truth/` 只给 Fault Lab/Evaluation，`execution/` 保存执行日志与时间线且其路径和敏感内容均不挂载或暴露给 Agent。数据库与卷凭证同步隔离。
8. 由确定性规则生成 `source.type.fact` Evidence Code；必须同时匹配服务、时间窗和 predicate，不使用自然语言相似度或模型生成代码。
9. 实现结构化 RCA（多个 Hypothesis、支持/冲突 Evidence、验证、结论等级、rootCauseCode 可空、修复建议），再从同一对象渲染 JSON 和 Markdown。
10. 实现独立 Evaluation 进程与八类指标：RootCauseTop1Accuracy、EvidenceRecall、EvidencePrecision、ToolSelectionAccuracy、TaskCompletionRate、UnsafeActionRate、InvestigationEfficiency、CitationValidity。
11. 实现版本化 Evaluation Profile、单 Run 去重、macro average、报告 Artifact 和 `opspilot.evaluation_result` 写回；Evaluation 只能读 Ground Truth，Agent 永远不能读。

## 主要输出

- `fault-lab` 场景运行器、三个 Scenario YAML、Ground Truth、流量和采集器；
- 可复现的数据集、时间线、Artifact 清单和校验报告；
- RCA 领域输出、JSON/Markdown 渲染与引用验证；
- `opspilot-evaluation`、8 类指标、Profile 和 JSON/Markdown 评测报告；
- 三场景正常链路的 E2E 测试。

## 完成门禁

- 三场景分别满足第 25.3–25.5 节全部注入、基线、故障成立和恢复合同，数据集有效率 100%。
- Ground Truth 通过 Schema，Agent 角色/卷/API/日志/Prompt 无访问路径；越权测试失败关闭。
- 每条必选 Evidence 可追溯到 Source/Batch/Record/Artifact，并使用确定性 evidenceCode。
- RCA JSON/Markdown 同源、Schema 有效、引用属于当前 Run；证据不足时允许 `PARTIAL` 或 `INCONCLUSIVE/rootCause=null`。
- 八类指标严格按第 25.7 节公式计算，不使用 LLM 判断或文本相似度。
- 每次执行失败都恢复环境，不删除数据卷，不让 Agent 获得 Docker socket。

## 明确不做

- 本阶段完成场景与评测能力，但不宣称已通过发布所需的每场景 5 次聚合阈值；15 次发布运行属于阶段 08。
- 不增加第四场景或更改三个场景的参数、required Evidence、Root Cause Code 和阈值。

## 设计依据

- [Fault Lab 与 RCA/评测边界](../design/opspilot-system-design/02-modules-and-boundaries.md)
- [测试策略](../design/opspilot-system-design/09-test-strategy.md)
- [场景与确定性评测](../design/opspilot-system-design/14-scenarios-and-deterministic-evaluation.md)
