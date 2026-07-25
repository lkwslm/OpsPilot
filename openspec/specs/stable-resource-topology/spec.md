# Purpose

定义可观测资源的稳定逻辑身份、版本化拓扑及查询归属边界。

## Requirements

### Requirement: ResourceRef 使用稳定逻辑身份
系统 MUST 为 Target System、三个 Sample 服务、数据库、代理及外部依赖分配或读取稳定 `ResourceRef`，至少保持 `resourceId/resourceType/systemId/environment` 的合同；逻辑身份 MUST NOT 直接使用临时 IP、容器 ID、Pod 名或 Java 类名。

#### Scenario: 容器替换后逻辑服务身份不变
- **GIVEN** `inventory-service` 的容器实例被替换且网络地址和容器 ID 变化
- **WHEN** 系统重新注册资源并采集 Observation
- **THEN** 逻辑 SERVICE 的 `resourceId` 保持不变，新的瞬时实例通过独立资源关联且不会覆盖历史身份

### Requirement: 静态 Compose 拓扑具有版本和生效时间
系统 MUST 加载并持久化带版本与生效时间的静态 Compose 拓扑，以方向明确的关系表达服务到服务以及服务到数据库、代理和外部依赖的连接；拓扑切换 MUST 保留历史版本。

#### Scenario: 拓扑关系版本切换
- **GIVEN** 已存在一个生效的 Compose 拓扑版本和引用该版本的历史 Evidence
- **WHEN** 新拓扑版本在后续时间生效
- **THEN** 新查询使用新版本，历史 Evidence 仍可解析原关系与生效区间且方向不发生反转

### Requirement: 查询和 Evidence 受 Target/Run/Resource 归属约束
Adapter 查询 MUST 验证 Resource 属于当前 Target 和 Run 允许范围；Evidence MUST 能反查产生它的 Resource 与拓扑版本。跨 Target、跨 Run 或未知 Resource 的查询和关联 MUST 被稳定拒绝。

#### Scenario: 跨 Target 查询资源
- **GIVEN** 调用上下文属于 Target A，而查询 Resource 属于 Target B
- **WHEN** Source 选择或 Adapter 查询尝试执行
- **THEN** 系统在外部调用前拒绝请求并留下审计，不创建可被分析层引用的 Observation 或 Evidence
