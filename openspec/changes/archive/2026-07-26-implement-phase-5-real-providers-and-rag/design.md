## 背景

阶段 00 已用真实 DeepSeek-compatible 与 Infinity 端点锁定 Chat、Embedding、Rerank 的协议、模型 revision、质量和性能基线；阶段 02 已建立窄 Provider Port、`ChainFailure`、状态机和受控扩展边界；阶段 03 已提供模型/知识表、pgvector 精确检索、knowledge revision、Artifact 与事务原语。当前生产代码中的模型与检索 Adapter 仍只有模块骨架，阶段 05 负责把既有原语装配为真实运行链路。

事实源按机器合同、设计不变量、阶段计划的顺序解释。实现必须维持 `opspilot-core` 不依赖 Spring、HTTP、Infinity 或 PostgreSQL 客户端；Adapter 只负责传输与响应规范化，预算、重试、状态和降级策略由核心应用层控制。运行依赖阶段 00 已锁定的镜像 digest 和模型 revision；若这些门禁证据缺失或失效，本变更保持阻塞，不猜测模型身份。

工作包依赖如下：05-WP01 先冻结配置与 Registry；05-WP02、05-WP04、05-WP05 在其上并行形成三类真实 Adapter；05-WP03 把共同调用约束包在三类 Provider 外层；05-WP06 依赖 Embedding 与阶段 03 知识原语；05-WP07 汇合 Embedding、Rerank、知识版本和 pgvector；05-WP08 固化结果/失败状态；05-WP09 最后验证 revision 全生命周期。

## 目标与非目标

**目标：**

- 提供三个显式装配、稳定 ID 唯一、required/版本可校验且启动后冻结的专用 Provider Registry。
- 提供真实 Chat、Embedding、Rerank Adapter 及可审计能力探针，不以 Fake、固定结果或替代 Provider 维持 readiness。
- 在所有模型调用外统一执行配置快照、Context/Prompt、Token/调用/成本预算、父子 deadline、有界重试、独立 bulkhead、取消和审计。
- 提供文档导入到 `Embedding → pgvector 精确召回 → Rerank → KnowledgeReference → Evidence` 的完整、revision 一致且可恢复链路。
- 用事务和 Run 级 effective snapshot 保证旁路重向量化、active revision 切换、回切与运行中查询互不污染。
- 产生可复算的共享合同、真实集成、质量、性能、事务、状态矩阵和安全证据。

**非目标：**

- 不实现动态模型路由、classpath 插件扫描、Registry 热加载或自动 Provider failover。
- 不把 OpenAI-Compatible Chat 能力外推为 Embedding/Rerank 能力，不让生成模型、二次余弦、关键词或固定排序冒充 Rerank。
- 不提供禁用 Rerank、跨 revision 向量复用、自动截断/补零/改维度或未完成 revision 对外可见的开关。
- 不实现阶段 06 的完整 Agent/A2A 产品编排、阶段 07 的冻结故障场景或新的配置管理后台。

## 设计决策

### 决策一：配置解析与 Registry 冻结先于业务装配

`opspilot-server` composition root 按“读取 Bootstrap/数据库非敏感版本 → 合并平台默认与六角色稀疏覆盖 → 校验 Secret ref 与字段 → 构建去密钥 effective snapshot → 显式注册 Adapter → 真实探针 → 冻结 capability snapshot/Registry”的顺序启动。Chat、Embedding、Rerank 各自使用独立 Registry 和 capability key；稳定 ID 冲突、required 缺失、版本不兼容或静态配置缺失使进程非零退出。配置完整但端点临时不可达时保持 liveness UP、readiness DOWN，恢复后重新探针，Registry 只在能力验证成功后对业务可用。

选择专用 Registry 而非万能对象表，是为了让每类 Port 的必需能力、版本与类型在编译和启动边界都可验证；选择启动后冻结而非热加载，是为了让 Run 快照、审计和重放拥有稳定含义。

### 决策二：Secret 值只在调用边界短暂解析

YAML、PostgreSQL、effective snapshot、capability report、日志、Trace、错误和 RCA 只保存 `api_key_ref` 或不可逆摘要，不保存解析值。`SecretResolver` 在发请求前解析受允许 scheme 的引用，值只进入内存中的授权 header，并在日志/异常归一化前经过固定脱敏器。配置错误报告精确字段路径和应注入变量名，但不回显值。

不把密钥导入数据库或 Run 快照，因为这会扩大静态泄漏面并破坏快照可安全审计的前提。

### 决策三：Adapter 保持窄，治理中间件统一包裹调用

