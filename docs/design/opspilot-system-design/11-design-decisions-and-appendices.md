## 22. 关键设计决策与待确认项

### 22.1 关键设计决策

| 决策 | 结论 |
|---|---|
| 业务关系库 | 统一 PostgreSQL |
| 向量库 | PostgreSQL + pgvector；MVP 精确检索，无 ANN |
| 运行状态/SSE/任务/幂等 | PostgreSQL 状态快照 + transition/event/task/idempotency |
| 知识向量 | PostgreSQL + pgvector；无 typmod `vector` + 模型 revision/真实维度/`searchable` |
| LLM | OpenAI-Compatible 抽象，默认 DeepSeek 基地址，模型/Key 留空外部注入 |
| 本地检索推理 | 单个 Infinity 容器同时加载 Embedding 与 Rerank 两个锁定模型，统一基地址、缓存、健康检查与监控 |
| 测试 Embedding | Infinity `/embeddings`，候选 `BAAI/bge-m3`，实际维度探针 |
| 测试 Rerank | 同一 Infinity `/rerank`，候选 `BAAI/bge-reranker-v2-m3`，真实分数和索引探针 |
| Agent | 6 个首期 Agent 全部使用 AgentScope `ReActAgent` 内置 loop；统一 `BoundedReActRunner` 仅施加预算/checkpoint/停机策略，Supervisor 通过 A2A 1.0 委派 5 个专业 Agent |
| A2A | 锁定官方 v1.0.1；Agent Card + HTTP+JSON + Message/Task/Artifact + stream/get/cancel/subscribe |
| 知识不足 | 空库/无匹配/无历史案例是正常业务结果；技术链路故障显式失败，绝不降级 |
| Token | Context Builder、A2A Artifact 引用、压缩和多级预算 |
| 测试 | 单元可 mock SPI；集成/E2E 必须真实模型和真实 A2A HTTP，不注册运行时 Mock Provider |
| 目标系统边界 | 面向分布式、跨语言系统；Java/Spring Boot 只作为首期 Sample 和语言 Adapter |
| 可观测接入 | Source Adapter → 单来源 ObservationBatch → EvidenceNormalizer → EvidenceBundle；每条 Evidence 可回溯 sourceId/batchId/observationId |
| CI/CD | GitHub Actions 执行 CI 与持续交付，仅生成不可变发布候选；不自动部署、不持有目标环境凭证 |

### 22.2 专用基础设施职责

Prometheus、Jaeger、隔离 Artifact 卷和 Toxiproxy 分别承担时间序列、Trace、可复现数据集和故障注入；其部署、保留、监控和一致性成本见第 2、18、21 章。它们不承载 OpsPilot 业务状态、模型配置、文档元数据或向量。

### 22.3 待确认清单

1. AgentScope Java 最终 Maven 版本/API/License；2.0.0 仅作为评估基线，必须以实际构建验证。
2. 默认/各 Agent 的具体 LLM 模型、API Key、上下文、工具/结构化/流式能力和配额。
3. 测试 Embedding 的准确模型 tag/revision、实际维度、归一化、License 和本机资源结果。
4. Infinity 镜像、Embedding/Rerank 模型 revision、中文质量与本机共享资源，以接口兼容性、并发干扰和中文检索效果测试为选型门禁。
5. 知识规模、写入频率、检索 p95/QPS/Recall@K 和保留周期，以决定是否启用 HNSW/IVFFlat。
6. 生产 PostgreSQL/pgvector/JDBC 版本、HA、RPO/RTO、备份与索引维护窗口。
7. 生产 Artifact 存储、Ground Truth 权威载体和保留策略。
8. 生产身份认证、出站合规、Prompt/Artifact 数据出境规则和模型成本预算。
9. 生产是否多实例运行 OpsPilot Server，以及是否启用 `LISTEN/NOTIFY` 作为事件唤醒优化。
10. 生产 A2A 服务身份方案、Agent Card 签名/信任分发、专业 Agent 独立容器拆分时机和证书轮换。
11. 生产评测阈值是否在 MVP Profile 之外增加行业/企业 Profile；MVP 的确定性公式和发布阈值已在第 25 章冻结，修改必须发布新 Profile 并保留对比结果。

待确认项必须在对应实现或部署阶段前闭环；第 1—4 项属于 Phase 0 完整功能编码门禁，第 5—10 项中仅生产专属内容可延后到生产部署设计。空模型、未验证维度、未验证 Rerank 或未验证 AgentScope API 不得进入可运行配置。

## 附录 A：关键接口定义

### A.1 状态仓库

