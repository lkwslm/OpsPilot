# 阶段 05：真实模型 Provider、知识版本与完整 RAG

## 目标

实现三个窄 Provider Port 的真实 Adapter、专用 Registry、配置/能力探针、Token 预算，以及 `Embedding → pgvector 精确召回 → Rerank → 引用` 的完整知识链路。

## 前置门禁

- Infinity 镜像、Embedding/Rerank 模型 revision 已由 Phase 0 在目标开发机验证并锁定。
- pgvector revision、知识版本和 Artifact 基础设施已完成。

## 建设范围

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

## 详细实施计划

### 工作包设计输入与依赖

| 工作包 | 设计/机器合同输入 | 直接依赖 |
| --- | --- | --- |
| 05-WP01 | 模型配置继承、三个 Provider Registry 和能力快照规则 | 阶段 01 版本/模型锁定 |
| 05-WP02 | Chat Provider 合同、Agent Profile 所需模型能力 | 05-WP01 |
| 05-WP03 | Context/Token 预算、deadline/retry/bulkhead 与状态矩阵 | 05-WP01、05-WP02 |
| 05-WP04 | Embedding Provider 合同、本地模型探针与向量 revision | 05-WP01，阶段 03 向量持久化 |
| 05-WP05 | Rerank Provider 合同、中文质量与 p95 门禁 | 05-WP01，阶段 01 选型结果 |
| 05-WP06 | 知识文档/Chunk/Version/Job 数据模型和 Artifact 合同 | 05-WP04，阶段 03 知识持久原语 |
| 05-WP07 | 精确召回、强制 Rerank、Knowledge Reference/Evidence 规则 | 05-WP04～05-WP06 |
| 05-WP08 | 空结果/技术失败和 Run/step 权威状态矩阵 | 05-WP03、05-WP07 |
| 05-WP09 | active revision 原子切换、运行快照与回滚规则 | 05-WP06、05-WP07 |

### 05-WP01：实现三类 Provider Registry 与配置快照

- **05-WP01.T1**：分别实现 Chat、Embedding、Rerank Registry 的显式装配、稳定 ID 冲突检测、required/版本校验、启动后冻结。
- **05-WP01.T2**：定义平台默认配置、角色稀疏覆盖和 Run 级 effective snapshot；模型名、上下文上限等非敏感配置可版本化，密钥只保存 Secret ref。
- **05-WP01.T3**：启动时生成不含密钥的 capability report/digest；required Provider 配置缺失时精确报告配置路径并退出。
- **目标文件**：三个 Registry、Provider 配置 Schema/Mapper、Secret Resolver、effective snapshot/capability snapshot。
- **验证与证据**：重复 ID、缺 required、版本不兼容、覆盖优先级、冻结和日志泄密测试。

### 05-WP02：实现真实 Chat Provider 与能力探针

- **05-WP02.T1**：实现 `OpenAICompatibleChatModelProvider`；默认 base URL 为 `https://api.deepseek.com`，模型与 API Key 无虚假默认值，资源路径不重复拼接 `/v1`。
- **05-WP02.T2**：实现普通/流式请求、工具调用、结构化输出、Usage、取消和统一错误映射；流中断不得产生最终消息。
- **05-WP02.T3**：对真实端点探测模型 identity、工具、结构化输出和所需流式能力；临时不可达只使 readiness DOWN。
- **目标文件**：Chat Adapter、HTTP client/DTO mapper、probe、错误映射和脱敏日志拦截器。
- **验证与证据**：共享合同、真实模型探针记录、抓包脱敏结果及 400/401/403/429/5xx/流中断测试。

### 05-WP03：落实上下文、预算、超时和有界重试

- **05-WP03.T1**：实现 Context Builder/Compactor 与版本化 Prompt；按冻结优先级压缩，保留决策、结论、约束、未解决问题和 Artifact 引用。
- **05-WP03.T2**：实现 Token/调用数/成本账本；优先用 Provider Usage 对账，无 Usage 时保守估算并标记，失败调用同样入账。
- **05-WP03.T3**：实现四层 deadline 传播、取消和独立 bulkhead/semaphore；等待、请求和退避都不得越过父 deadline。
- **05-WP03.T4**：LLM/Embedding/Rerank 默认总尝试不超过 2；按错误分类执行一次结构修复或受控网络退避，禁止自动换 Provider。
- **05-WP03.T5**：预算不足按压缩、减候选、缩时间窗/代码、拆子任务、Supervisor 重规划顺序处理；仍不足保存 checkpoint 和缺失工作。
- **目标文件**：Context/Prompt、TokenBudgetManager、deadline/retry/bulkhead middleware、usage/cost ledger。
- **验证与证据**：父子超时、取消释放、重试次数、`Retry-After` 上限、失败计费、预算耗尽状态映射测试。

### 05-WP04：实现 Infinity Embedding Adapter

- **05-WP04.T1**：按 Token 和 Provider 限制做批处理，保持输入输出条数与顺序；单批失败不得部分提交。
- **05-WP04.T2**：锁定模型 identity/revision/维度，拒绝非有限值、维度漂移，不截断、不补零、不自动转换。
- **05-WP04.T3**：按规范化文本哈希与 revision 复用向量；查询和文档必须使用同一 revision。
- **目标文件**：Embedding Adapter、batcher、probe、vector validator、cache/revision integration。
- **验证与证据**：共享合同、真实模型维度报告、顺序/原子性/revision mismatch 测试。

