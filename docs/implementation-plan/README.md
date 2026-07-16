# OpsPilot 分阶段实施计划

本目录把 `docs/design/` 中的 OpsPilot v3.0 设计转换为可执行的实施阶段。阶段边界遵循设计第 21.6 节的九步顺序；这里不新增产品能力，也不修改设计中的合同、状态、范围或验收阈值。

## 使用规则

1. 实施前先阅读本页，再按编号顺序执行阶段文档；前一阶段的完成门禁未通过，不进入下一阶段。
2. 设计事实源的优先级保持不变：`docs/design/contracts/` 中的 OpenAPI 与 JSON Schema → 状态转换矩阵、能力关键性矩阵和第 23 章不变量 → 第 28 章边界 → 数据表和其他模块边界正文 → Mermaid 图、示例 JSON/YAML 和说明文字。
3. 阶段文档只负责组织工作。若阶段文档与设计冲突，以设计事实源为准，并先修正文档，不能在代码中选择性解释。
4. 每阶段必须提交实际文件、可复现命令和测试结果；“代码已写完”或文字说明不能替代门禁证据。
5. 设计中标为“待确认”的项只能通过 Spike、选型记录或 ADR 闭环，不得猜测版本、模型、生产规格或身份系统。

## 阶段总览

| 阶段 | 目标 | 主要完成标志 |
|---|---|---|
| [01](01-phase-0-spikes-and-gates.md) | Phase 0：合同、依赖与真实能力 Spike | 所有 Phase 0 门禁通过，最小真实纵切可运行 |
| [02](02-cohesive-core-and-module-skeleton.md) | 内聚核心、模块骨架与状态机 | core 无框架依赖，状态、Port、outbox 和架构测试通过 |
| [03](03-postgresql-pgvector-and-artifacts.md) | PostgreSQL/pgvector、Flyway 与 Artifact | 四 schema/角色、领域持久化、向量版本和追溯可用 |
| [04](04-sample-system-and-observability.md) | Sample System、故障接口与可观测适配 | 三服务、五类 Phase 0 基线 Adapter 及受控 HTTP 健康/配置实现产生可追溯 Evidence |
| [05](05-real-providers-and-rag.md) | 真实 Chat/Embedding/Rerank 与完整 RAG | 真实探针、精确召回、必经 Rerank、引用和重向量化通过 |
| [06](06-agent-runtime-a2a-and-product-api.md) | 六个 Agent、AgentScope、A2A 与产品 API | 六进程经 HTTP+JSON 协作，状态/恢复/API/SSE 合同通过 |
| [07](07-fault-lab-rca-and-evaluation.md) | Fault Lab、三场景、RCA 与确定性评测 | 三场景可注入/恢复，输出 JSON/Markdown RCA 和 8 类指标 |
| [08](08-quality-security-and-release-gates.md) | 质量矩阵、恢复、安全、效率、性能与发布阈值 | 15 次质量 Run 与全部质量/效率/安全/性能门禁通过 |
| [09](09-ci-continuous-delivery-and-rc.md) | CI、持续交付与 RC 演练 | 不可变 RC、SBOM、证据和 Manifest 就绪，流水线不部署 |

## 设计覆盖矩阵

