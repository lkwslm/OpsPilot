## 背景与约束

当前仓库以设计文档和机器合同为主，尚无 Maven 工程、运行实现或持续校验链。Phase 0 的职责不是交付完整产品，而是在后续编码前用最小、可自动验证的实现闭环关键技术不确定性，并留下可由 CI 和 Release Manifest 引用的证据。

实现必须遵循以下事实源顺序：

1. `docs/design/contracts/` 中的 OpenAPI 3.1 与 JSON Schema Draft 2020-12；
2. 设计中的状态转换矩阵、能力关键性矩阵、第 23 章不变量和第 28 章模块边界；
3. `docs/implementation-plan/01-phase-0-spikes-and-gates.md` 的工作包、阈值和交付顺序；
4. OpenSpec proposal、能力规范和本设计只负责组织实施，不重定义上述事实。

涉及者包括后续实现者、合同/安全审查者、模型与基础设施选型者以及 CI 维护者。真实 Provider 凭证、目标开发机和镜像/模型供应链是外部条件，缺失时相关任务必须保持 `BLOCKED`，不能用替代实现宣称通过。

## 目标与非目标

**目标：**

- 把 Phase 0 的 10 个工作包转换为按依赖执行、可单独验证和可追溯的任务。
- 先建立合同与版本身份，再以最小正式模块内的 Spike 验证框架、协议、模型、数据库和 Source 边界。
- 统一正常、负向、恢复、取消、权限与真实故障测试，并生成带 URI、SHA-256、commit 和配置身份的证据。
- 以六进程 Compose 和最小真实纵切证明各项 Spike 可以组合，且没有进程内 Agent 快捷路径、Mock/Fake 或静默回退。

**非目标：**

- 不补齐全部业务表、九个 Tool、完整六 Agent 行为或三场景 E2E。
- 不实施非 MVP 能力、生产 HA/身份/保留策略，也不选择 ANN。
- 不创建通用 Extension Host，不拆分设计之外的 Maven 模块，不让 Spike 类型或临时代码污染 core。
- 不在证据出现前写死尚待确认的 AgentScope/Provider/模型结论；需要改变冻结边界时先 ADR 和设计复审。

## 关键设计决策

### 1. 一个 OpenSpec 变更覆盖一个完整阶段

本变更用 10 个能力规范一一对应 `01-WP01` 至 `01-WP10`，任务继续使用原始 `01-WPxx.Txx` 标识。这样 Issue、PR、测试报告和 Release Manifest 能共用稳定编号，也能在一个变更中表达跨工作包门禁。

未采用“每个工作包一个 OpenSpec 变更”，因为 Phase 0 的完成结论依赖所有 Spike 汇总和 `01-WP10` 纵切；拆成独立变更会使阶段级依赖、证据和完成状态分散。实现时仍可按工作包分别提交 PR，但不得单独宣称 Phase 0 完成。

### 2. 最小实现必须落在未来正式边界内

根 Maven 工程只创建第 28.3 节冻结模块；AgentScope Spike 位于 `opspilot-agent-runtime-agentscope`，A2A Spike 位于 `opspilot-a2a`，PostgreSQL 和 Source Spike 分别位于对应 Adapter。`opspilot-core` 只包含 domain、application 和 port，架构测试禁止框架或厂商类型进入 core。

未采用独立 `spikes/` 模块或临时 Extension Host，因为它们会绕开正式依赖方向，使可行性结论无法证明未来模块边界可用。

### 3. 身份先于能力，静态门禁先于真实探针

`01-WP01` 先建立 `deployment/versions.lock.yaml`，`01-WP02` 再建立合同校验；后续每个真实探针都必须引用版本锁中的依赖坐标、镜像 digest、模型 revision 和合同版本。静态身份不完整直接失败，不发起昂贵或可能误导的真实调用。

未采用“先跑通再补版本锁”，因为 tag 漂移或模型别名会让测试结果不可复现，也无法把证据关联到准确供应链身份。

### 4. 真实能力通过专用探针闭环

Chat、Embedding、Rerank、AgentScope 和 A2A 分别使用面向其冻结断言的最小探针或测试入口。Chat 能力按实际模型去重，但必须回填到默认及六角色逻辑 Profile；Embedding/Rerank 在同一 Infinity 实例并发验证；A2A 必须通过真实 HTTP+JSON 和持久 Store；AgentScope 必须使用 PostgreSQL 状态并验证终止后的零调用。

未采用根据名称、静态 capability 声明或 OpenAI-Compatible 接口推断能力，也不使用内存 Store、Bean 直调或模型模拟器替代真实门禁，因为这些方式不能闭环设计中的待确认项。

### 5. 证据模型与业务事实分离

原始响应保存为带哈希 Artifact；每次 Source 调用只生成一个 Source 的 ObservationBatch；Observation 经 EvidenceNormalizer 转换为不可变 Evidence 后，Agent、RCA 和 Evaluation 才能消费。报告统一记录输入身份、UTC 时间、工具版本、统计、结果、URI、SHA-256、commit 和配置摘要，并执行 Secret、完整 Prompt、完整响应和 Ground Truth 泄漏检查。

