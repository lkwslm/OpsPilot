# 阶段 09：CI、持续交付与发布候选演练

## 目标

补齐并固化 GitHub Actions 的合同、构建、Compose、安全、真实模型、Evaluation 和 Release Candidate 流水线，生成可验证的不可变候选；流水线在 Draft Release 后停止，不执行任何环境部署。

## 前置门禁

- 阶段 08 的 15 Run、技术失败、调查效率、安全和性能证据全部通过。
- 所有依赖、镜像、模型、Scenario、Evaluation Profile 和数据库 baseline/target 可由不可变身份引用。

## 建设范围

1. 固化七个权威 Workflow：`contracts.yml`、`build-test.yml`、`compose-smoke.yml`、`security.yml`、`model-integration.yml`、`evaluation.yml`、`release-candidate.yml`，文件名和职责不变。
2. 配置 main Ruleset：禁止直接 push，至少一名非作者审查，必需检查含 contracts、build-test、postgres-integration、compose-smoke、security-policy；用失败样例证明阻止合并。
3. 区分 `PASSED/FAILED/BLOCKED/NOT_APPLICABLE/CANCELLED`；必需 Job 禁止 continue-on-error，模型/Runner/Secret 缺失只能 BLOCKED；`NOT_APPLICABLE` 只允许非必需 Job 按版本化规则使用并记录原因，`CANCELLED` 只有同 commit 后续成功运行才能替代。
4. 固定 Runner 信任边界：普通 PR 用托管 Linux；真实模型/故障实验用临时化专属 Runner；Fork 不获取 Secret/受信 cache/self-hosted Runner；模型 Secret 只访问测试 Provider/配额，Ground Truth 以独立只读凭证仅挂载给 Evaluation；Runner 不连生产网络、不挂载开发者目录、不持有生产凭证，Job 后清理容器/工作区/临时 Secret；RC Job 不被其他 Tag 隐式取消。
5. 固定供应链安全：默认只读权限、写权限按 Job 最小化、Action 锁完整 SHA、Maven Wrapper/JDK/versions.lock/base digest、Secret/SAST/依赖/License/镜像扫描和 SBOM；PR 标题、分支名、Issue 内容和模型输出不得未经类型校验与安全引用直接插入 Shell，Cache miss 必须可从空缓存构建且 Fork 不写受信 Cache。达到策略级别的已知漏洞或不兼容许可证必须阻止新增；例外必须包含到期时间、责任人和 ADR/风险接受记录。
6. 构建一次：同 sourceCommit 产生 JAR、复用六角色的 OpsPilot OCI、Fault Lab/Evaluation 镜像、合同包、Migration、SBOM、扫描报告、模型探针、15 Run 证据和 Release Manifest；后续正式版复用 RC digest，不重建。
7. 执行数据库交付门禁：空库、前一版本升级、旧应用兼容、新应用集成、单活动 Run、破坏性 DDL 扫描；首版 `destructiveChanges=false`，只生成运维命令/预检/回滚条件，不连接目标库。
8. Release Manifest 记录 commit/Tag/Workflow、全部制品 URI/SHA-256、versions.lock/JDK/base/model revision、DB migration、Evaluation Profile、Scenario version 和外部 Runbook Artifact，并分别记录 `CONTRACTS`、`BUILD_TEST`、`POSTGRES_INTEGRATION`、`COMPOSE_SMOKE`、`SECURITY`、`MODEL_INTEGRATION`、`A2A_INTEROPERABILITY`、`EVALUATION` 八个质量门禁；固定 `automaticDeployment=false`、`deploymentPerformed=false`。
9. 用指向受保护 main 历史提交的签名 Tag `v0.1.0-rc.1` 演练；只有全部必需门禁 PASSED 才生成 `READY_FOR_MANUAL_DEPLOYMENT` 和 Draft GitHub Release。
10. 审计所有 Workflow，证明不存在 deploy.yml、SSH/Kubeconfig/云部署/生产 DDL 凭证、远程 compose、kubectl、Helm、latest 提升、自动 active revision 变更或目标环境访问。
11. 完成根 README 和只含变量名/空值的 `.env.example`：本地 Compose 是唯一主路径，提供 config/up、健康、场景、SSE、报告、Evaluation 与清理命令；本地 PowerShell 命令必须有跨平台脚本、Maven 或容器入口等价物。删除数据卷必须单独标为破坏性动作并要求显式确认。

## 详细实施计划

### 工作包设计输入与依赖

