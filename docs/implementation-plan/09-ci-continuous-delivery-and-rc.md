# 阶段 09：CI、持续交付与发布候选演练

## 目标

补齐并固化 GitHub Actions 的合同、构建、Compose、安全、真实模型、Evaluation 和 Release Candidate 流水线，生成可验证的不可变候选；流水线在 Draft Release 后停止，不执行任何环境部署。

## 前置门禁

- 阶段 08 的 15 Run、技术失败、调查效率、安全和性能证据全部通过。
- 所有依赖、镜像、模型、Scenario、Evaluation Profile 和数据库 baseline/target 可由不可变身份引用。

## 实施内容

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
