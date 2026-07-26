## 背景与动机

阶段 00 至 04 已锁定真实模型与基础设施版本，并提供 Provider Port、pgvector revision、知识持久化和 Artifact 原语，但生产装配仍缺少真实 Chat/Embedding/Rerank Adapter、统一的模型调用治理以及端到端知识检索编排。阶段 05 需要把这些基础合同收敛为可启动、可审计、可恢复且不以虚假降级掩盖失败的完整 RAG 链路，为后续 Agent/A2A 产品能力提供真实运行底座。

## 变更内容

- 实现三个专用 Provider Registry、模型配置继承、Secret ref 解析、Run 级有效配置快照及脱敏能力快照；配置缺失、稳定 ID 冲突或版本不兼容时启动失败，注册完成后冻结。
- 实现基于通用 HTTP/Jackson 的 `OpenAICompatibleChatModelProvider`，覆盖普通与流式调用、工具调用、结构化输出、Usage、取消、错误映射和真实能力探针。
- 实现 Context Builder/Compactor、版本化 Prompt、Token/调用数/成本账本，以及受父 deadline 约束的 timeout、retry、bulkhead/semaphore 和取消传播。
- 实现 Infinity Embedding 与 Rerank Adapter，锁定模型 identity/revision，严格校验批次原子性、维度、有限值、输入映射、index、score 和排序，并保留可复算的中文质量/性能证据。
- 实现知识文档导入、更新、删除、版本化切分、向量化 job/checkpoint、同 revision 哈希复用和未完成 revision 隔离。
- 实现对当前 active revision 的关系/标签过滤、pgvector 精确 `candidate_k` 召回、候选非空时强制 Rerank、最终 `top_k`、可核验 `KnowledgeReference` 与 Evidence 规范化。
- 明确 `KB_EMPTY`、`NO_MATCH` 与技术失败的互斥语义，并把预算耗尽、`NO_PROGRESS`、业务输入缺失映射到权威 Run/step 状态。
- 实现旁路重切分/重向量化、质量校验、collection active revision 原子切换、运行快照稳定、显式回切和安全清理。
- 不引入动态模型路由、自动 failover、生成模型伪 Rerank、vector-only/关键词/固定排序降级或绕过 Rerank 的开关。

## 能力范围

### 新增能力

- `provider-registry-and-model-configuration`：三个专用 Registry、配置继承与版本、Secret 边界、有效配置及能力快照和启动冻结合同。
- `openai-compatible-chat-provider`：真实 OpenAI-Compatible Chat Adapter 的请求、流式、工具、结构化输出、Usage、错误与能力探针合同。
- `model-invocation-governance`：Context/Prompt、Token 与成本预算、deadline、retry、bulkhead、取消和审计合同。
- `infinity-embedding-provider`：Infinity Embedding 的批处理、identity/revision、向量校验、哈希复用和查询/文档一致性合同。
- `infinity-rerank-provider`：Infinity Rerank 的候选映射、响应校验、identity/revision 和中文质量/性能门禁合同。
- `knowledge-ingestion-lifecycle`：文档导入/更新/删除、切分、ACL、Artifact、immutable revision、向量化 job/checkpoint 和删除可见性合同。
- `rag-knowledge-search`：active revision 精确召回、强制 Rerank、最终排序、可核验引用和 Evidence 转换合同。
- `knowledge-outcome-state-mapping`：`KB_EMPTY`、`NO_MATCH`、技术失败、预算耗尽、无进展与输入缺失的结果和状态映射合同。
- `knowledge-revision-activation`：旁路重向量化质量门禁、active revision 原子切换、Run 快照隔离、回切和清理合同。

### 修改能力

- 无。阶段 05 在既有 `chat-model-capability-gate`、`retrieval-model-gate`、`pgvector-revision-retrieval`、`knowledge-version-persistence`、`artifact-lifecycle-storage` 和状态机合同之上实现运行能力，不改变其已冻结需求。

## 影响范围

- 影响 `opspilot-core` 的 Provider Port 使用、模型调用治理、知识用例、结果映射和 Evidence 边界，以及 `opspilot-infrastructure`/`opspilot-server` 的 HTTP Adapter、PostgreSQL/pgvector Adapter、Secret/配置装配、健康探针和 composition root。
- 新增或扩展模型配置、能力快照、Prompt、Usage/成本账本、知识导入 job、revision 激活和检索引用相关的数据库迁移、配置 Schema、测试 fixture 与审计记录。
- 运行依赖真实 DeepSeek-compatible Chat 端点、锁定 digest/revision 的 Infinity Embedding/Rerank 服务、PostgreSQL/pgvector 和 Artifact 存储；受保护集成测试需要显式 Secret 与固定基准数据集。
- 验收覆盖共享合同、真实端点探针、错误/取消/重试/脱敏负向测试、中文 NDCG@10/MRR、完整 RAG p95 `< 2s`、revision 切换/回切事务测试及 secret scan。
