# artifact-lifecycle-storage Specification

## Purpose

定义本地卷 Artifact 的受控路径、原子写入、授权读取、保留、删除与对账合同，确保对象内容和关系元数据可验证且不可越权。

## Requirements

### Requirement: 受控路径与 Artifact 身份解析
`ArtifactAccessService` MUST 通过受控根目录将 `artifactId` 解析为 `storageProvider/objectKey`，并拒绝绝对路径、`..`、设备文件及符号链接逃逸。业务记录 MUST 只持有 `artifactId`，不得直接持有可任意访问的文件路径。

#### Scenario: 路径与符号链接逃逸
- **GIVEN** object key 包含绝对路径、父目录跳转、设备文件或指向根目录外的符号链接
- **WHEN** 请求保存或读取 Artifact
- **THEN** 服务在文件访问前拒绝请求，受控根目录外的内容没有被读取或修改

### Requirement: 原子写入与不可覆盖内容寻址元数据
写入 MUST 先进入受控临时文件，流式计算 SHA-256、大小和媒体类型，再原子移动并登记 `storage_provider/object_key/uri/sha256/size_bytes/media_type/access_level/lifecycle_status/expires_at`。同一 `artifactId` 的内容寻址元数据 MUST NOT 被覆盖；内容更新 MUST 生成新 Artifact。

#### Scenario: 重写已有 artifactId
- **GIVEN** 一个已登记且对象完整的 Artifact
- **WHEN** 调用方尝试以不同内容覆盖相同 `artifactId`
- **THEN** 写入被拒绝，原对象与元数据保持不变，并要求创建新 Artifact 身份

#### Scenario: 原子移动前失败
- **GIVEN** 临时文件已写入但正式对象尚未原子移动
- **WHEN** 注入进程或 I/O 故障
- **THEN** 不出现指向半写对象的可用元数据，残留临时对象可由安全对账识别

### Requirement: 受权且可验证的流式读取
读取 MUST 校验访问级别、调用方的 Run/Task 归属、对象 SHA-256 与大小；大文件 MUST 使用受控流和上限。任何越权、哈希漂移、大小漂移或超限 MUST fail closed。

#### Scenario: 越权或完整性失败
- **GIVEN** 调用方无目标 Run 权限，或对象内容与登记哈希/大小不一致
- **WHEN** 请求读取 Artifact
- **THEN** 服务不返回对象正文并记录脱敏的稳定错误与审计关联

### Requirement: 冻结的默认保留策略
系统 MUST 默认保留 Incident/Run/审计 30 天、原始观测 Artifact 14 天、RCA/Evaluation 90 天；Ground Truth MUST 永久保留到对应场景版本废弃。保留计算 MUST 使用可注入时钟并允许测试边界时刻。

#### Scenario: 不同类别到期判定
- **GIVEN** 四类 Artifact 分别处于到期前、到期时和 Ground Truth 场景仍有效状态
- **WHEN** 生命周期任务计算候选删除集合
- **THEN** 仅已到期且不受保护的普通 Artifact 入选，仍有效 Ground Truth 不入选

### Requirement: 引用保护的两阶段删除
删除 MUST 执行 `DELETE_PENDING → 删除对象 → DELETED`，并在进入待删除前检查保留期与业务引用。对象删除失败 MUST 可重入，受保护 Artifact MUST NOT 被删除。

#### Scenario: 对象删除失败后重试
- **GIVEN** Artifact 已进入 `DELETE_PENDING` 且首次对象删除失败
- **WHEN** 生命周期任务再次运行
- **THEN** 任务安全重试同一对象，不重复破坏引用，并只在对象确认删除后写入 `DELETED`

### Requirement: 孤儿与漂移对账
对账任务 MUST 区分对象无元数据、元数据无对象和哈希漂移并告警；MUST NOT 自动删除受保护数据或把完整性异常伪装为成功删除。

#### Scenario: 三类不一致对账
- **GIVEN** 分别存在孤儿对象、缺失对象元数据记录和哈希漂移对象
- **WHEN** 执行对账任务
- **THEN** 生成可区分且可关联的告警，受保护数据保持不变并等待显式处置
