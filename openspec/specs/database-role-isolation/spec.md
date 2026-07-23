# database-role-isolation Specification

## Purpose

定义四个 PostgreSQL schema 及各运行身份的最小权限、行级隔离和 Ground Truth 边界。

## Requirements

### Requirement: 四 schema 与角色最小权限
系统 MUST 建立 `opspilot`、`opspilot_a2a`、`sample`、`opspilot_eval`，以及 migrator、app、Sample、Fault Lab、Evaluation 和五个专业 Agent 角色。长期进程 MUST NOT 获得 migrator 凭证，专业 Agent MUST NOT 使用 `opspilot_app_role`，所有 schema 与 future table MUST 撤销不需要的 `PUBLIC` 权限。

#### Scenario: 应用角色尝试管理 DDL
- **GIVEN** 使用 app 或专业 Agent 角色连接已迁移数据库
- **WHEN** 尝试执行 DDL、`CREATE EXTENSION` 或访问未授权 future table
- **THEN** PostgreSQL 返回权限错误且没有对象或权限被改变

### Requirement: Supervisor 与专业 Agent 权限分离
`opspilot_app_role` MUST 只获得 Server/Supervisor 所需 DML；专业 Agent MUST NOT 写 Incident、Run、Hypothesis 或 RCA 领域表。Code Agent MUST 只通过按 `runId + repositoryId` 限定的 `code_analysis_scope` 只读视图访问最小数据，零行或多个未消歧 commit MUST fail closed，且该角色 MUST NOT 读取底表。

#### Scenario: Code Agent 访问歧义 revision
- **GIVEN** 目标资源映射到零个或多个未消歧 commit
- **WHEN** Code Agent 查询 `code_analysis_scope` 并尝试读取底表
- **THEN** scope 查询拒绝或不返回可分析快照，底表查询由 PostgreSQL 拒绝

### Requirement: 专业 Agent 行级隔离
每个专业角色 MUST 只能访问自身 `server_agent_id` 的 `opspilot_a2a` 与 Agent runtime state 行，使用 RLS，或使用按 `server_agent_id` 的受控 Repository 谓词并叠加数据库角色测试；跨 Agent 查询或更新 MUST 返回零行或被拒绝并产生审计证据。

#### Scenario: 跨 server_agent_id 访问
- **GIVEN** 专业 Agent A 使用自己的数据库角色和会话上下文
- **WHEN** 查询或更新属于专业 Agent B 的 A2A Task、Message 或 runtime state
- **THEN** 操作返回零行或权限错误，B 的数据保持不变且审计可定位主体与目标

### Requirement: Sample、Fault Lab、Evaluation 与 Ground Truth 隔离
`sample_app_role` MUST 只访问 `sample`；Fault Lab MUST 只获得故障编排最小权限且不获得 Agent 身份。只有 `fault_lab_role` 与 `evaluation_role` MUST 能访问 `opspilot_eval`；Evaluation 对业务 schema MUST 只获得写 `evaluation_result` 和必要 Artifact 元数据的权限。app、Agent、Tool DataSource 和 model Context Builder MUST 无 Ground Truth 的 schema `USAGE`、表 `SELECT` 与卷路径权限。

#### Scenario: Ground Truth 全链路越权测试
- **GIVEN** app、专业 Agent、Tool 与 model Context Builder 的实际运行身份
- **WHEN** 分别尝试读取 `opspilot_eval` 表或 Ground Truth Artifact 路径
- **THEN** 每个非 Fault Lab/Evaluation 身份均在数据库或文件边界被拒绝，Ground Truth 内容不进入模型上下文

### Requirement: 权限矩阵必须覆盖允许与拒绝项
系统 MUST 为每个角色维护允许/拒绝 SQL 清单，并在锁定 PostgreSQL 镜像中验证当前表与 future table 默认权限；每个拒绝项 MUST 由真实数据库权限错误、零行隔离或显式 fail-closed 结果证明。

#### Scenario: 权限矩阵回归
- **GIVEN** 所有 migration 已应用且各角色凭证已创建
- **WHEN** Testcontainers 套件逐项执行角色权限矩阵
- **THEN** 所有允许项成功、所有拒绝项按预期失败，任何意外授权都会使套件失败