`OpenAICompatibleChatModelProvider` 使用通用 HTTP client 与 Jackson DTO mapper；`base_url` 视为 API 根地址，资源相对路径单独配置并用 URI 解析组合，禁止字符串式重复拼 `/v1`。Infinity Adapter 分别实现 Embedding 与 Rerank Port，即便共享同一容器、基地址和监控也不合并领域接口。

调用链固定为“配置/能力解析 → Context/输入构建 → 预算预留 → deadline 与独立 semaphore 获取 → HTTP 执行/受控重试 → 响应校验与规范化 → Usage 对账 → 脱敏审计 → 释放 semaphore”。Adapter 不决定换 Provider、扩大预算或改变业务状态。这样 Chat/Embedding/Rerank 能共享运行约束，同时保留各自协议校验。

### 决策四：Context 与预算按引用和账本管理

`AgentContextBuilder` 只装配版本化公共/角色 Prompt、当前结构化任务、必要状态摘要、允许 Tool Schema、Evidence 和受权 Artifact 片段。接近上下文上限时，`ContextCompactor` 依次压缩重复工具输出、已完成步骤细节、低相关候选，始终保留决策、结论、未解决问题、约束和 Artifact 引用，禁止删除最早消息式截断。

`TokenBudgetManager` 在调用前保守预留，调用后优先以 Provider Usage 对账；Usage 缺失时记录算法版本和“估算”标志，失败调用、等待、重试也进入调用与成本审计。预算不足只按“压缩 → 减少知识候选 → 缩小代码/时间窗 → 拆分子任务 → Supervisor 重规划”降载，仍不足返回 `TOKEN_BUDGET_EXCEEDED` 并保存 checkpoint 与缺失工作。

### 决策五：父 deadline 是所有等待与重试的硬上限

时间层级固定为 `Incident deadline > Agent step deadline > Provider/Tool request deadline > connect + response/read timeout`。每层从父层剩余时间派生；获取 semaphore、连接、读取、`Retry-After`、指数退避和 jitter 都计入同一 deadline。三类 Provider 默认总尝试不超过 2：401/403/模型不存在不重试，400/Schema/上下文超限不原样重放且结构修复最多一次，429 只等待本地上限内的 `Retry-After`，网络及 502/503/504 只在剩余时间允许时退避。超时或取消必须中止请求并在 `finally` 释放对应 Profile 的 semaphore。

选择每个 Provider Profile 独立 bulkhead，而非全局并发池，是为了防止一个慢模型耗尽其他模型的执行容量，同时让等待成本可以归属到准确配置快照。

### 决策六：Embedding 与 Rerank 对输入映射和模型身份 fail closed

Embedding batcher 同时受 Token 数、最大条数和 Provider 限制约束；一批响应必须条数、顺序、维度、有限值和 COSINE 非零范数全部通过后才能整批提交。复用键为规范化文本哈希与 `model_revision_id`，复用只减少计算，新 Chunk 仍写独立向量行并重新校验。Query 必须使用 active document revision 的预处理、模型 revision、维度和距离合同。

Rerank 请求为每个候选携带稳定 `documentId`；响应必须能唯一映射原 index，包含有限 score、确定 rank 及锁定 identity/revision，拒绝重复、不存在、缺失 index、模型 mismatch 和超时部分结果。score 不假定为 0~1，也不跨模型比较。

### 决策七：知识导入发布 immutable revision，检索固定 Run 快照

导入先把原始文档作为受控 Artifact 保存，再按版本化规范化/切分策略生成 document version、Chunk、metadata/ACL 与 immutable knowledge revision。Embedding job 以 checkpoint 推进，批次事务只提交完整结果；失败 revision 可恢复但保持不可搜索。删除先写 `deleted_at` 并立即进入所有检索过滤，异步物理清理必须尊重历史引用和 Artifact 保留策略。

每个 Run 保存 collection active revision 和 effective model configuration；后续 collection 切换不改变运行中 Run。这样查询、引用和重放都能指向同一知识与模型事实。

### 决策八：候选非空时 Rerank 是不可绕过的提交门

Knowledge Search 先解析 Run 快照中的 collection/revision，再生成匹配 revision 的 Query embedding，对关系字段、标签、ACL、`searchable=true` 和 `deleted_at` 执行过滤，并用 allowlist distance operator 做精确 `candidate_k` 检索。零候选直接形成 `NO_MATCH`；候选非空必须调用 Rerank，成功后截取 `top_k`，任何 Rerank 技术失败都返回 `ChainFailure`，不得返回 vector-only 结果。

