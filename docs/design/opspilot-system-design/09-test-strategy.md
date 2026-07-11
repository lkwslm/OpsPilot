## 20. 测试策略

### 20.1 单元测试

单元测试不启动可部署 Mock Provider；可以在测试类内 mock SPI，以验证纯业务逻辑。至少覆盖：

- AgentEndpointState、A2A Task、step attempt、Incident Run 四层状态机的合法/非法转换、终态不变式、`runId + version` CAS、取消与恢复；
- `IncidentAgentState` JSON 序列化/反序列化、Schema 版本迁移、未知版本拒绝、字段边界、列表上限和禁止内容校验；
- 六个 Agent 的 AgentScope `ReActAgent` + `BoundedReActRunner` 策略包装：框架内置 loop、Middleware/checkpoint、动作白名单、最大轮数、重复动作指纹、NO_PROGRESS、取消和完成条件；验证未实现第二套嵌套 loop；
- Evidence 去重/时间对齐、Hypothesis 评分、引用有效性；
- Tool 权限、审批、Shell 白名单、输出限长、敏感字段脱敏；
- Scenario YAML 校验、Ground Truth 路径隔离、故障恢复 `finally`；
- 8 类 Evaluation 指标；
- 默认配置 + Agent 稀疏覆盖、Secret ref 解析和配置快照；
- Context Builder 最小上下文、A2A Message/Artifact 映射、压缩保留项；
- Token 预留/对账/超限策略和最大轮数；
- Provider JSON/SSE 解析、错误映射、Rerank index 映射；
- Embedding 批次维度/有限值校验和模型 revision identity。
- A2A Agent Card 校验、状态映射、Message/Artifact Schema、messageId 幂等、终态不可续写和取消传播；
- 证据门禁、`rootCause=null` 的 `INCONCLUSIVE`、补证指纹、无进展检测和所有硬预算停机条件。

### 20.2 PostgreSQL/pgvector 集成测试

使用锁定版本的 `pgvector/pgvector` Testcontainers 镜像，不用 H2 模拟：

1. 从空库执行全部 Flyway，验证 `vector` 扩展和角色权限。
2. 验证订单/库存 JPA 映射、JSONB、`@Version` 和事务回滚。
3. 并发领取 `task`，验证 `SKIP LOCKED`、租约过期恢复、最大 attempts 和幂等键。
4. 同一事务写状态、转换和事件；故障回滚时三者都不出现。
5. 验证 `agent_state.version` 与 `state_json.version` 一致、CAS 冲突不会覆盖、快照引用必须存在且归属同一 Run、专业 Agent 数据库角色无读写权限。
6. 验证 AgentScope `AgentState` 按 `(server_agent_id,user_id,session_id)` 隔离与恢复；A2A continuation 复用会话，新 attempt 创建新会话；框架状态保存失败时显式失败且不切换内存/文件 Store。
7. 验证 `api_idempotency` 同 Key 同请求重放、不同 hash 冲突。
8. 写入不同维度 revision，验证正确维度成功、漂移/非有限值失败。
9. 验证 collection + active revision + `searchable` + 文档元数据过滤的精确 Top-K。
10. 验证文档更新/删除、旁路重向量化、100% 覆盖后原子切换和回滚。
11. 验证 `opspilot_app_role` 无法访问 `opspilot_eval`。
12. 验证 SSE `Last-Event-ID` 只重放目标 Incident/Run 的后续事件。

### 20.3 Provider 合同测试

三套 SPI 各有共享 contract suite，任何新 Adapter 都必须通过：

- Chat：正常/流式、Usage、工具调用、结构化输出、429、5xx、超时、无效 Schema、取消；
- Embedding：单条/批量、顺序、维度、模型 identity、空/超长输入、错误响应；
- Rerank：query + documents、`top_n`、原 index、分数有限值、模型 mismatch、超限候选和超时。

HTTP 协议单测可以使用本地 HTTP fixture，但这不替代真实模型集成测试，也不能作为运行 Provider 注册。

### 20.4 A2A 合同与互操作测试

锁定 A2A 1.0.1 规范/官方 SDK 测试六个 Agent：

1. `/.well-known/agent-card.json` 通过 Schema 校验，`supportedInterfaces`、skill、媒体类型、streaming 和安全声明与实测一致。
2. 覆盖 `message:send`、`message:stream`、`tasks/{id}`、`tasks/{id}:cancel`、`tasks/{id}:subscribe` 及全部 TaskState；收到 `UNSPECIFIED` 必须拒绝为无效 Agent 响应。
3. 覆盖版本不支持、内容类型不支持、未知 required extension、认证/越权、Task 不存在/不可取消和非法 Agent 响应。
4. 重复 messageId 同请求返回原 Task，不同请求 hash 冲突；终态 Task 不能通过后续 Message 复活。
5. stream 断开、客户端重启、Agent Server 重启后能通过 Get/Subscribe 和持久化 Task Store 恢复。
6. 禁用任何进程内 Agent 调用，在不同端口抓取真实 HTTP 交换；专业 Agent 数据库角色无法写 Supervisor 领域表。
7. Artifact 的 schema version、媒体类型、完整性哈希、Task 归属和 Evidence 引用均被校验。
8. 覆盖端点 `UNKNOWN/PROBING/READY/UNAVAILABLE/DRAINING/DISABLED`、attempt 的 `RECONCILING/VALIDATING_RESULT/CANCEL_REQUESTED`、Incident `CANCELLING` 及所有非法终态迁移。

