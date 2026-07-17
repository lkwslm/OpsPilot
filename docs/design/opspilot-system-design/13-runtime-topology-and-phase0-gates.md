## 24. 首版运行拓扑与 Phase 0 门禁

### 24.1 冻结的进程拓扑

首版采用“一个产品/Supervisor 服务 + 五个专业 Agent 服务”。六个服务可以复用同一 OCI 镜像和代码仓库，但必须以不同 `AGENT_ID`、服务身份、数据库角色和 A2A 基地址启动。该拓扑从本地 Compose 到生产保持一致，避免先做进程内多 Agent、后续再重构网络、状态和权限边界。

六个进程复用第 28 章的同一 `opspilot-core`、Agent 执行服务和 AgentScope Adapter；`AGENT_ID` 只选择经过启动校验的内置 `AgentProfile`，不触发 classpath Extension 扫描。各进程仅装配该 Profile 所需的 Tool/Provider/Source Adapter，遵守最小权限；A2A 仍是稳定进程边界，不因物理模块收敛而改为内存直调。

| 服务 | 端口 | Agent ID | 职责 |
|---|---:|---|---|
| `opspilot-server` | 8080 | `supervisor` | 产品 REST/SSE、Supervisor A2A Server、领域事务 |
| `evidence-agent` | 8081 | `evidence-collector` | 观测 Evidence 收集 |
| `code-agent` | 8082 | `code-analysis` | 受控代码定位 |
| `knowledge-agent` | 8083 | `knowledge` | 完整 Embedding/pgvector/Rerank 链路 |
| `diagnosis-agent` | 8084 | `diagnosis` | 假设生成与验证 |
| `remediation-agent` | 8085 | `remediation` | 修复建议和受控测试请求 |

每个容器在自己的 origin 暴露 `/.well-known/agent-card.json`，因此不存在一条 well-known 路径对应多张卡的问题。产品端口只绑定宿主 `127.0.0.1:8080`；专业 Agent 只在 Compose 内网暴露，不绑定宿主端口。

### 24.2 Agent Directory

首版使用只读配置文件 `deployment/agents/agent-directory.yaml`，内容必须进入镜像/部署清单并计算摘要：

```yaml
schema_version: "1.0"
agents:
  - id: evidence-collector
    card_url: http://evidence-agent:8081/.well-known/agent-card.json
    expected_skill: collect-observability-evidence
    expected_protocol: "1.0"
    expected_binding: HTTP+JSON
    token_ref: env:EVIDENCE_AGENT_TOKEN
  - id: code-analysis
    card_url: http://code-agent:8082/.well-known/agent-card.json
    expected_skill: analyze-code-location
    expected_protocol: "1.0"
    expected_binding: HTTP+JSON
    token_ref: env:CODE_AGENT_TOKEN
  - id: knowledge
    card_url: http://knowledge-agent:8083/.well-known/agent-card.json
    expected_skill: retrieve-incident-knowledge
    expected_protocol: "1.0"
    expected_binding: HTTP+JSON
    token_ref: env:KNOWLEDGE_AGENT_TOKEN
  - id: diagnosis
    card_url: http://diagnosis-agent:8084/.well-known/agent-card.json
    expected_skill: generate-and-verify-hypotheses
    expected_protocol: "1.0"
    expected_binding: HTTP+JSON
    token_ref: env:DIAGNOSIS_AGENT_TOKEN
  - id: remediation
    card_url: http://remediation-agent:8085/.well-known/agent-card.json
    expected_skill: propose-remediation
    expected_protocol: "1.0"
    expected_binding: HTTP+JSON
    token_ref: env:REMEDIATION_AGENT_TOKEN
```

Token 必须是不同值，并限制为对应 skill。Agent Card digest 在首次通过探针后写入 `agent_endpoint`；配置声明的 ID、Card 内 ID、服务身份和 `server_agent_id` 必须一致。

### 24.3 Compose 实现基线

`opspilot-server` 不再内嵌五个专业 Agent 的 HTTP endpoint。专业服务使用同一镜像的 `/app/bin/opspilot-agent` 入口，示例基线：

```yaml
x-agent-common: &agent-common
  image: opspilot-server:local
  entrypoint: ["/app/bin/opspilot-agent"]
  networks: [opspilot-backend]
  restart: unless-stopped
  depends_on:
    db-migrate: {condition: service_completed_successfully}
    retrieval-inference: {condition: service_started}
  volumes:
    - agent-input:/datasets/input:ro

services:
  evidence-agent:
    <<: *agent-common
    environment:
      AGENT_ID: evidence-collector
      SERVER_PORT: 8081
      DB_USERNAME: a2a_evidence_agent
      DB_PASSWORD: ${A2A_EVIDENCE_DB_PASSWORD:?required}
      SERVICE_TOKEN: ${EVIDENCE_AGENT_TOKEN:?required}

  code-agent:
    <<: *agent-common
    environment:
      AGENT_ID: code-analysis
      SERVER_PORT: 8082
      DB_USERNAME: a2a_code_agent
      DB_PASSWORD: ${A2A_CODE_DB_PASSWORD:?required}
      SERVICE_TOKEN: ${CODE_AGENT_TOKEN:?required}

  knowledge-agent:
    <<: *agent-common
    environment:
      AGENT_ID: knowledge
      SERVER_PORT: 8083
      DB_USERNAME: a2a_knowledge_agent
      DB_PASSWORD: ${A2A_KNOWLEDGE_DB_PASSWORD:?required}
      SERVICE_TOKEN: ${KNOWLEDGE_AGENT_TOKEN:?required}

  diagnosis-agent:
    <<: *agent-common
    environment:
      AGENT_ID: diagnosis
      SERVER_PORT: 8084
      DB_USERNAME: a2a_diagnosis_agent
      DB_PASSWORD: ${A2A_DIAGNOSIS_DB_PASSWORD:?required}
      SERVICE_TOKEN: ${DIAGNOSIS_AGENT_TOKEN:?required}

  remediation-agent:
    <<: *agent-common
    environment:
      AGENT_ID: remediation
      SERVER_PORT: 8085
      DB_USERNAME: a2a_remediation_agent
      DB_PASSWORD: ${A2A_REMEDIATION_DB_PASSWORD:?required}
      SERVICE_TOKEN: ${REMEDIATION_AGENT_TOKEN:?required}
```

