# GPU Monitor Toolkit

面向 Linux 无头服务器（Headless Server）的轻量级 GPU 集群监控探针系统。通过 SSH 远程执行 `nvidia-smi` 命令采集 GPU 状态，结合 OSHI 采集宿主机指标，将数据持久化到 SQLite 供后续分析。

## 技术栈

| 组件 | 技术选型 | 用途 |
|---|---|---|
| 运行环境 | Java 17 | LTS 长期支持，跨平台兼容 |
| 系统指标采集 | OSHI 6.6+ | 纯 Java 实现，免装原生 Agent，跨平台 CPU/内存/磁盘采集 |
| SSH 远程命令 | JSch 0.2+ (mwiede 分支) | 唯一持续维护的 JSch 分支，支持 ed25519 等现代密钥算法 |
| 本地持久化 | SQLite (JDBC) | 零配置嵌入式数据库，适合探针单机场景 |
| 日志框架 | Logback 1.5+ | SLF4J 生态标准实现，支持异步刷盘 |
| 简化代码 | Lombok 1.18+ | 编译期生成 Getter/Builder，减少样板代码 |

## 项目结构

```
gpu-monitor-toolkit/
├── agent-cli/                    # CLI 探针模块 (Maven)
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/monitor/agent/
│       │   └── config/           # 配置层
│       │       └── AgentConfig.java
│       └── resources/
│           ├── agent.properties          # 真实配置 (不入库)
│           └── agent.properties.example  # 配置模板
├── docs/                         # 项目文档
├── scripts/                      # 辅助脚本
├── src/                          # 预留: 桌面端/Web 模块
└── .gitignore
```

## Roadmap

### v0.1 — 单机 CLI MVP (当前)
- [x] Maven 项目骨架 + 依赖管理 (OSHI / JSch / Logback / Lombok)
- [x] 单例配置类 `AgentConfig`，读取 `agent.properties`
- [x] SSH 远程执行 `nvidia-smi` 并解析 GPU 名称、温度、显存
- [ ] OSHI 本地采集 CPU 使用率、可用内存
- [ ] SQLite 建表与数据落库
- [ ] 单次采集命令行入口 `AgentMain.java`

### v0.2 — 定时调度 + 故障保护
- [ ] `ScheduledExecutorService` 实现可配置间隔的定时采集
- [ ] GPU 温度超阈值告警 (基于 `agent.gpu.threshold.temperature`)
- [ ] SSH 断连自动重试 + 指数退避
- [ ] 日志分级输出 (INFO/ERROR 分离)

### v0.3 — 多机监控 + 简易报表
- [ ] 支持多台 target 的配置与轮询
- [ ] SQLite 查询报表: 按小时/天聚合 GPU 温度趋势
- [ ] CSV 导出采集数据
- [ ] 探针自身健康检查 (内存占用、采集延迟)
