# 阶段 01：Phase 0——合同、依赖与真实能力门禁

## 目标

在完整功能开发前消除关键技术不确定性，锁定依赖和镜像，验证 AgentScope、A2A、真实模型、机器合同、最小数据库边界与一条真实纵切。这个阶段只允许骨架和 Spike；其输出必须是可自动验证的最小实现，不能以临时代码冒充后续完整模块。

## 前置条件

- 以 `docs/design/contracts/` 的 OpenAPI 与 JSON Schema 为最高优先级机器事实源；Profile 和示例是必须通过对应 Schema 校验的版本化实例，不得覆盖第 23.1 节中优先级更高的状态矩阵、不变量或正文边界。
- 对设计第 22.3 节和各章“待确认”项建立清单；本阶段只闭环阻止编码的版本、模型和框架 API 项。

## 实施内容

1. 建立 JDK 21/Maven 多模块最小骨架，模块名直接采用第 28.3 节，不拆出额外通用 Extension Host。
2. 创建 `deployment/versions.lock.yaml`，锁定 JDK 21、Maven、Spring Boot、AgentScope Java、A2A Java SDK、PostgreSQL/pgvector、Infinity 和其他验收基础镜像 digest，以及 Embedding/Rerank 的不可变模型 revision。禁止 `latest`、浮动 minor、占位符和只有 tag 没有 digest 的基础设施镜像。
3. 使用 OpenAPI 3.1 validator 和 JSON Schema Draft 2020-12 validator，为 OpenAPI、全部 JSON Schema、Profile 和现有示例建立 lint/编译/实例校验；加入错误版本、缺少 required、非法枚举和未知安全敏感字段等故意失败样例，证明错误合同会阻止合并。`schemaVersion` 只表示 payload 合同版本，A2A `protocolVersion` 只表示传输协议版本，两者不得混用。
4. 完成 AgentScope Spike：项目 Chat/Tool Adapter、结构化输出与 Tool calling、一次结构修复、Middleware 审计、PostgreSQL `AgentStateStore`、重启恢复、最大轮次、deadline、取消、重复动作和 `NO_PROGRESS`；中止后不得继续调用 Tool/Model，状态保存失败不得退化到内存或本地文件。同时通过真实 Provider 对默认及六角色所引用的逻辑 LLM Profile 验证具体模型身份、Secret ref、上下文上限、Tool/结构化/所需流式能力和配额，输出去密钥的版本化验收记录。
5. 完成 A2A 1.0 Spike，并把官方 release/SDK 或官方 proto 生成对象锁定到 `v1.0.1`：六张 Card 的 `protocolVersion` 固定为 `1.0`，验证 `send/stream/get/cancel/subscribe`、持久 Task Store、stream 恢复、messageId 幂等、Artifact 媒体类型/Schema/哈希/身份校验和数据库权限隔离；重复 messageId 的相同请求返回原 Task，不同请求哈希必须冲突。
6. 完成 `retrieval-model-probe`：同一 Infinity 实例加载真实 Embedding 与 Rerank；校验 `/models`、三次 Embedding、实际维度、归一化与有限值、正负样本排序、并发不串模型，并输出带镜像 digest、不可变 revision、探针版本和 UTC 时间的 JSON。同步完成目标开发机上的模型选型门禁，记录模型/权重来源、License、CPU/内存/GPU、冷/热启动、最大长度、并发干扰和中文效果；NDCG@10 与 MRR 均不得低于仅向量基线且至少一项提升 ≥ 5%，最小真实 Embedding+召回+Rerank 链 p95 < 2 秒。
7. 建立最小 Flyway 和角色测试：四个 schema、pgvector 扩展、空库/前一版本升级、专业 Agent 权限、单 Incident 单活动 Run 并发约束。
8. 建立 Prometheus、Jaeger、JSONL、Actuator、Compose Adapter 的最小合同实现，证明 ObservationBatch 单 Source、Resource/Source 归属、Artifact 哈希和 Evidence provenance 可追溯。
9. 按冻结拓扑创建一个 Supervisor/产品进程和五个专业 Agent 进程的 Compose 骨架；专业 Agent 仅内网暴露，各自使用 `AGENT_ID`、Token 和数据库角色。
10. 打通最小 `Incident → Evidence → RCA → Evaluation` 纵切。RCA 允许 `INCONCLUSIVE/rootCause=null`，但所有 Provider 必须真实可调用，且不得访问 Ground Truth。
11. 先落地 `.github/workflows/contracts.yml`；Maven 骨架存在后同时建立 `build-test.yml` 和 `security.yml` 的最小必需检查。输出符合 Schema 的阶段证据/Release Manifest，且任何 Workflow 都不部署环境。

## 主要输出

- 根 `pom.xml`、Maven Wrapper 和第 28.3 节规定的模块目录骨架；
- `deployment/versions.lock.yaml`、`deployment/docker-compose.yml`、`deployment/agents/agent-directory.yaml`；
- AgentScope/A2A Spike 代码与自动化测试；
- `retrieval-model-probe` 小程序及版本化 JSON 结果；
- 默认/六角色 LLM 能力与配额验收记录，以及 Embedding/Rerank 选型、License、资源、中文质量和延迟记录；
- 最小 Flyway、数据库角色和 Testcontainers 测试；
- 合同校验脚本/测试，以及 `contracts.yml`、`build-test.yml`、`security.yml`；
- 最小纵切的 Incident、Evidence、RCA、Evaluation Artifact 和调用审计。

## 完成门禁

- `versions.lock.yaml` 无占位符，依赖可从空缓存解析并通过 License/SBOM 检查。
- AgentScope 和 A2A 的第 24.5、24.6 节断言全部自动化通过；抓包确认无进程内 Agent 快捷调用。
- 第 22.3 节待确认项 1–4 已由实际构建、真实 Provider/模型调用和目标开发机结果闭环；空模型、未验证上下文/能力/配额、未验证维度/归一化/License/资源、未验证 Rerank 或未验证框架 API 均不能通过。
- OpenAPI 3.1、所有 Draft 2020-12 Schema、Profile 和示例通过；失败样例能使检查失败，Java/Python DTO、Agent Artifact 校验和 Evaluation 读取不复制另一套字段定义。
- 五类首期 Source Adapter 通过共享合同测试，Observation/Evidence 来源可追溯。
- Flyway 空库/升级、角色权限和单活动 Run 并发测试通过。
- 六个 Agent endpoint 在 Compose 中 ready；真实模型探针失败时 Server/Agent 不启动。
- 三个场景合同和评测公式保持冻结；最小真实纵切完成且没有 Mock/Fake/替代链路。
- Workflow 只产生校验证据和 Manifest，不保存目标环境凭证、不执行部署。

## 明确不做

- 不在本阶段补齐全部业务表、九个 Tool、完整六 Agent 行为或三场景 E2E。
- 不因 Spike 方便而改变 core Port、状态所有权、A2A 媒体类型或数据库 schema 边界；确需改变必须先 ADR 和设计复审。

## 设计依据

- [实现合同与 Definition of Ready](../design/opspilot-system-design/12-implementation-contracts-and-evolution.md)
- [运行拓扑与 Phase 0 门禁](../design/opspilot-system-design/13-runtime-topology-and-phase0-gates.md)
- [分布式目标与 Source Adapter](../design/opspilot-system-design/16-distributed-target-and-observability-adapters.md)
- [内聚核心与模块边界](../design/opspilot-system-design/17-cohesive-core-and-controlled-extensions.md)
