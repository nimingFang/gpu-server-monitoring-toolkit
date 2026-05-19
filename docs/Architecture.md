# 架构设计文档 (Architecture)

## 技术选型决策

### 为什么用 CLI 探针模式？

| 维度 | CLI 探针 (本项目) | 常驻 Agent + 中心 Server |
|---|---|---|
| 部署复杂度 | 单个 fat-jar + cron，零依赖 | 需部署 Agent + Server，配服务发现 |
| 资源占用 | 按需启动，采集完即退出 | 常驻进程持续消耗内存 |
| 故障隔离 | 单次失败不影响下次 | Agent 崩溃需守护进程保活 |
| 适用场景 | 实验室/小集群 (< 20 节点) | 生产级大规模集群 |

**决策**: 本项目定位为实验室 GPU 服务器监控工具，目标节点数 < 10，CLI 探针模式零运维负担，且天然适配 cron 调度。

### 为什么用 mwiede 分支的 JSch？

原版 [JSch](http://www.jcraft.com/jsch/) (com.jcraft:jsch) 自 2018 年后停止维护：

- 不支持 OpenSSH 6.5+ 的 `ed25519` 密钥算法
- 不支持 `aes256-ctr` 等现代加密套件
- 存在多个已报告但未修复的高危 CVE

[mwiede/jsch](https://github.com/mwiede/jsch) 是社区唯一持续维护的分支：

- 支持 `ed25519` / `ecdsa` 等现代密钥
- 兼容 OpenSSH 8.x+ 的 `rsa-sha2-256/512` 签名
- 持续跟进 JDK 新版本 (含 JDK 21+ 兼容)

**决策**: 必须使用 mwiede 版本，否则无法连接主流 Linux 发行版 (Ubuntu 22.04+ / Rocky 9+) 的 SSH 服务。

## Runtime-Flow 数据流图

```
+-----------+     +-------------+     +----------+     +--------+     +--------+
| Scheduler | --> |  Collector  | --> |   SSH    | --> | Parser | --> | SQLite |
| (cron/    |     | (AgentMain) |     | Session  |     | (Regex) |     | (JDBC) |
|  Main)    |     +-------------+     +----------+     +--------+     +--------+
+-----------+          |                    |               |              |
       |               |                    |               |              |
       v               v                    v               v              v
  按 interval   1. OSHI 本地采集       ssh 远程执行    nvidia-smi       INSERT INTO
  触发采集      2. 调用 SSH 执行     nvidia-smi       文本解析为       gpu_metrics
                 nvidia-smi          返回原始文本     结构化对象       (每条记录一
                                                                    条 insert)
```

### 数据流步骤详解

```
Step 1 - Schedule
  Scheduler (ScheduledExecutorService 或外部 cron)
  按 agent.collect.interval (默认 15s) 触发 AgentMain.main()

Step 2 - Collect (Local via OSHI)
  OSHI HardwareAbstractionLayer 获取:
    - system.cpu.usage       → CentralProcessor.getSystemCpuLoad()
    - system.memory.available → GlobalMemory.getAvailable()

Step 3 - Collect (Remote via SSH)
  JSch 建立 SSH Session → ChannelExec 执行 "nvidia-smi --query-gpu=..."
  → 读取命令 stdout 原始文本流 (UTF-8)

Step 4 - Parse
  Regex 正则引擎解析 nvidia-smi 表格输出:
    - gpu.name              → GPU 产品名称
    - gpu.temperature       → 当前温度 (°C)
    - gpu.memory.used       → 已用显存 (MB)
    - gpu.memory.total      → 总显存 (MB)

Step 5 - Persist
  JDBC 连接本地 SQLite → INSERT INTO gpu_metrics(timestamp, ...)
  每条采集记录一行，带毫秒精度时间戳
```

## 核心类职责 (v0.1 规划)

```
com.monitor.agent
├── AgentMain.java            # 程序入口，串联采集→解析→入库流程
├── config/
│   └── AgentConfig.java      # 单例配置，读取 agent.properties (已实现)
├── collect/
│   ├── OshiCollector.java    # OSHI 本地指标采集器
│   └── SshCollector.java     # SSH 远程 nvidia-smi 执行器
├── parse/
│   └── NvidiaSmiParser.java  # nvidia-smi 输出正则解析器
├── store/
│   └── SqliteWriter.java     # SQLite JDBC 写入封装
└── model/
    └── GpuMetrics.java       # 单次采集的数据模型 (POJO)
```
