## ADDED Requirements

### Requirement: 九个 Tool 显式注册并冻结
系统 MUST 显式注册日志、指标、Trace、健康、拓扑、配置、代码、知识库和沙箱测试九个 Tool，并在能力校验后冻结 `ToolRegistry`；每次结果 MUST 只使用 `SUCCEEDED|EMPTY|DENIED|FAILED`，携带受控摘要及 Evidence/Artifact 引用而非厂商 DTO。

#### Scenario: Registry 与结果合同
- **GIVEN** 九个 Tool 的装配和共享合同套件
- **WHEN** 校验重复 ID、缺失 Tool、冻结后修改及成功/空/拒绝/失败结果
- **THEN** 只有完整且唯一的九 Tool Registry 可冻结，所有结果符合统一 Schema 和状态集合

### Requirement: 部署 revision 必须解析为完整 commit
代码分析 MUST 从受信 Release Manifest、image label/digest 或部署元数据解析 `resourceId → imageDigest → repositoryId + 完整 commit SHA`，并投影最小只读 `code_analysis_scope`；零个或多个未消歧 commit MUST 返回 `CODE_REVISION_UNRESOLVED`。

#### Scenario: 禁止回退 main 或本地目录
- **GIVEN** 部署元数据缺少唯一完整 commit SHA
- **WHEN** CodeAnalysis Agent 请求分析
- **THEN** 请求以 `CODE_REVISION_UNRESOLVED` 结束，不读取当前 `main`、任意 branch、Agent 本地目录或模型提供的 URL

### Requirement: GitHub 与 GitLab 统一物化不可变 CodeSnapshot
`GitHubCodeSourceAdapter` 与 `GitLabCodeSourceAdapter` MUST 通过专用冻结 Registry 把一个 `sourceId + repositoryId + commitSha` 物化为不可变 `CodeSnapshot(snapshotId,sourceId,sourceKind,adapterId/version,repositoryId,commitSha,manifestArtifactId/hash,retrievedAt)` 和不暴露给模型的只读 workspace；平台 DTO、URL 和凭证 MUST NOT 越过 Adapter。

#### Scenario: 同仓库提交跨平台语义一致
- **GIVEN** GitHub 与 GitLab fixture 中语义相同的 repository/完整 commit
- **WHEN** 两个 Adapter 分别物化快照
- **THEN** 产生相同领域语义、可核验 manifest/hash 和独立只读 workspace，且输出不包含平台凭证或任意 URL

### Requirement: 代码源物化执行安全限制
Code Source MUST 校验 host/repository allowlist、`connectionRef`、归档/文件数量与大小、路径穿越、符号链接、子模块/LFS、文件类型和临时 workspace；MUST 禁止 Git hook、仓库脚本和跨根路径，并在完成或失败后销毁 workspace。

#### Scenario: 恶意归档不能逃逸
- **GIVEN** 含 `..`、绝对路径或指向根目录外符号链接的仓库归档
- **WHEN** Adapter 尝试物化 CodeSnapshot
- **THEN** 物化失败且根目录外无文件写入，临时 workspace 被销毁并产生稳定 ChainFailure

### Requirement: 多仓库 provenance 不得合并丢失
一个 Source/Repository/commit MUST 对应一个 CodeSnapshot；一次调查涉及多个仓库时 MUST 分别分析并保留 `sourceId/repositoryId/commitSha/snapshotId/文件哈希`，MUST NOT 拼成无来源的单一根目录。

#### Scenario: 多仓库分别产生证据
- **GIVEN** 一个资源映射到两个已消歧 repository/commit
- **WHEN** 执行代码分析并规范化结果
- **THEN** 两个快照和 Finding/Evidence 分别保留 provenance，任一文件可回溯唯一仓库与 commit

### Requirement: CodeFinding 经规范化后才成为事实
Java Code Analyzer MUST 只接收已校验 CodeSnapshot 的受限只读 workspace，并返回携带 repository/commit/root Artifact、文件哈希、位置和 Analyzer 版本的 `CodeFinding`；只有 `EvidenceNormalizer.normalizeCode` 成功生成的 Evidence ID 才能进入 Diagnosis/Hypothesis/RCA。

#### Scenario: 原始 Finding 不能进入诊断
- **GIVEN** 一个未规范化或 provenance/hash 不完整的 CodeFinding
- **WHEN** Diagnosis 尝试引用该 Finding
- **THEN** 领域边界拒绝写入，只有完整 Finding 经 `normalizeCode` 后的 Evidence ID 可被接受

### Requirement: Maven Sandbox 仅执行审批白名单测试
Maven Sandbox MUST 只执行 Profile 与有效审批共同允许的 test suite ID，并施加网络、CPU、内存、时长、workspace 和写目录限制；HIGH_RISK、任意 Shell、仓库代码写入和网络扩权 MUST 始终拒绝。

#### Scenario: 任意命令即使审批也拒绝
- **GIVEN** 一个带有效审批但要求自定义 shell command 或修改源码的 Sandbox 请求
- **WHEN** 请求经过权限与风险策略
- **THEN** 返回 `DENIED`，Sandbox 进程不启动且审计不泄露命令中的 Secret

### Requirement: 代码源失败语义稳定区分
未配置受信代码源 MUST 返回 `CODE_SOURCE_NOT_CONFIGURED`，无法解析部署 revision MUST 返回 `CODE_REVISION_UNRESOLVED`，鉴权、超时、完整性或 Analyzer 故障 MUST 返回对应 `ChainFailure`；这些结果 MUST NOT 被映射为 `EMPTY` 或虚假成功。

#### Scenario: 未配置与无匹配严格区分
- **GIVEN** 分别缺少代码源配置，以及完整快照分析成功但没有匹配 Finding
- **WHEN** 执行 CodeSearchTool
- **THEN** 前者失败为 `CODE_SOURCE_NOT_CONFIGURED`，后者才返回 `EMPTY` 并保留快照与查询审计