未采用下游直接读取厂商 DTO，因为这会形成平行事实模型并破坏 provenance；也不把完整敏感交互写入证据，以免凭证和 Prompt 泄漏。

### 6. PostgreSQL 是状态、幂等和权限的唯一验证基础

最小 Flyway 只创建 Phase 0 所需表、pgvector、四个 schema 和冻结角色。AgentState、A2A Task/messageId、Incident/Run、Artifact/Evidence/Evaluation 都用 PostgreSQL 验证持久、重启和权限语义；Testcontainers 使用锁定 PostgreSQL/pgvector 镜像，覆盖空库与前一版本升级。

未采用 H2、内存数据库或本地文件，因为它们不能验证 PostgreSQL 并发约束、角色隔离、pgvector 和恢复语义。

### 7. 启动资格与运行健康分层

Migration 是所有进程的硬启动依赖。一次性 retrieval probe 用于 Phase 0 和部署资格，不作为长运行服务的完成型依赖；Server/Agent 自身执行等价轻量探针。身份、revision、维度、协议或 required capability 的确定性不兼容使进程非零退出；配置合法但端点暂时不可达只使 readiness DOWN，liveness 保持 UP，并在恢复后重试。

未采用“任意探针失败都退出”或“端点不可达仍 ready”，前者会把可恢复依赖故障误判为配置错误，后者会接收无法完成的新任务。

### 8. 阶段收敛顺序固定

工作包依赖保持为：

```text
01-WP01
├─→ 01-WP02
├─→ 01-WP03 ─→ 01-WP04 ─┐
├─→ 01-WP05 ─────────────┤
├─→ 01-WP06 ─────────────┼─→ 01-WP09
└─→ 01-WP07 ─→ 01-WP08 ─┘
全部通过 ─→ 01-WP10
```

每个工作包内部先完成身份/配置，再完成正常测试、负向测试、恢复/取消/权限测试，最后生成证据。`01-WP10` 只在所有上游门禁通过后运行，避免纵切中的组合失败掩盖单项合同错误。

## 风险与权衡

- **风险：真实 Provider、模型或目标开发机暂不可用** → 任务保持 `BLOCKED` 并记录缺失条件；允许先完成不依赖该条件的静态实现，但不得勾选真实门禁。
- **风险：锁定的 AgentScope/A2A API 与设计断言不兼容** → 用最小 Spike 和实际 API 报告定位差异；若影响 core 语义或冻结协议，先 ADR 与设计复审，不在 Adapter 中隐藏偏差。
- **风险：Phase 0 范围很大，任务被“骨架完成”提前关闭** → 每个任务同时列出实现、验证和证据；只有工作包的正常、负向和恢复路径都通过才可关闭。
- **风险：供应链身份或测试输入漂移导致基准不可复现** → 所有镜像使用 digest、模型使用不可变 revision、fixture 与查询集记录哈希，报告绑定机器规格和 commit。
- **风险：证据包含 Secret、Ground Truth 或完整 Prompt/响应** → 输出前执行自动泄漏扫描，报告只保存 Secret ref、摘要、Usage 和脱敏错误。
- **风险：六进程组合调试成本较高** → 先独立完成合同、框架、数据库和 Adapter 套件，再进入 Compose；故障结果保留 Source、Task、Trace 和 Artifact 关联。
- **权衡：一个阶段变更包含较多任务** → 保留稳定工作包编号和依赖图，换取单一阶段事实源与完成审查；PR 可以按工作包拆分。

## 落地与回退计划

1. 合入 OpenSpec 规划文档，不改变现有设计合同。
2. 依次实施 `01-WP01` 和 `01-WP02`，使后续提交均受版本锁与合同 Workflow 约束。
3. 按依赖图实施并验证各 Spike；每个 PR 附任务 ID、设计章节、合同版本、命令、退出码、报告 URI/SHA-256 和 `BLOCKED` 项。
4. 上游门禁全部通过后建立六进程 Compose，再运行最小真实纵切与阶段 Manifest。
5. Phase 0 尚未进入生产部署，不需要数据面上线迁移。若某项最小实现失败，可回退该工作包的代码与 migration；已生成证据保留并标记失败，不改写历史结果。
6. 若数据库 migration 已被后续测试使用，回退通过新的修正 migration 或重建一次性测试数据库完成，不修改已经发布的 migration 文件。

## 待闭环问题

- AgentScope Java 的准确版本、Maven 坐标、实际 API 和 License 是否满足第 24.5 节全部断言？
- 默认及六角色最终引用哪些真实 Chat 模型，其上下文、Tool、结构化、流式和配额能力是否实际可用？
- Embedding 的准确 revision、维度、归一化、License 和目标开发机资源结果是什么？
- Infinity digest、Rerank revision、并发干扰、中文质量和真实链路 p95 是否达到冻结门禁？

以上问题只能由对应 Spike 产物回答；在回答前不得以暂定值或“预计支持”关闭任务。
