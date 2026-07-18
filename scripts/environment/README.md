# 项目本地环境

所有 OpsPilot 任务工具链、缓存、模型和临时文件必须位于项目根目录的 `.tmp/`，不得在 C 盘创建项目环境。

在 PowerShell 中先执行：

```powershell
. .\scripts\environment\enter-project-env.ps1
```

脚本会把 Java 临时目录、Maven 本地仓库、npm/pip 缓存、Hugging Face/Torch 模型缓存和 Docker CLI 配置指向 `E:\Code\OpsPilot\.tmp\`。项目固定使用 Eclipse Temurin `21.0.11+10`，下载包 SHA-256 为 `d3625e7cadf23787ea540229544b6e2ab494b3b54da1801879e583e1dfee0a64`；放在 `.tmp/toolchains/jdk-21*` 后会被自动选中。

Docker Desktop daemon 的全局镜像磁盘位置不由此脚本控制；项目命令不会主动修改 Docker Desktop 的全局设置。

## 项目本地 Secret

Phase 0 DeepSeek 能力探针从以下项目内文件读取 API Key：

```text
E:\Code\OpsPilot\.tmp\secrets\deepseek-api-key.txt
```

`enter-project-env.ps1` 仅在该文件存在且非空时把内容载入当前进程的 `DEEPSEEK_API_KEY`，不会打印内容。`.tmp` 已被 Git 忽略并位于 E 盘；不得把 Key 写入 YAML、报告、日志或源码。
