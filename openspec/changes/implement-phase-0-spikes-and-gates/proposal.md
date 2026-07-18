## 背景

OpsPilot 在进入完整功能开发前，必须先消除框架、协议、真实模型、检索模型、数据库权限及机器合同上的关键不确定性。当前设计已经冻结 Phase 0 的工作包与门禁，但仓库尚无可由 OpenSpec 跟踪、验证和逐项实施的变更计划，因此需要把阶段计划转换为规范、设计和任务清单。

## 变更内容

- 建立 JDK 21/Maven 多模块骨架、不可变版本清单、依赖方向检查和供应链证据。
- 建立 OpenAPI 3.1、JSON Schema Draft 2020-12、Profile、示例及负向 fixture 的持续合同校验链。
- 用自动化 Spike 验证 AgentScope Java 的 Adapter 隔离、结构化输出、持久状态、恢复和有界终止语义。
- 对默认及六角色逻辑 LLM Profile 运行真实 Provider 能力门禁并输出脱敏验收记录。
- 验证 A2A 1.0.1 的跨进程 HTTP+JSON 互操作、持久 Task、幂等、取消、恢复、Artifact 校验和权限隔离。
- 验证同一 Infinity 实例中的真实 Embedding/Rerank 模型身份、质量、资源、并发与延迟门禁。
- 建立最小 PostgreSQL/pgvector、Flyway、数据库角色及单 Incident 单活动 Run 约束。
- 建立五类 Source Adapter 到 ObservationBatch、Artifact、Evidence 的最小可追溯合同链。
- 按冻结拓扑建立一个 Supervisor/产品进程和五个专业 Agent 进程的 Compose 骨架与启动门禁。
- 打通无 Mock/Fake 的最小 `Incident → Evidence → RCA → Evaluation` 真实纵切，并生成 CI 证据和 Release Manifest。
- 不在本变更中补齐全部业务表、九个 Tool、完整六 Agent 行为、三场景 E2E 或任何后续阶段能力；若 Spike 迫使冻结边界变化，工作保持 `BLOCKED`，先提交 ADR 和设计修订。

## 能力

### 新增能力

- `phase0-project-foundation`：仓库骨架、模块边界、不可变版本锁和供应链证据。
- `machine-contract-validation`：机器合同、Profile、示例、负向 fixture 和 CI 报告的持续校验。
- `agentscope-runtime-gate`：AgentScope Adapter、结构化调用、持久状态、恢复及有界终止门禁。
- `chat-model-capability-gate`：默认及六角色逻辑 Chat 模型的真实能力和配额门禁。
- `a2a-interoperability-gate`：A2A 1.0.1 跨进程互操作、持久任务、幂等和 Artifact 安全门禁。
- `retrieval-model-gate`：Infinity、Embedding、Rerank 的身份、质量、性能和资源选型门禁。
- `postgres-boundary-gate`：最小 Flyway、pgvector、角色权限、升级和并发约束门禁。
- `evidence-contract-chain`：五类 Source Adapter 到 ObservationBatch、Artifact 和 Evidence 的可追溯合同链。
- `six-process-compose-topology`：冻结的六进程 Compose 拓扑、身份隔离、健康检查和启动资格门禁。
- `phase0-vertical-slice`：最小真实纵切、故障透明性、CI 检查和阶段证据。

### 修改能力

无。仓库当前没有已归档的 OpenSpec 基线能力，本变更只新增 Phase 0 能力规范。

## 影响范围

- 代码与构建：根 Maven 工程、设计冻结的物理模块、测试与架构规则。
- 配置与部署：`deployment/versions.lock.yaml`、Dockerfile、Compose、Agent Directory、模型与角色配置。
- 合同与数据：`docs/design/contracts/` 校验入口、最小 Flyway、PostgreSQL/pgvector 和版本化证据 JSON。
- 运行集成：AgentScope Java、A2A Java SDK、真实 Chat/Embedding/Rerank Provider、Prometheus、Jaeger、JSONL、Actuator 与 Compose Adapter。
- CI：`.github/workflows/contracts.yml`、`build-test.yml`、`security.yml`；工作流只校验和产出证据，不部署环境。
- 外部条件：准确框架/SDK 版本、真实 Provider 凭证和配额、不可变模型 revision/镜像 digest、目标开发机资源与基准结果必须由 Spike 闭环，不能在规划文档中猜测。License 尽力解析并保留覆盖率；无法识别时记录警告，不阻塞 Phase 0 应用跑通。