| 设计内容 | 主实施阶段 | 后续验证阶段 |
|---|---|---|
| 第 1–3 章：目标、范围、约束、总体架构 | 01、02 | 07、08 |
| 第 4 章：模块、Sample、Fault Lab、Tool、API、RCA | 02、04、06、07 | 08 |
| 第 5–6 章：A2A、多 Agent、状态和调度 | 02、06 | 08 |
| 第 7–8 章：模型配置、Context、Token 预算 | 05、06 | 08 |
| 第 9–10 章：PostgreSQL、pgvector、表和迁移 | 03、05 | 08、09 |
| 第 11–13 章：三类 Provider 与 RAG | 05 | 08 |
| 第 14–16 章：本地推理、配置和启动 | 01、05、06 | 08、09 |
| 第 17–19 章：可靠性、安全、可观测性 | 02–06 | 08、09 |
| 第 20 章：测试策略 | 各功能阶段同步实现 | 08、09 |
| 第 21–22 章：部署、顺序、决策和待确认项 | 01、09 | 全阶段 |
| 第 23 章：实现合同与演进 | 01–06 | 08、09 |
| 第 24 章：运行拓扑与 Phase 0 | 01 | 06、09 |
| 第 25 章：场景与确定性评测 | 07 | 08、09 |
| 第 26 章：CI、持续交付与发布治理 | 01、04、09 | 09（RC 演练） |
| 第 27 章：分布式目标与 Source Adapter | 03、04 | 07、08 |
| 第 28 章：内聚核心与受控扩展 | 02 | 03–09 |
| OpenAPI、JSON Schema 和示例 | 01 建立持续校验；对应阶段实现 | 08、09 |

## 全程不可偏离的边界

- MVP 是本地可运行、可验证、可复现的演示；不接真实企业生产系统。
- 非 MVP 项不实施：Tree-sitter MCP、自动 Patch、Git Worktree、自动 PR、多租户、Kubernetes/Chaos Mesh、并行子 Agent、动态模型路由、LLM-as-a-Judge、审批前端和 `ReviewerAgent`。
- PostgreSQL + pgvector 是默认唯一业务/向量存储；不引入 MySQL、Redis、Qdrant、Milvus 或 Elasticsearch Vector。
- 六个 Agent 使用统一运行时驱动的有界 ReAct；Agent 间必须经过 A2A 1.0 HTTP+JSON，不允许进程内快捷调用。
- `Evidence` 是唯一事实层。ObservationBatch、CodeFinding、KnowledgeResult 必须规范化后才能进入 Diagnosis、Hypothesis、RCA 和 Evaluation。
- 真实能力失败必须透明：禁止 Mock/Fake、固定结果、关键词检索、vector-only、生成式伪 Rerank 或静默 Provider/Source 切换。
- Ground Truth 只对 Fault Lab/Evaluation 可见；Agent、产品 API、日志和 Prompt 均不得访问。
- GitHub Actions 只做 CI 和持续交付，在 Draft Release/Release Manifest 处停止；不得持有目标环境凭证或自动部署。

## 待确认项的阶段门禁

- Phase 0 必须闭环：AgentScope Java 的实际版本/API/License；各 Agent 的真实 LLM 配置与能力；Embedding 的准确 revision/维度/归一化/License/资源；Infinity digest、Embedding/Rerank revision、并发干扰和中文质量。
- 是否启用 ANN 只能在知识规模、写入频率、p95/QPS/Recall@K 和保留周期基准后决定；MVP 默认仍为精确检索。
- 生产 PostgreSQL/pgvector/JDBC、HA/RPO/RTO/备份与索引维护窗口，Artifact/Ground Truth 介质和保留策略，身份/出站合规/数据出境与模型成本预算，多实例/LISTEN-NOTIFY，以及 A2A 身份/签名/证书、专业 Agent 独立容器拆分时机等生产项可以延后到生产部署设计，但不能在 MVP 文档或配置中虚构结论。
- 新的生产 Evaluation Profile 可以后续新增；MVP 的公式和阈值已经冻结，任何变更必须发布新 Profile 并保留对比结果。

## 每阶段统一交付格式

阶段合并前，在 PR 或构建制品中附带：

- 本阶段新增/修改文件清单；
- 从干净环境执行的命令及退出码；
- 单元、合同、集成或 E2E 报告及其 SHA-256；
- 未通过项、`BLOCKED` 项和仍待确认项；
- 对应设计章节与机器合同版本；
- 若涉及架构或合同变化，附 ADR；没有 ADR 时不得改变冻结边界。
