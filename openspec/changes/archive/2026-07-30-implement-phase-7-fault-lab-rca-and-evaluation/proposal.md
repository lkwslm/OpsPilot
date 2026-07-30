## Why

阶段 06 已具备真实多 Agent、RAG、Artifact、RCA 封账基础和产品 API/SSE，但尚缺少可重复注入、可验证恢复、Agent/Ground Truth 隔离及不依赖模型裁判的质量闭环。阶段 07 需要把三个冻结故障场景落实为可复现数据集，并以同一封账事实生成结构化 RCA 和确定性 Evaluation，才能为阶段 08 的发布聚合门禁提供可信输入。

## What Changes

- 建立 Python 3.11+ `fault-lab` 工程、严格合同加载器、显式组件 Registry，以及“重置 → 健康 → 基线 → 注入 → 持续负载 → 采集 → 恢复 → 导出 → 校验”的可恢复状态机。
- 为每次执行生成唯一 `datasetRunId`、固定三时间窗、环境与 Artifact 摘要，并以 `input/`、`ground-truth/`、`execution/` 固定目录和独立身份实现 Agent/Ground Truth/执行信息隔离。
- 实现 `dependency-latency-inventory/1.0.0`、`database-pool-exhausted-order/1.0.0`、`service-instance-stopped-inventory/1.0.0` 三个冻结场景的注入、负载、采集、恢复与有效性校验。
- 由冻结规则生成 `source.type.fact` Evidence Code 和 Ground Truth；匹配必须同时满足 Source、Resource、服务、时间窗与 predicate，不调用模型、不做自然语言相似度匹配。
- 扩展已封账 Run 的 RCA 合同：直接读取当前 `runId + runVersion` 的全部分析事实，支持多个 Hypothesis、支持/冲突 Evidence、验证结果、受限结论、可空 `rootCauseCode`、修复建议和 `missingEvidence`，再从同一 Schema 有效对象渲染 JSON/Markdown。
- 建立独立 `opspilot-evaluation` 进程，以只读 Ground Truth 身份严格计算八类指标，实现版本化 Profile、单 Run 去重、macro average、报告 Artifact 和 `opspilot.evaluation_result` 写回。
- 验收覆盖四类冻结 Schema、逐阶段失败与 `finally` 恢复、三场景三时间窗、目录/卷/凭证/Docker socket 越权、RCA 封账一致性、指标黄金样例及 Fault Lab → Agent → RCA → Evaluation 正常链路。
- 不增加第四场景，不修改冻结场景参数、Evidence/Root Cause Code 或阈值，不让 Agent 接触 Ground Truth/execution/Docker 控制面，不建立重复 RCA 输入快照，不使用 LLM-as-a-Judge，也不宣称完成阶段 08 的 15 次发布运行。

## Capabilities

### New Capabilities

- `fault-lab-contract-runtime`：Fault Lab 工程、四类冻结 machine contract 的严格加载/版本校验、窄组件接口和显式 Registry。
- `recoverable-scenario-execution`：统一场景阶段状态机、checkpoint/UTC 时间线、取消与失败时的强制恢复、双错误记录和 Docker 控制权隔离。
- `reproducible-isolated-dataset`：唯一 datasetRunId、三时间窗、环境/Artifact digest、固定目录、只读挂载、凭证隔离与数据集一致性校验。
- `dependency-latency-scenario`：库存依赖延迟场景的冻结 Toxiproxy 参数、负载、Trace 拓扑、Evidence、禁止误判和恢复合同。
- `database-pool-exhaustion-scenario`：订单连接池耗尽场景的冻结长事务/池/超时参数、Evidence、共享 PostgreSQL 保护和恢复合同。
- `service-instance-stop-scenario`：库存实例停止场景的冻结容器控制、卷保留、健康/业务/Prometheus Evidence 和原实例恢复合同。
- `deterministic-ground-truth`：Evidence Code 精确规则、Ground Truth 生成/校验、required/auxiliary/forbidden 集合及写后只读边界。
- `deterministic-evaluation`：独立 Evaluation 身份、八类指标公式、版本化 Profile、单 Run 去重、macro average、报告与结果写回。

### Modified Capabilities

- `supervisor-evidence-orchestration`：把阶段 06 的封账与唯一 RCA 基础扩展为完整 `rca.schema.json` 领域对象、当前 Run 引用校验、受限结论、`runId + runVersion` 元数据和 JSON/Markdown 同源渲染合同。

## Impact

- 新增 `fault-lab/` Python 工程、场景/负载/采集/恢复 Adapter、数据集 writer/validator，以及三个冻结 Scenario/Ground Truth fixture；调整 Compose 的挂载、凭证和 Fault Lab 独占 Docker 控制配置。
- 将阶段 00 的 `opspilot-evaluation` 占位 Maven 模块扩展为正式实现，新增指标计算器、Profile loader、报告 renderer/writer 和独立进程装配；扩展部署拓扑、数据库角色与 `opspilot.evaluation_result` migration。
- 扩展 `opspilot-core`、`opspilot-adapters/persistence-postgres` 和 RCA 渲染边界，但不创建 `RcaGenerationSnapshot` 或第二套报告输入/生成路径。
- 依赖阶段 01 合同校验链、阶段 03 Artifact、阶段 04 Sample/遥测/受控故障入口、阶段 05 真实 RAG 和阶段 06 Agent/A2A/产品 API；任一前置能力不完整时，真实 E2E 门禁保持阻塞。
- 证据输出落在 `outputs/phase7/07-WPxx/`，包含 Schema/规则覆盖、三场景 validator 与恢复报告、隔离负向测试、RCA 一致性、指标复算和三场景正常链路关联 Artifact。
