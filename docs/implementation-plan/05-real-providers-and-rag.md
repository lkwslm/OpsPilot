# 阶段 05：真实模型 Provider、知识版本与完整 RAG

## 目标

实现三个窄 Provider Port 的真实 Adapter、专用 Registry、配置/能力探针、Token 预算，以及 `Embedding → pgvector 精确召回 → Rerank → 引用` 的完整知识链路。

## 前置门禁

- Infinity 镜像、Embedding/Rerank 模型 revision 已由 Phase 0 在目标开发机验证并锁定。
- pgvector revision、知识版本和 Artifact 基础设施已完成。

## 实施内容

1. 实现 `OpenAICompatibleChatModelProvider`，默认 base URL 为 `https://api.deepseek.com`，模型名和 API Key 保持无虚假默认值；URL 资源路径不可无条件重复拼 `/v1`。
2. 分别实现 `ChatModelProviderRegistry`、`EmbeddingProviderRegistry`、`RerankProviderRegistry`；稳定 ID 冲突、required 缺失或版本不兼容时启动失败，启动后冻结。
3. 实现静态配置校验和真实能力探针：工具调用、结构化输出、所需流式、模型 identity、Embedding 维度/有限值、Rerank index/score/排序。配置缺失进程退出；临时不可达时 liveness UP/readiness DOWN。
4. 实现模型配置继承、六角色稀疏覆盖、非敏感数据库配置/版本、Run 级 effective snapshot、Secret ref 解析，以及不得把密钥写入 YAML/数据库/日志/Trace/RCA 的约束。
5. 实现 Context Builder/Compactor、Prompt 版本和 TokenBudgetManager；上下文接近上限时按“重复工具输出 → 已完成步骤细节 → 低相关候选”压缩并保留决策、结论、未解决问题、约束和 Artifact 引用，禁止简单截断最早消息。按 Provider Usage 对账，缺失 Usage 时保守估算并标记，失败调用也计预算；超限依次尝试压缩、降低知识候选数、缩小代码/时间窗、拆分子任务和 Supervisor 重规划，仍不足则返回 `TOKEN_BUDGET_EXCEEDED` 并保存缺失工作/可恢复状态。落实 `Incident deadline > Agent step deadline > Provider/Tool request deadline > connect + response/read timeout`，子层超时与退避不得越过父层；LLM、Embedding、Rerank 默认总尝试均不超过 2，429 尊重受本地上限约束的 `Retry-After`，网络/502/503/504 只在总 deadline 内指数退避并加 jitter。每个 Provider Profile 使用独立 semaphore/bulkhead，所有失败调用、等待和重试计入预算/审计，超时取消请求并释放 semaphore，且不得自动切换 Provider。
6. 实现 Infinity Embedding Adapter：批处理按 Token/Provider 限制、批次原子性、同 revision 哈希复用、Query/文档 revision 一致，不截断/补零/自动改维度。
7. 实现 Infinity Rerank Adapter：保存 documentId、原 index、score、rank 和锁定 identity；拒绝重复/不存在 index、非有限分数和 model mismatch。
8. 实现 Knowledge Search：关系/标签过滤、当前 active revision 的精确 pgvector `candidate_k`、非空候选必经 Rerank、最终 `top_k` 和可核验 KnowledgeReference。
9. 区分合法空结果与失败：空知识库返回 `COMPLETED + KB_EMPTY`，非空知识库检索成功但零候选返回 `COMPLETED + NO_MATCH`，两者都不调用 Rerank；存在候选时 Rerank 失败使任务失败，绝不 vector-only/关键词/固定排序保底。
10. 完成文档导入、更新、删除、重新向量化、质量校验和 collection active revision 原子切换；知识断言只有规范化为 Evidence 后才可进入诊断。

## 主要输出

- Chat OpenAI-Compatible、Infinity Embedding/Rerank 和 pgvector Knowledge Adapter；
- 三个 Model Registry、能力报告/快照和真实探针；
- 模型/Agent 配置、Secret Resolver、Context Builder、Prompt 与 Token/成本账本；
- 文档导入、切分、向量化、精确检索、Rerank、引用和重向量化服务；
- 三类 Provider 共享合同测试和真实模型集成测试。

## 完成门禁

- 配置缺 Key、模型或上下文上限时错误精确指向配置路径且不泄密；真实能力不符时不注册能力。
- Chat 正常/流式/Usage/工具/结构化输出和错误映射合同通过；流式中断不得把半成品当最终结果，只有幂等且预算允许时才能从 checkpoint 重启整次调用。
- 400/Schema/上下文超限不原样重放，结构修复最多一次；401/403/模型不存在不重试；429 与网络错误的有界退避、父子 deadline、取消、semaphore 释放和无自动 failover 测试通过。
- Embedding 条数、顺序、维度、有限值、revision 和批次原子性通过；Rerank 原 index、分数、模型与超时合同通过。
- Rerank 选型满足中文 NDCG@10/MRR 不低于向量基线且至少一项提升 5%，完整 RAG p95 小于 2 秒，并记录资源/License/digest/revision。
- `KB_EMPTY`、真实 `NO_MATCH` 和无历史案例均是可区分的成功空结果；任一技术失败有 ChainFailure 且没有替代链路。
- Query 和文档不混用 revision；旁路重向量化失败不影响旧 active revision。
- Token、调用数、deadline、成本和重试均进入审计/预算；预算耗尽或 `NO_PROGRESS` 不自动标记技术失败，而是在保存 checkpoint 后按权威状态矩阵进入 `GENERATING_REPORT`，完成为受限 `PARTIAL/INCONCLUSIVE`，且不无限重试。只有当前步骤返回可回答的业务输入缺失时才能进入 `WAITING_INPUT`，并受 deadline 与 continuation 上限约束。

## 明确不做

- 不建设动态模型路由或自动 failover；不假定 Chat 端点同时支持 Embedding/Rerank。
- 不使用生成模型冒充 Rerank，不提供禁用 Rerank 的 MVP 开关。

## 设计依据

- [模型配置与 Token 预算](../design/opspilot-system-design/04-model-configuration-and-token-budget.md)
- [数据与向量存储](../design/opspilot-system-design/05-data-and-vector-storage.md)
- [模型与检索 Provider](../design/opspilot-system-design/06-model-and-retrieval-providers.md)
- [本地模型部署与启动](../design/opspilot-system-design/07-local-model-deployment-and-bootstrap.md)