| 工作包 | 设计/机器合同输入 | 直接依赖 |
| --- | --- | --- |
| 09-WP01 | CI/CD 七 Workflow 职责、触发与路径过滤规则 | 阶段 01/04 已建立的最小 Workflow |
| 09-WP02 | main Ruleset、必需检查和五状态语义 | 09-WP01 |
| 09-WP03 | Runner/Secret/Ground Truth/cache 信任边界 | 09-WP01、09-WP02 |
| 09-WP04 | 供应链、Action/依赖锁、扫描/SBOM 和 build-once | 09-WP03，阶段 08 全证据 |
| 09-WP05 | 数据库交付兼容性与禁止目标库连接规则 | 09-WP01，阶段 03 Migration |
| 09-WP06 | Release Manifest Schema、八门禁和制品身份 | 09-WP02、09-WP04、09-WP05 |
| 09-WP07 | 受保护 main、签名 RC Tag、Draft Release 停止点 | 09-WP06，阶段 08 全门禁 PASSED |
| 09-WP08 | 持续交付/人工部署边界和 no-deploy 约束 | 09-WP01～09-WP07 |
| 09-WP09 | 本地 Compose 主路径和人工部署/回滚原则 | 09-WP04～09-WP08 |

### 09-WP01：固化七个 Workflow 与触发依赖图

- **09-WP01.T1**：逐个实现 `contracts.yml`、`build-test.yml`、`compose-smoke.yml`、`security.yml`、`model-integration.yml`、`evaluation.yml`、`release-candidate.yml`，保持文件名和职责唯一。
- **09-WP01.T2**：按 PR/main/schedule/workflow_dispatch/RC tag 固化触发关系、依赖和超时；文档 PR 始终运行 contracts。
- **09-WP01.T3**：路径过滤只跳过纯文档的重型非必需 Job；维护一份影响 pom/Dockerfile/Compose/Flyway/Provider/Agent/Tool/Scenario/Evaluation/Workflow 的全门禁路径清单。
- **目标文件**：`.github/workflows/*.yml`、路径分类配置/测试、Workflow 依赖文档。
- **验证与证据**：Action lint、触发矩阵测试、典型路径变更的预期 Job 列表和超时配置。

### 09-WP02：落实 Ruleset 与统一状态语义

- **09-WP02.T1**：配置 main 禁止直接 push、至少一名非作者审查，以及 contracts/build-test/postgres-integration/compose-smoke/security-policy 必需检查。
- **09-WP02.T2**：为 Job/Manifest 统一 `PASSED/FAILED/BLOCKED/NOT_APPLICABLE/CANCELLED`，禁止必需 Job `continue-on-error`。
- **09-WP02.T3**：Secret/模型/Runner 缺失映射为 BLOCKED；NOT_APPLICABLE 只用于版本化规则允许的非必需项，CANCELLED 只由同 commit 后续成功运行替代。
- **目标文件**：Ruleset 配置/说明、status collector/Schema、Workflow condition。
- **验证与证据**：直接 push、缺审查、必需检查失败的阻断样例，以及五状态聚合测试。

### 09-WP03：固定 Runner、Secret 与 Cache 信任边界

- **09-WP03.T1**：普通 PR 使用托管 Linux；真实模型/故障/Evaluation 使用临时化专属 Runner，并在 Job 后清理容器、工作区和临时 Secret。
- **09-WP03.T2**：Fork 不获得 Secret、自托管 Runner或受信 cache 写权限；Ground Truth 凭证只读且仅给 Evaluation，模型凭证只访问测试 Provider/配额。
- **09-WP03.T3**：Runner 不连接生产网络、不挂载开发者目录、不持有生产凭证；RC Job 不因无关 Tag 被并发策略取消。
- **09-WP03.T4**：验证 cache miss 可从空缓存完成构建，cache key 纳入锁文件/平台摘要且不缓存敏感内容。
- **目标文件**：Workflow permissions/environment/concurrency/cache、Runner bootstrap/cleanup、Secret inventory。
- **验证与证据**：Fork 演练、空 cache 构建、网络/挂载/凭证审计和清理日志。

### 09-WP04：实施供应链检查与 build-once

