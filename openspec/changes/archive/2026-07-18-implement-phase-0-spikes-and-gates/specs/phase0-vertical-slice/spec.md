## ADDED Requirements

### Requirement: 最小真实纵切
系统 MUST（必须）以真实 Provider 和真实 Source 打通最小 `Incident → Evidence → RCA → Evaluation`，专业 Agent 经真实 A2A HTTP+JSON 获取 Observation/Evidence；RCA 可以为 `INCONCLUSIVE` 且 `rootCause=null`，但不得访问 Ground Truth。

#### Scenario: 从 Incident 生成可评测 RCA
- **GIVEN** 一个最小 Incident、一个可用真实 Source 和已通过门禁的真实模型
- **WHEN** 执行单一纵切命令链
- **THEN** 系统持久化 Incident、Run、Observation、Evidence、结构化 RCA 和 Evaluation，所有跨 Agent 调用经过 A2A，且 Agent/RCA 不读取 Ground Truth

### Requirement: 独立确定性 Evaluation 与审计
Evaluation MUST（必须）独立且确定性地读取允许的 Artifact，保存 Incident、Evidence、RCA、Evaluation 的关联和调用审计；每个 Artifact/报告必须具有 URI、SHA-256、commit 和配置身份。

#### Scenario: 全链证据可追溯
- **GIVEN** 一次完成的最小纵切
- **WHEN** 从 Evaluation 反向追溯
- **THEN** 能定位 RCA、Evidence、原始 Artifact、调用审计、配置和 commit，所有哈希校验一致

### Requirement: 真实失败透明
系统 MUST（必须）分别注入模型、Source、A2A 和数据库故障，并证明不存在 Mock/Fake、固定结果或静默 Provider/Source/存储切换。

#### Scenario: 四类故障不被替代链路掩盖
- **GIVEN** 可独立触发四类真实依赖故障的环境
- **WHEN** 分别执行最小纵切
- **THEN** 每次运行以冻结的明确失败或允许的 `INCONCLUSIVE` 结束，不产生伪造 Evidence/RCA，也不切换到未声明实现

### Requirement: Phase 0 CI 和阶段 Manifest
Maven 骨架存在后 MUST（必须）提供 `build-test.yml` 和 `security.yml` 的最小检查，并与先行的 `contracts.yml` 一起生成符合 Schema、`automaticDeployment=false` 的阶段 Release Manifest；所有 Workflow 都不得部署环境。

#### Scenario: CI 只产出证据
- **GIVEN** Phase 0 commit 和全部阶段门禁结果
- **WHEN** 三个 GitHub Actions Workflow 执行
- **THEN** Workflow 只运行校验并上传带 URI、SHA-256、commit 和配置身份的证据与 Manifest，不持有目标环境凭证、不执行部署

### Requirement: 阶段完成必须全门禁通过
只有版本锁、合同、AgentScope、A2A、Chat/Embedding/Rerank、PostgreSQL/权限、五类 Adapter、六进程拓扑和真实纵切全部通过，Phase 0 才能标记完成；未闭环的外部版本、模型、资源或 API 项 MUST（必须）保持 `BLOCKED`。License 必须尽力解析并报告覆盖率，无法识别的组件记录 `WARNING`，不单独阻塞 Phase 0。

#### Scenario: 任一门禁失败阻止阶段完成
- **GIVEN** Phase 0 门禁矩阵中至少一项失败或缺少证据
- **WHEN** 生成阶段完成结论
- **THEN** Release Manifest 不得声明阶段完成，并明确列出失败或 `BLOCKED` 项