`IncidentAgentStateRepository` 是 Supervisor 的领域 Repository Port，只负责创建、读取和以 CAS 更新单个 Incident Run 的 `IncidentAgentState` 当前快照，落库目标为 `opspilot.agent_state`。它不保存 AgentScope 会话上下文，不实现 AgentScope `AgentStateStore`，也不管理 A2A Task、Evidence 正文、日志或 Artifact。只有 Supervisor 应用服务可以调用写方法。

```java
public interface IncidentAgentStateRepository {
    Optional<IncidentAgentState> findByRunId(String runId);
    IncidentAgentState create(IncidentAgentState initialState);
    boolean compareAndSet(String runId, long expectedVersion, IncidentAgentState nextState);
}
```

典型调用链为：`IncidentRunService` 创建初始快照；Supervisor 每次合法状态迁移或 checkpoint 后执行 `compareAndSet`；恢复器通过 `findByRunId` 重建业务编排视图，再到 A2A Task Store 查询远端权威 Task 状态。

### A.2 向量仓库

`KnowledgeVectorRepository` 是 Knowledge Ingestion/Search 服务访问 PostgreSQL/pgvector 的 Repository Port，负责批量写入已由 `EmbeddingProvider` 生成并校验的 Chunk 向量、执行带模型 revision 和元数据过滤的向量 Top-K，以及原子切换可检索的文档版本/collection revision。它不调用 Infinity、不生成 Embedding、不执行 Rerank，也不承担知识文档解析；这些职责分别属于 `EmbeddingProvider`、`RerankProvider` 和 Knowledge Ingestion Service。

```java
public interface KnowledgeVectorRepository {
    void upsertBatch(EmbeddingModelRevision revision, List<ChunkEmbedding> embeddings);
    List<VectorCandidate> searchExact(VectorSearchRequest request);
    void switchDocumentVersion(UUID documentId, UUID newVersionId);
    void switchCollectionRevision(UUID collectionId, UUID newRevisionId);
    void markDocumentDeleted(UUID documentId);
}
```

写入调用链为：`KnowledgeIngestionService → EmbeddingProvider → KnowledgeVectorRepository.upsertBatch → 覆盖率校验 → switchDocumentVersion/switchCollectionRevision`。查询调用链为：`KnowledgeSearchService → EmbeddingProvider(query) → KnowledgeVectorRepository.searchExact → RerankProvider → KnowledgeReference`。

### A.3 内部结构化 Agent 结果

该类型只用于单个 Agent 内部执行器与其 A2A Server Adapter 的映射，不得作为 Agent 间传输协议。跨 Agent 输出必须转换为版本化 A2A Artifact。

```java
public record AgentStepResult<T>(
    String taskId,
    String agentName,
    StepStatus status,
    String summary,
    T payload,
    List<String> evidenceIds,
    List<ArtifactReference> artifacts,
    List<String> decisions,
    List<String> openIssues,
    UsageStatistics usage
) {}
```

### A.4 A2A Task 绑定仓库

```java
public interface A2ATaskBindingRepository {
    A2ATaskBinding createPending(A2ADelegation delegation);
    Optional<A2ATaskBinding> findByRunAndStep(String runId, String stepId);
    A2ATaskBinding bindRemoteTask(String messageId, String contextId, String a2aTaskId);
    A2ATaskBinding recordArtifact(String a2aTaskId, String artifactId, String sha256);
}
```

## 附录 B：官方能力核验依据

- DeepSeek 官方 API 文档说明 OpenAI-compatible 基地址为 `https://api.deepseek.com`：https://api-docs.deepseek.com/
- Infinity 官方仓库说明单实例多模型、Embedding/Rerank 能力、CPU/GPU 镜像和已测试模型：https://github.com/michaelfeil/infinity
- Infinity CLI v2 官方源码定义可重复的 `model-id`、`served-model-name`、`revision` 和批量配置：https://github.com/michaelfeil/infinity/blob/main/libs/infinity_emb/infinity_emb/cli.py
- Infinity 官方 OpenAPI 定义 `/models`、`/embeddings`、`/rerank`、`/health` 和 `/metrics`：https://raw.githubusercontent.com/michaelfeil/infinity/main/docs/assets/openapi.json
- pgvector 官方文档说明精确检索、HNSW/IVFFlat 权衡、变量维度 `vector`、`vector_norm`、ANN 维度限制以及同维度表达式/部分索引：https://github.com/pgvector/pgvector
- AgentScope Java 2.0 官方文档用于版本评估，最终依赖仍以项目构建验证：https://java.agentscope.io/v2/en/docs/index.html
- A2A v1.0 协议规范定义 Agent Card、Message、Task、Artifact、操作和协议绑定：https://a2a-protocol.org/v1.0.0/specification/
- A2A 官方 v1.0.1 release：https://github.com/a2aproject/A2A/releases/tag/v1.0.1
- A2A Agent discovery 与 Agent Card：https://a2a-protocol.org/latest/topics/agent-discovery/
