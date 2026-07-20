# core-ports-evidence-boundary Specification

## Purpose
定义 Core 的窄 Port、代码快照链、Evidence 唯一规范化入口和受控扩展边界。

## Requirements

### Requirement: Provider Port 保持分离
Chat、Embedding 和 Rerank MUST 使用三个独立 Port，并以领域合同表达 deadline、identity、Usage 和错误结果；任何 Port MUST NOT 暴露厂商协议对象。

#### Scenario: 替换单一 Provider 测试实现
- **GIVEN** core 使用 Chat、Embedding 和 Rerank 测试 Port
- **WHEN** 只替换其中一个实现
- **THEN** 其他 Port 和 core 领域代码无需修改，厂商 DTO 不出现在编译依赖中

### Requirement: 窄 Port 与代码快照链
core MUST 分别定义 Tool、ObservabilitySource、CodeSource、Repository/UnitOfWork、Artifact、A2A Client、CodeAnalysis 和 SandboxRunner 窄接口；代码分析 MUST 固定为 `CodeSourceAdapter → CodeSnapshot → CodeAnalysisPort → CodeFinding`，Analyzer 不得访问托管平台凭证/API 或 Agent 宿主机任意路径。

#### Scenario: 两种托管平台统一物化快照
- **GIVEN** GitHub 和 GitLab 测试 Adapter 指向同一不可变 revision
- **WHEN** 分别读取代码源
- **THEN** 二者只返回同一 `CodeSnapshot` 领域类型，CodeAnalysis 只接受已校验的只读快照

### Requirement: 唯一 EvidenceNormalizer 入口
Runtime Observation、CodeFinding 和 KnowledgeResult MUST 通过唯一 `EvidenceNormalizer` 转换为不可变 Evidence，并统一记录 `provenanceRefs`。

#### Scenario: 三类输入规范化
- **GIVEN** 合法 Observation、CodeFinding 和 KnowledgeResult
- **WHEN** 分别提交到 Evidence 边界
- **THEN** 每个输入生成符合统一身份与 provenance 合同的 Evidence，不产生平行规范化路径

### Requirement: 分析只接受 Evidence ID
Diagnosis、Hypothesis 和 RCA MUST 只接受 Evidence ID；ObservationBatch、CodeFinding、KnowledgeResult 和厂商 DTO MUST 在编译边界及运行时 Schema 双重拒绝。

#### Scenario: 原始结果绕过 Evidence 边界
- **GIVEN** ObservationBatch、CodeFinding、KnowledgeResult 或厂商 DTO
- **WHEN** 调用 Diagnosis、Hypothesis 或 RCA 入口
- **THEN** 输入 fail closed 且不会生成诊断、假设或 RCA

### Requirement: 有界 Tool 结果合同
Tool 结果 MUST 使用 `SUCCEEDED/EMPTY/DENIED/FAILED`，并包含受控摘要、Artifact/Evidence 引用和稳定 `errorCode`；大正文不得放入返回 DTO。

#### Scenario: Tool 返回大正文
- **GIVEN** Tool 原始输出超过 DTO 大小边界
- **WHEN** 结果通过规范化与脱敏
- **THEN** 正文保存为受控 Artifact，只在 Tool 结果中返回摘要和引用

### Requirement: 稳定扩展描述而无通用 Registry
五类扩展点 MUST 定义稳定 descriptor/capability 接口，但本阶段 MUST NOT 实现通用 Registry、动态发现或 classpath 扫描。

#### Scenario: composition root 装配扩展
- **GIVEN** 一个满足 descriptor/capability 合同的测试扩展
- **WHEN** Server composition root 显式装配它
- **THEN** core 通过窄 Port 使用扩展，且不依赖运行时发现机制
