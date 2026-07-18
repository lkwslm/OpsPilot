# phase0-project-foundation Specification

## Purpose
TBD - Defines the Phase 0 contract and acceptance criteria for phase0-project-foundation.

## Requirements

### Requirement: 可复现的 Maven 模块骨架
系统 MUST（必须）提供 JDK 21、Maven Wrapper 和根聚合工程，并且只创建设计第 28.3 节冻结的物理模块。构建必须统一 UTF-8、测试插件版本和插件版本声明规则，core 不得依赖 Spring、AgentScope、A2A SDK、JPA 或厂商包。

#### Scenario: 从空缓存构建骨架
- **GIVEN** 一台仅具备锁定 JDK 的干净构建环境和空 Maven 缓存
- **WHEN** 执行 `./mvnw -B -ntp verify`
- **THEN** 所有冻结模块成功解析和构建，架构测试确认 core 未导入被禁止的框架或厂商类型

#### Scenario: 非法依赖方向被拒绝
- **GIVEN** core 中引入一个 Spring、AgentScope、A2A SDK、JPA 或厂商包类型
- **WHEN** 执行架构测试
- **THEN** 构建以非零退出码失败并指出非法依赖

### Requirement: 不可变版本清单
系统 MUST（必须）在 `deployment/versions.lock.yaml` 中记录精确依赖版本、基础镜像 digest 和模型 ID/revision，不得包含空值、占位符、`latest`、浮动 minor 或只有 tag 没有 digest 的基础设施镜像。

#### Scenario: 合法版本锁通过
- **GIVEN** 所有必填版本、镜像 digest 和模型 revision 均为不可变值
- **WHEN** 执行版本清单校验
- **THEN** 校验成功并输出清单内容哈希

#### Scenario: 浮动身份被拒绝
- **GIVEN** 版本清单分别包含占位符、浮动 minor、`latest` 或无 digest 镜像
- **WHEN** 对每个负向样例执行同一校验入口
- **THEN** 每个样例均以非零退出码失败且报告对应字段

### Requirement: 供应链证据
系统 MUST（必须）从空 Maven 和容器缓存解析锁定依赖及镜像，并生成依赖树、License、SBOM 和镜像摘要证据，证据必须能关联到 commit 和版本清单哈希。

#### Scenario: 生成可追溯供应链证据
- **GIVEN** 版本清单已通过静态校验
- **WHEN** 从空缓存执行依赖和镜像解析
- **THEN** 生成依赖树、License、SBOM 与镜像 digest 报告，并记录 commit 和版本锁哈希