- **09-WP04.T1**：所有 Workflow 默认只读权限、按 Job 最小授权；第三方 Action 锁完整 SHA，锁定 Maven Wrapper/JDK/versions.lock/base image digest。
- **09-WP04.T2**：运行 Secret、SAST、依赖、License、镜像扫描并生成 SBOM；达到策略级别的问题阻断，例外需到期时间/责任人/ADR。
- **09-WP04.T3**：对 PR 标题、分支、Issue、模型输出等不可信文本做类型校验，禁止直接拼进 Shell。
- **09-WP04.T4**：同 sourceCommit 一次生成 JAR、六角色复用的 OpsPilot OCI、Fault Lab/Evaluation OCI、合同包、Migration、SBOM/扫描报告并登记 digest。
- **目标文件**：build/security Workflow、锁文件、Dockerfile、SBOM/provenance 配置、policy/exception Schema。
- **验证与证据**：Action SHA 审计、扫描报告、SBOM、制品摘要一致性和从 RC 到正式版“不重建”验证。

### 09-WP05：建立数据库交付门禁

- **09-WP05.T1**：在隔离 PostgreSQL 上验证空库迁移、前一版本升级、旧应用兼容、新应用集成和单活动 Run 约束。
- **09-WP05.T2**：扫描破坏性 DDL；首版强制 `destructiveChanges=false`，失败时阻止 RC。
- **09-WP05.T3**：只生成目标环境预检、expand Migration、兼容性、回滚条件与人工命令模板，不连接目标数据库。
- **目标文件**：postgres-integration Job/scripts、migration policy/checker、DB delivery report。
- **验证与证据**：五项数据库测试、DDL 扫描和无目标 DB 凭证/连接审计。

### 09-WP06：生成可验证 Release Manifest

- **09-WP06.T1**：记录 commit/Tag/Workflow、全部制品 URI/SHA-256、versions.lock/JDK/base/model revision、Migration、Evaluation Profile、Scenario version。
- **09-WP06.T2**：分别汇总 `CONTRACTS/BUILD_TEST/POSTGRES_INTEGRATION/COMPOSE_SMOKE/SECURITY/MODEL_INTEGRATION/A2A_INTEROPERABILITY/EVALUATION` 八门禁。
- **09-WP06.T3**：验证每个 URI/digest 和报告 Schema，固定 `automaticDeployment=false`、`deploymentPerformed=false`，并引用外部人工 Runbook Artifact。
- **目标文件**：release manifest Schema/generator/validator、artifact index、gate status collector。
- **验证与证据**：Manifest Schema、哈希复算、缺门禁/伪造 URI 负向测试和八门禁来源映射。

### 09-WP07：演练签名 RC Tag 与 Draft Release

- **09-WP07.T1**：确认候选 commit 位于受保护 main 历史，创建并验证签名 `v0.1.0-rc.1` Tag 的演练流程。
- **09-WP07.T2**：RC Workflow 只复用 build-once 制品，运行全部必需门禁；任一非 PASSED 只产诊断 Artifact。
- **09-WP07.T3**：全部必需门禁 PASSED 后生成 `READY_FOR_MANUAL_DEPLOYMENT` 和 Draft GitHub Release，并在此终止。
- **09-WP07.T4**：演练使用非生产/隔离仓库或等价 dry-run 时，仍验证 Tag、Manifest、Draft Release 权限边界，不声称已发布正式版本。
- **目标文件**：release-candidate Workflow、Tag/attestation verification、Draft Release notes/template。
- **验证与证据**：失败门禁不出 Release、成功 RC 清单、签名验证和 Workflow 终止点日志。

### 09-WP08：审计“持续交付但不自动部署”边界

- **09-WP08.T1**：扫描 Workflow/repo，禁止 deploy.yml、SSH/Kubeconfig/云/生产 DDL 凭证、远程 compose、kubectl、Helm 和目标环境地址。
- **09-WP08.T2**：禁止 `latest` 提升、自动知识 active revision 变更、RC 后部署调用或任何目标环境访问。
- **09-WP08.T3**：证明 GitHub Release 只含带占位符的人工命令模板，Workflow 权限和日志中没有部署能力/凭证。
- **目标文件**：no-deploy policy/scanner、credential inventory、audit report。
- **验证与证据**：正/负 fixture 扫描、权限清单和“目标环境未变化”审计声明。

### 09-WP09：完成本地运行与人工交接文档