### 05-WP05：实现 Infinity Rerank Adapter

- **05-WP05.T1**：提交带稳定 `documentId` 的候选，保存原 index、score、rank 和 Provider identity/revision。
- **05-WP05.T2**：拒绝重复/不存在 index、非有限 score、缺失结果、模型 mismatch 和超时部分结果。
- **05-WP05.T3**：对中文基准集记录向量基线与 Rerank 的 NDCG@10/MRR、延迟、资源、License、digest/revision。
- **目标文件**：Rerank Adapter、response validator、benchmark harness/fixture、capability probe。
- **验证与证据**：共享合同与真实基准报告；质量需同时满足“不低于基线且至少一项提升 5%”。

### 05-WP06：建立文档导入、切分与知识版本

- **05-WP06.T1**：实现文档导入/更新/删除、格式校验、规范化切分、metadata/ACL 和原始 Artifact 保存。
- **05-WP06.T2**：创建 immutable knowledge revision，批量向量化并记录文本哈希、模型 revision、维度和任务状态。
- **05-WP06.T3**：失败批次可恢复但不得让未完成 revision 对外可见；删除先设置 `deleted_at` 并立即从检索中过滤，后续异步清理不得破坏受保留策略保护的历史引用。
- **目标文件**：Knowledge ingestion application service、chunker、revision repository、embedding job/checkpoint。
- **验证与证据**：重复导入幂等、更新/删除、断点恢复、ACL 和历史引用稳定性测试。

### 05-WP07：实现精确召回、强制 Rerank 与引用

- **05-WP07.T1**：对当前 active revision 执行关系/标签过滤和 pgvector 精确 `candidate_k` 召回，并保存查询 revision/过滤条件摘要。
- **05-WP07.T2**：非空候选必须调用 Rerank，截取最终 `top_k`；任何 Rerank 技术失败都使链路失败。
- **05-WP07.T3**：构造可核验 `KnowledgeReference`，包含文档/chunk/revision/Artifact/位置与 score/rank，知识断言需再规范化为 Evidence。
- **目标文件**：Knowledge Search use case、pgvector Adapter、Rerank orchestration、reference/evidence mapper。
- **验证与证据**：过滤、revision 一致、排序、引用回溯、技术失败无降级测试和完整 RAG p95 报告。

### 05-WP08：实现成功空结果与失败的严格分流

- **05-WP08.T1**：空 collection 返回 `COMPLETED + KB_EMPTY`；非空 collection 成功检索零候选返回 `COMPLETED + NO_MATCH`。
- **05-WP08.T2**：两种空结果都不调用 Rerank；Embedding、数据库、Rerank 等技术失败必须返回对应 `ChainFailure`。
- **05-WP08.T3**：把知识空结果交给现场调查继续执行；把预算耗尽/`NO_PROGRESS` 映射到保存 checkpoint 后的 `GENERATING_REPORT`，仅真实业务输入缺失进入 `WAITING_INPUT`。
- **目标文件**：Knowledge outcome mapper、Supervisor policy/state mapping、相关合同 fixture。
- **验证与证据**：空库、零候选、各技术失败、预算耗尽、无进展和输入缺失的状态矩阵测试。

### 05-WP09：实现旁路重向量化与原子切换

- **05-WP09.T1**：创建新 revision 后在旁路完成重切分/重向量化、条数/维度/有限值/抽样检索质量校验。
- **05-WP09.T2**：仅在全部校验通过后单事务切换 collection active revision；运行中的 Run 继续使用其 effective snapshot。
- **05-WP09.T3**：失败时保留旧 active revision，支持审计、清理未激活 revision 和显式回切。
- **目标文件**：Re-embedding job、quality validator、revision activation transaction、cleanup/rollback command。
- **验证与证据**：中途失败、并发查询、原子切换、Run 快照稳定和回切测试。

## 阶段内执行顺序

1. 05-WP01 固化配置与能力合同后，分别实施 05-WP02、05-WP04、05-WP05。
2. 05-WP03 作为三类 Provider 的共同运行约束，与 Adapter 合同一起通过后再开放给业务用例。
3. 05-WP06 建立 revision 后执行 05-WP07、05-WP08；最后用 05-WP09 验证全生命周期。

## 测试与证据矩阵

| 验证层 | 必测内容 | 阶段证据 |
| --- | --- | --- |
| 合同 | Chat/Embedding/Rerank 成功、空、错误、取消、Usage、identity/revision | 三类共享合同报告 |
| 真实集成 | DeepSeek-compatible Chat、Infinity Embedding/Rerank、pgvector 精确检索 | 探针、请求摘要和模型 digest/revision |
| 质量/性能 | NDCG@10、MRR、完整 RAG p95 `< 2s` | 固定数据集与可复算报告 |
| 状态/恢复 | KB_EMPTY、NO_MATCH、技术失败、预算耗尽、重向量化切换/回切 | 状态矩阵和事务测试报告 |
| 安全 | Secret ref、日志/Trace/RCA 脱敏、ACL、禁止自动 failover | 负向测试与 secret scan |

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