每个角色只能访问自己的 `opspilot_a2a` 行和允许的只读 Tool 数据；PostgreSQL 使用 RLS 或按 `server_agent_id` 的受控 Repository 谓词加数据库角色测试双重保证。专业 Agent 不使用 `opspilot_app_role`。

### 24.4 依赖冻结清单

正式功能编码前必须提交 `deployment/versions.lock.yaml`，至少包含：

```yaml
jdk: "21"
maven: "<verified>"
spring_boot: "<verified>"
agentscope_java: "<verified exact version>"
a2a_java_sdk: "<verified exact version>"
postgresql_image_digest: "sha256:<verified>"
pgvector_version: "<verified>"
infinity_image_digest: "sha256:<verified>"
embedding_model: "<id>"
embedding_revision: "<immutable revision>"
rerank_model: "<id>"
rerank_revision: "<immutable revision>"
```

不得使用 `latest`、浮动 minor、空 revision 或仅 tag 不含 digest 的基础设施镜像进入验收环境。

### 24.5 AgentScope Spike 门禁

Spike 必须在真实 Maven 模块中证明：

1. 锁定依赖可由空缓存解析并通过 License/SBOM 检查。
2. `ReActAgent` 可以接入项目自己的 Chat Model Adapter 和 Tool Adapter。
3. 结构化输出能够同时与 Tool calling 工作；无效输出可被捕获并限制为一次修复。
4. Middleware/事件能够记录每轮动作、Usage 和 checkpoint，而不持久化隐藏思考。
5. `AgentStateStore` 能以 `(serverAgentId,userId,sessionId)` 写入 PostgreSQL，并在进程重启后恢复。
6. max rounds、deadline、取消和重复动作指纹均能在运行时中止循环。
7. 中止后不会继续调用 Tool/Model；状态保存失败不会退化到内存 Store。

所有项必须有自动化测试。若框架 API 无法满足某项，只允许修改 `opspilot-agent-runtime-agentscope`；若必须改变 `opspilot-core` Port 或状态所有权，需要 ADR 并重新审查，不能静默绕过。

### 24.6 A2A Spike 门禁

使用锁定官方 Java SDK 或锁定官方 proto 生成对象，证明：

- 六张 Card 可分别获取并通过 v1.0 Schema；
- REST `send/stream/get/cancel/subscribe` 全部可调用；
- Task Store 和 stream 事件在服务重启后可恢复；
- 重复 messageId 同请求返回原 Task，不同 hash 返回冲突；
- Artifact v1 Schema、媒体类型、哈希和调用方身份被校验；
- 专业 Agent 角色无法写 `opspilot` 领域表；
- 抓包证明不存在 Spring Bean 进程内快捷调用。

### 24.7 模型探针门禁

Compose 的 shell `grep` 只能判断 HTTP 形状，不能作为最终 readiness。正式探针由 `retrieval-model-probe` 小程序完成，并输出版本化 JSON：

- `/models` 中存在准确 served name、model ID/revision；
- Embedding 至少重复调用三次，向量非空、长度一致、所有值有限，实际维度匹配配置；
- Rerank 对固定正/负样本返回所有原始 index、有限分数、正样本排名高于负样本；
- 并发请求不会串模型；
- 结果带镜像 digest、模型 revision、探针版本和时间；
- 任何断言失败都使一次性容器非零退出并阻止 Phase 0/部署资格门禁通过。该容器不作为 Server/Agent 的 `service_completed_successfully` 启动依赖；Server/Agent 自身执行等价探针：可达后确认的模型身份、revision、维度、协议或 required capability 不兼容时非零退出，配置合法但端点暂时不可达时保持 liveness UP、readiness DOWN，恢复后重新探针。

### 24.8 Phase 0 完成定义

以下全部满足后，项目才从“技术 Spike”进入“完整功能开发”：

- `versions.lock.yaml` 无占位符；
- AgentScope 和 A2A Spike 自动化测试通过；
- OpenAPI 和所有 JSON Schema 通过 lint/示例校验；
- ObservationBatch/EvidenceBundle 示例通过 Schema；Prometheus、Jaeger、JSONL、Actuator 和 Compose Adapter 通过第 27 章共享合同测试并证明 Source 可追溯；
- `.github/workflows/contracts.yml` 已实际执行上述校验，失败示例能够阻止合并；Maven 骨架建立后 `build-test.yml` 和 `security.yml` 也必须成为受保护分支必需检查；
- 空库 Flyway、前一版本升级、角色权限和单活动 Run 并发测试通过；
- 六个 Agent Card/endpoint 在 Compose 中 ready；
- 三个场景合同和评测公式已冻结；
- 一条最小 vertical slice 完成 `Incident → Evidence → RCA → Evaluation`，允许 RCA 为 `INCONCLUSIVE`，但不得使用 Mock Provider。
- 持续交付按第 26 章输出 Release Manifest；Phase 0 及后续任何 Workflow 均不得自动部署或保存目标环境部署凭证。