- **09-WP09.T1**：根 README 以 Compose 为唯一主路径，给出 config/up、四类健康、场景、SSE、报告、Evaluation 和普通清理命令。
- **09-WP09.T2**：`.env.example` 只保留变量名、说明和空值；PowerShell 操作必须有跨平台脚本、Maven 或容器等价入口。
- **09-WP09.T3**：卷删除与普通清理分离，标记破坏性并要求显式确认；从干净环境逐条执行 README。
- **09-WP09.T4**：制作独立人工部署 Runbook，描述 Manifest/digest/SBOM/证明验证、变更单、备份、expand、相同 digest 更新、Smoke 和兼容回滚。
- **目标文件**：`README.md`、`.env.example`、`scripts/**`/Maven 入口、Runbook Artifact。
- **验证与证据**：干净环境复现日志、跨平台命令检查、破坏性命令防误触和 Runbook 审阅记录。

## 阶段内执行顺序

1. 先完成 09-WP01～09-WP03，固定 Workflow、分支治理和执行信任边界。
2. 09-WP04、09-WP05 产出不可变制品与数据库报告，再由 09-WP06 汇总 Manifest。
3. 09-WP07 执行 RC 演练；09-WP08 必须证明流水线在 Draft Release 停止，09-WP09 完成可复现交接。

## 测试与证据矩阵

| 门禁域 | 必测内容 | 交付证据 |
| --- | --- | --- |
| Workflow/Ruleset | 触发、路径过滤、依赖、必需检查、五状态 | lint、触发矩阵和阻断样例 |
| 信任/供应链 | Fork、Runner、Secret/cache、Action SHA、扫描、SBOM | 权限审计、扫描报告和 SBOM |
| 制品/数据库 | build-once digest、空库/升级/兼容/DDL | 制品索引和 DB delivery report |
| RC/Manifest | 八门禁、签名 Tag、Manifest 哈希、Draft Release | RC run、Manifest 和签名证明 |
| 无部署 | 无凭证/目标访问/部署命令，两个 deployment flag 为 false | no-deploy 审计报告 |
| 可复现交接 | README 干净环境、跨平台、破坏性清理隔离、Runbook | 复现日志与人工审阅记录 |

## 主要输出

- 七个权威 Workflow、main Ruleset 配置证据和负责人/超时；
- 不可变 JAR/OCI/合同/Migration/SBOM/证明/测试证据；
- 通过 Schema 的 Release Manifest 和外部人工部署 Runbook Artifact；
- `v0.1.0-rc.1` Draft Release 演练记录；
- 最终本地运行 README、空值 `.env.example` 和跨平台命令入口；
- “流水线未部署、目标环境未变化、无目标凭证”的审计证据。

## 完成门禁

- PR、main、定时、手工和 RC 触发关系与第 26.2 节一致；文档 PR 也始终运行 contracts。
- 路径过滤只减少纯文档变更的重型非必需 Job；任何影响 pom、Dockerfile、Compose、Flyway、Provider、Agent、Tool、Scenario、Evaluation 或 Workflow 的变更运行完整 PR 门禁。
- 所有第三方 Action 锁定 SHA，空 cache 可构建，日志/Artifact/SBOM 无密钥、敏感 Prompt 或完整模型响应。
- OCI 只能以 digest 部署/回滚，正式版本复用已通过 RC commit 与 digest。
- Migration 通过全部交付门禁，Manifest 的每个 Artifact 均可验证，所有必要状态为 PASSED。
- 任一必要门禁为 BLOCKED/FAILED/CANCELLED 时只生成诊断 Artifact，不生成可交付 Release 或 `READY_FOR_MANUAL_DEPLOYMENT`。
- Draft Release 后 Workflow 明确停止；没有自动部署 Job、环境凭证或目标环境访问。
- README 的本地主路径可从干净环境复现；破坏性卷清理不会被普通清理命令隐式执行。

## 人工交接边界

运维在 GitHub Actions 之外校验 Manifest/digest/SBOM/证明、创建变更单、备份、执行 expand Migration、更新相同 OCI digest、运行 Smoke 并记录/回滚；数据库回滚只使用预先验证的兼容路径，不自动执行破坏性 down migration。GitHub Release 中的命令模板只能含显式占位符，不能内嵌目标地址/凭证，也不能由 Workflow 执行。该过程不是本阶段 Workflow 的一部分；未来自动部署需求必须新 ADR、威胁模型、权限设计和恢复演练。

## 设计依据

- [部署与人工部署原则](../design/opspilot-system-design/10-deployment-and-roadmap.md)
- [CI、持续交付与发布治理](../design/opspilot-system-design/15-ci-cd-and-release-governance.md)
- [测试分层](../design/opspilot-system-design/09-test-strategy.md)
