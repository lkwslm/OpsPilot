## 7. Agent 独立 LLM 配置

### 7.1 配置继承

有效配置按以下顺序合并，后者覆盖前者：

```text
系统默认 LLM 配置
→ Agent 稀疏覆盖
→ 单次任务允许的受控覆盖（仅预算/输出长度等白名单字段）
```

每个 Agent 都支持独立设置 Provider、Base URL、API Key 引用、模型、Temperature、Max Tokens、Timeout、重试次数、并发限制、上下文长度、流式输出、工具调用能力和结构化输出能力。没有覆盖的字段继承默认值；不得把整套默认配置复制到每个 Agent。

### 7.2 Bootstrap 配置示例

```yaml
models:
  defaults:
    llm:
      provider: openai-compatible
      base_url: https://api.deepseek.com
      api_key: ""
      api_key_ref: env:DEEPSEEK_API_KEY
      model: ${DEFAULT_LLM_MODEL:}
      temperature: 0.2
      max_tokens: 2048
      timeout_seconds: 60
      retry:
        max_attempts: 2
        initial_backoff_ms: 500
      concurrency_limit: 4
      context_window_tokens: ${DEFAULT_LLM_CONTEXT_WINDOW:0}
      stream: false
      required_capabilities:
        tool_calls: true
        structured_output: true

agents:
  supervisor:
    llm:
      temperature: 0.1
      max_tokens: 2048
      concurrency_limit: 1
    budget:
      max_input_tokens_per_call: 12000
      max_output_tokens_per_call: 2048
      max_task_tokens: 30000
      max_calls: 12

  evidence_collector:
    llm:
      temperature: 0
      max_tokens: 1024
      concurrency_limit: 2
    budget:
      max_input_tokens_per_call: 6000
      max_output_tokens_per_call: 1024
      max_task_tokens: 12000
      max_calls: 8

  code_analysis:
    llm:
      temperature: 0
      max_tokens: 1536
    budget:
      max_input_tokens_per_call: 10000
      max_output_tokens_per_call: 1536
      max_task_tokens: 18000
      max_calls: 8

  knowledge:
    llm:
      temperature: 0
      max_tokens: 768
    budget:
      max_input_tokens_per_call: 5000
      max_output_tokens_per_call: 768
      max_task_tokens: 8000
      max_calls: 6

  diagnosis:
    llm:
      temperature: 0.1
      max_tokens: 3072
    budget:
      max_input_tokens_per_call: 16000
      max_output_tokens_per_call: 3072
      max_task_tokens: 40000
      max_calls: 10

  remediation:
    llm:
      temperature: 0.1
      max_tokens: 3072
    budget:
      max_input_tokens_per_call: 12000
      max_output_tokens_per_call: 3072
      max_task_tokens: 24000
      max_calls: 6
```

`api_key` 的默认解析结果为空；密钥不写入 YAML、数据库、镜像或日志。`model`、API Key 或上下文上限缺失时，配置校验返回具体字段名。`context_window_tokens: 0` 表示“未配置”，不是无限；任务在模型可用前失败。

### 7.3 模型分级

| Agent | 默认能力级别 | 理由 |
|---|---|---|
| Supervisor | 高能力 | 复杂计划、跨模块调度、结束判断和风险控制 |
| EvidenceCollector | 中小型 | 工具参数生成、分类、抽取和格式化为主 |
| CodeAnalysis | 中型 | 需要代码语义，但首期读取范围受控 |
| Knowledge | 中小型 | 检索查询改写和短摘要；召回/重排由专用模型完成 |
| Diagnosis | 高能力 | 多证据推理、冲突消解、假设评分 |
| Remediation | 高能力 | 修复权衡、回滚和监控/测试方案 |

模型名称均由部署配置指定，不能在设计中把所有 Agent 强制绑定到同一个“最强模型”。MVP 使用静态 Agent 配置，不建设动态路由平台；若以后启用路由，只能在能力、上下文、预算和重试状态明确时选择已注册的真实模型。