### 20.5 真实模型集成测试

`docker compose` 模型测试必须执行：

1. 单个 Infinity 实例加载锁定的 Embedding 与 Rerank 两个 revision，`/models` 显示正确模型别名和能力。
2. 真实 `/embeddings` 返回稳定非空向量且维度与 PostgreSQL revision 一致；真实 `/rerank` 对至少一组中文正/负文档给出可解释顺序。
3. 使用注入 Key 和非空模型名调用真实 DeepSeek/其他 LLM；验证至少一个首期 Agent 所需的工具调用和结构化输出能力。
4. 分别停止 Embedding/Rerank/断开 LLM，断言 readiness 和业务错误清晰，且没有模拟数据。
5. 同一 Infinity 内两个模型并发调用时保持各自权重和模型路由，资源限制生效；压力下不得串模型或返回错误能力结果。

测试环境不允许 Mock 模型替代这些测试。若 CI Job 被定义为模型集成门禁而缺少 Key/模型变量，应在准备阶段失败，不应静默跳过。

### 20.6 Rerank 模型选型验收

| 维度 | 必测项 | 通过标准 |
|---|---|---|
| 可运行 | 锁定 Infinity 镜像、两个模型 ID/revision 可下载和同时加载 | 冷/热启动均成功，无未声明代码执行依赖 |
| 接口 | `/rerank` 返回 query-document 真实分数和索引 | 合同测试全过，无生成式伪装 |
| 中文效果 | 基于首期故障知识集比较向量召回与重排 | NDCG@K/MRR 不低于仅向量基线；目标值待基准确认 |
| 资源 | CPU/GPU、内存/显存、镜像/权重大小 | 在目标开发机不 OOM；预算待确认 |
| 延迟 | 不同候选数/长度的 p50/p95 | 满足待确认的 RAG SLO |
| License/供应链 | 模型、框架、镜像、权重来源 | 许可证与项目使用方式兼容，revision/digest 可追溯 |

Rerank 模型在本地资源占用、接口兼容性和中文重排效果测试通过后确定。

### 20.7 端到端测试

对 3 个首期场景分别执行：

```text
启动并健康检查环境
→ 建立基线
→ 真实注入故障并持续流量
→ 采集日志/指标/Trace
→ 恢复并验证基线
→ 生成 input/Ground Truth
→ 创建 Incident 并运行真实多 Agent
→ 生成 JSON/Markdown RCA
→ 运行 Evaluation
→ 验证 Artifact、引用、审计、Token 和结果
```

每次 PR 至少运行 Java/Python 单元和 PostgreSQL 集成；受 Secret 保护的模型集成 Job 运行真实 Provider；每日或发布前运行全部 3 个故障场景。任何层级的通过状态必须分别报告，不能用离线单测冒充 E2E。

每个故障场景覆盖两组严格区分的矩阵：

- **正常空结果：**`KB_EMPTY`、真实检索 `NO_MATCH`、历史案例为 0。任务必须在 deadline 和预算内输出 `CONCLUSIVE/PARTIAL/INCONCLUSIVE` 之一，不得生成不存在的引用。
- **技术链路失败：**LLM、Embedding、Rerank、KnowledgeAgent、任一 A2A endpoint 和 Tool 分别不可用/超时/鉴权失败/Schema 无效。任务必须在有限重试后 `FAILED`，错误体和日志含完整关联 ID、attempt、上游状态、checkpoint 和 `logArtifactId`；断言从未调用 Mock、vector-only、关键词、固定结果、替代 Provider 或跳过计划步骤。

构造重复补证场景，验证连续两轮无进展后停止；构造每个 Agent 的 ReAct 动作序列，验证动作只能来自白名单且 loop 由统一运行时而非 Agent 业务代码控制。

### 20.8 性能与索引基准

- 在代表性 Chunk 数、元数据选择性、并发和写入频率下测精确扫描 p95/吞吐。
- 只有精确方案不满足 SLO，才建立同 revision 的 HNSW 与 IVFFlat 对照，记录 Recall@K、p95、索引大小、构建时间、WAL、插入/更新吞吐和内存。
- 使用 `searchable=true + model revision + collection` 的真实过滤条件测试；验证 ANN 后置过滤不会导致候选不足，必要时用过采样/迭代扫描或精确回退。
- 索引切换前后运行固定黄金查询，索引删除/回滚也纳入演练。

### 20.9 安全与故障测试

- Ground Truth schema/卷越权、路径穿越、符号链接逃逸、超大文件；
- Prompt injection 试图更改工具权限、读取密钥或执行 Shell；
- API 幂等冲突、跨 Incident `runId`、伪造 `Last-Event-ID`；
- HIGH_RISK/未审批动作、PromQL/代码路径/模型 URL allowlist 绕过；
- 数据库/模型/Prometheus/Jaeger/Toxiproxy 不可用、超时和恢复；
- 日志、Trace、SSE、Actuator 中无明文密钥和 Ground Truth。
- A2A Agent Card/endpoint 篡改、SSRF、Task 跨调用方访问、Artifact URL 越权、未知 required extension 和伪造 Task 事件。