每个 `KnowledgeReference` 保存 document/chunk/revision、Artifact 与位置、向量距离、Rerank score/rank、查询 revision 和过滤摘要。只有通过权限和完整性校验、再由 `EvidenceNormalizer` 规范化的知识断言才能进入诊断。

### 决策九：业务空结果、技术失败和受限报告严格分流

collection 没有可用文档时返回 `COMPLETED + KB_EMPTY`；非空 collection 的 Embedding 与数据库查询成功但零候选时返回 `COMPLETED + NO_MATCH`；两者均不调用 Rerank，并允许现场调查继续。Embedding、数据库或候选非空后的 Rerank 失败均产生具体 `ChainFailure`，不能伪装为空结果。

预算耗尽或 `NO_PROGRESS` 在保存 checkpoint 后进入 `GENERATING_REPORT` 并最终产生受限 `PARTIAL/INCONCLUSIVE`；只有当前步骤确实缺少用户可回答的业务输入才进入 `WAITING_INPUT`。该映射复用既有权威状态机，不由 Adapter 自行迁移状态。

### 决策十：旁路校验全部通过后才原子激活

重向量化创建新 revision，在旁路完成重切分、Embedding、条数/coverage、维度、有限值和固定抽样检索质量校验。只有全部通过才在单事务内切换 collection active revision 与新旧 `searchable`；失败保持旧 revision 不变。回切同样走显式事务和审计。未激活 revision 只在无受保护 Run/Reference/Artifact 引用且超过保留策略后清理。

## 风险与权衡

- [真实 Provider 端点或 Secret 在开发/CI 不可用] → 把离线合同与受 Secret 保护的真实集成 Job 分层报告；缺少真实证据时明确阻塞阶段门禁，绝不以 Fake 通过。
- [Provider 方言导致流式、Usage 或 JSON Schema 行为差异] → 以逐能力真实探针和共享合同冻结行为；未验证能力不注册，错误保留脱敏响应摘要与 correlation ID。
- [Token 估算与实际 Usage 偏差] → 使用保守、版本化估算并标记，Provider Usage 到达后对账；不允许缺失 Usage 记零。
- [严格强制 Rerank 降低可用性] → 将 Rerank 纳入 readiness、独立 bulkhead 和有限重试；候选存在时宁可显式失败，也不牺牲排序语义和可审计性。
- [精确 pgvector 扫描随数据增长变慢] → 阶段 05 保持少于 50,000 active Chunk 的 MVP 边界并记录 p95；越界后进入独立 ANN 基准与设计，不在本阶段偷偷建索引。
- [并发导入、删除、激活和查询产生可见性竞态] → 使用 immutable revision、Run 快照、事务切换、`searchable/deleted_at` 双过滤和并发事务测试。
- [引用或审计泄漏原文与 Secret] → 引用只保存稳定身份、位置、哈希和受控摘要；所有持久化/日志/Trace/RCA 运行 secret scan 与权限负向测试。

## 迁移与回滚计划

1. 先新增非敏感模型配置、Prompt/Usage/成本账本、knowledge job/revision/reference 所需 migration 与 Schema，并验证旧数据可读、默认配置不会伪造模型或密钥。
2. 装配三个 Registry、Secret Resolver、真实探针和 readiness；在业务流量接入前冻结 capability snapshot。
3. 分别启用 Chat、Embedding、Rerank Adapter 与共享治理中间件，通过合同和真实集成门禁后再开放知识用例。
4. 运行文档导入和旁路向量化，校验 coverage、质量与性能后原子激活首个可搜索 revision。
5. 启用 Knowledge Search、空结果/失败映射和 Evidence 转换，执行完整 RAG 与状态矩阵验收。

应用回滚时停用阶段 05 业务入口但保留新增表和不可变 revision，恢复上一应用版本；知识回滚通过显式事务回切到保留窗口内的旧 active revision。任何 migration 不删除旧 revision、历史引用或受保护 Artifact，清理由独立、可审计命令完成。

## 待确认事项

- 阶段 00 锁定清单中的最终 `DEFAULT_LLM_MODEL`、`EMBEDDING_MODEL_REVISION`、`RERANK_MODEL_REVISION`、Infinity 镜像 digest 与目标开发机资源证据必须在实现开始前复核；不一致时暂停对应真实集成任务。
- Provider 价格表来源与币种若尚未冻结，可先实现版本化可选成本估算；Token 与调用数预算仍为强制门禁，金额字段必须明确标记估算或不可用。
- 文档输入格式 allowlist、默认 Chunk 策略参数和 ACL metadata 键若机器合同尚未列全，在 05-WP06 开始前以最小受支持集合冻结，不能由 Adapter 自由解释。