### 7.4 配置持久化与快照

- Bootstrap YAML/环境变量在启动时导入或覆盖 PostgreSQL `model_profile` 与 `agent_model_override` 的非敏感字段。
- 数据库只存 `api_key_ref`，不存密钥值；Secret Resolver 在调用时解析环境变量或生产密钥管理系统。
- 每次 Incident Run 固化 `effective_model_config_snapshot`（去密钥）和配置版本，运行中不受热更新影响。
- 配置更新采用版本号和审计记录；无管理界面的 MVP 可通过受控迁移/启动导入更新，不新增不必要的配置后台。

## 8. Token 成本控制策略

### 8.1 Context Builder

每个 Agent 通过专属 `AgentContextBuilder` 组装最小上下文：

```text
共享基础 System Prompt（按版本引用）
+ Agent 职责 Prompt
+ 当前结构化任务
+ 必要状态摘要
+ 允许工具的 Schema
+ 精确 Evidence/Artifact/代码/知识引用
+ 输出 Schema
```

不向所有 Agent 广播完整会话、完整仓库、所有工具结果、其他 Agent 的完整输出或推理过程。日志和 Trace 先按时间窗聚合，代码按文件与行号加载，知识先召回再重排；Prompt 中记录引用 ID，只有当前 Agent 确实需要完整正文时才加载。

### 8.2 状态分层与压缩

- 当前任务状态：步骤、状态、预算、下一动作；始终保留。
- 长期事实：工单、已验证证据、关键约束；以结构化字段保留。
- 临时结果：本轮候选、搜索片段；完成后压缩为摘要和引用。
- 可丢弃日志：调试细节和重复输出；不进入后续 Prompt。
- 可重读 Artifact：原始日志、Trace、代码、文档；只保留 ID/路径/行号/哈希。

上下文接近 Agent 上限时，`ContextCompactor` 按“重复工具输出 → 已完成步骤细节 → 低相关候选”的顺序压缩，并保留决策、结论、未解决问题、重要约束和 Artifact 引用。不得简单截断最早消息。

### 8.3 Prompt 管理

- 公共 Prompt：安全、证据约束、引用规则、不可读取 Ground Truth；按 `prompt_template.version` 管理。
- Agent Prompt：只包含该 Agent 职责、停止条件和输出 Schema。
- 动态 Prompt：当前任务、预算和必要引用。
- 工具描述：只注入当前 Agent 可用工具，长示例放 Artifact 或模板版本，不重复发送。
- 输出：优先 JSON Schema；Markdown 由结构化领域结果渲染。

### 8.4 预算执行

`TokenBudgetManager` 在调用前预留估算 Token，调用后按 Provider 返回的 Usage 对账。模型未提供 Usage 时使用明确标记的保守估算，不能记为 0。预算维度包括：

- 单次输入 Token 上限；
- 单次输出 Token 上限；
- 单 Agent/单任务累计 Token；
- Agent 最大调用次数；
- Incident 总 Token/调用预算；
- 可选金额预算（价格表版本化，结果标记为估算）。

超限时依次尝试：压缩上下文、降低知识候选数、缩小代码/时间窗、拆分子任务、要求 Supervisor 重新规划；仍不满足则返回 `TOKEN_BUDGET_EXCEEDED`，保存缺失工作和可恢复状态。禁止在预算不足后无限重试。

### 8.5 成本与质量平衡

- 小模型执行分类、路由、抽取、格式转换和状态判断；高能力模型只用于计划、复杂诊断和修复权衡。
- 先用元数据过滤和专用 Rerank 缩小知识上下文，不把 Top-K 完整正文全部交给 LLM。
- 相同不可变 Prompt 模板和 Artifact 摘要按内容哈希复用；是否能获得 Provider 缓存折扣以实际 Provider Usage 为准，不作假设。
- 重试使用同一上下文引用，不重复读取和拼接大文本；失败调用仍计入预算。
