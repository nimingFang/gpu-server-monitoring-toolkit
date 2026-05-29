# GPU Monitor Toolkit — Agent 探针端

> 面向 Linux 无头服务器的轻量级 GPU 集群监控探针。SSH 零侵入采集，嵌入式时序存储，守护进程常驻调度。

<p align="left">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Version-v0.2-blue" alt="v0.2">
  <img src="https://img.shields.io/badge/License-MIT-green" alt="MIT">
  <img src="https://img.shields.io/badge/Build-Maven%20Shade-C71A36?logo=apachemaven" alt="Maven Shade">
  <img src="https://img.shields.io/badge/Storage-SQLite-003B57?logo=sqlite&logoColor=white" alt="SQLite">
</p>

---

## 项目初衷

在 GPU 集群管理场景中，运维团队通常依赖 Prometheus + Node Exporter + DCGM 等重型技术栈。但对于 **5~20 台规模的实验室 / 边缘 GPU 节点**，这套体系存在三个痛点：

- **侵入性过高**：每台 target 都要装 Agent、配 yaml、开端口，运维成本远超业务价值。
- **部署门槛陡峭**：整套监控栈涉及 4+ 组件，对非 SRE 背景的算法研究员极不友好。
- **断网数据丢失**：中心化存储依赖网络，GPU 满载时若网络抖动，整段温度曲线出现空白缺口。

本项目的设计哲学是 **"ssh 进去，metrics 出来"** — 探针只需在一台机器上运行，通过 JSch 远程执行 `nvidia-smi`，本机 OSHI 采集宿主机指标，SQLite 本地固化，零 Agent 入侵 target，一个 Fat-Jar 搞定一切。

---

## 核心特性

### 无侵入式跨节点巡检

宿主机指标通过 OSHI JNA 直读 `/proc` 伪文件系统，GPU 指标通过 JSch (mwiede 分支) `ChannelExec` 远程执行 `nvidia-smi --query-gpu=...`，精准解析 CSV 输出。target 无需安装任何额外软件，仅需开启 SSH 服务。

### 常驻守护进程设计

摒弃 `while(true) { Thread.sleep() }` 的粗暴死循环，基于 `ScheduledExecutorService` 单线程池实现固定频率调度。单线程池天然保证采集任务串行化，无需额外锁机制保护 SQLite 写入。外层 `catch(Throwable)` 防线程静默死亡，调度循环永不停摆。

### 高可用网络容错

- **指数退避重试**：单次 SSH 命令内置最多 3 次重试（2s → 4s），避免瞬时网络抖动导致采集断层。
- **局部失败容忍**：SSH 故障只影响 GPU 列，CPU/内存数据照常落库，时序曲线不出现全行空缺。
- **重试与执行分离**：连接级异常（`JSchException` / `IOException`）触发重试，业务级失败（`exitCode != 0`）直接返回错误详情，不浪费重试配额。

### 优雅停机

深度接入 JVM Shutdown Hook。收到 `SIGTERM` / `Ctrl+C` 后：

1. 不再接受新的采集任务。
2. 等待当前正在执行的采集写入完成（最长 30 秒超时）。
3. `finally` 块保证 SQLite `INSERT` 完整落盘。
4. SSH Channel → Session 逐级断开，TCP 端口不残留 TIME_WAIT。

从此跟 `kill -9` 导致的 `"database disk image is malformed"` 说再见。

### 边缘嵌入式存储

使用 SQLite JDBC 替代 MySQL / InfluxDB，零配置、零守护进程。数据库文件 `gpu_metrics.db` 即单一文件，断网不丢数据，便于 `scp` 导出后在本地做分析。PreparedStatement 参数化查询防止 SQL 注入。

### 运维级工程交付

| 能力 | 实现 |
|---|---|
| **一键启动** | Fat-Jar 打包全部依赖，`java -jar agent-cli.jar` 即运行 |
| **日志规范** | Logback `SizeAndTimeBasedRollingPolicy`，每天/10MB 滚动，保留 7 天，压缩归档 |
| **OSHI 噪音屏蔽** | `<logger name="oshi" level="WARN"/>` 滤除 JNA 调试日志 |
| **MVP 告警** | 单次 GPU 温度超阈值（`alert.gpu.temp.threshold`），`log.error` 醒目输出 |
| **防乱码** | 启动参数 `-Dfile.encoding=UTF-8`，代码内全部 `StandardCharsets.UTF_8` |

---

## 快速开始

### 1. 配置

```bash
cp agent-cli/src/main/resources/agent.properties.example \
   agent-cli/src/main/resources/agent.properties
```

编辑 `agent.properties`：

```properties
# SSH 连接信息
ssh.host=192.168.160.140
ssh.port=22
ssh.user=fang
ssh.password=YOUR_PASSWORD_HERE

# 采集间隔 (秒)
agent.collect.interval=15

# GPU 温度告警阈值 (°C)
alert.gpu.temp.threshold=85
```

> `agent.properties` 已在 `.gitignore` 中排除，密码不会入库。生产环境建议使用密钥认证。

### 2. 打包

```bash
cd agent-cli
mvn clean package
```

输出：`target/agent-cli-0.1-SNAPSHOT.jar`（Fat-Jar，含全部依赖）。

### 3. 启动

```bash
java -Dfile.encoding=UTF-8 -jar target/agent-cli-0.1-SNAPSHOT.jar
```

启动后立即执行首次采集，之后每 15 秒执行一次。日志同时输出到控制台和 `logs/agent.log`。

### 4. 停止

按 `Ctrl+C` 或发送 `SIGTERM`（`kill <pid>`），探针将等待当前采集完成后体面退出。

---

## 核心目录结构

```
gpu-monitor-toolkit/
├── agent-cli/                              # CLI 探针 (Maven 模块)
│   ├── pom.xml                             # 依赖管理 + maven-shade-plugin
│   └── src/main/
│       ├── java/com/monitor/agent/
│       │   ├── AgentMain.java              # [入口] Daemon 编排: 定时调度 + 优雅停机
│       │   ├── config/
│       │   │   └── AgentConfig.java        # [配置] 单例, 读取 agent.properties
│       │   ├── collect/
│       │   │   └── OshiCollector.java      # [采集] CPU tick 差值计算 + 可用内存
│       │   ├── ssh/
│       │   │   └── SshConnectionManager.java # [通信] JSch exec + 指数退避重试
│       │   ├── parse/
│       │   │   └── NvidiaSmiParser.java    # [解析] CSV→GpuMetrics, Fail-Fast
│       │   ├── model/
│       │   │   └── GpuMetrics.java         # [模型] GPU 指标 POJO
│       │   ├── store/
│       │   │   └── SqliteWriter.java       # [存储] JDBC 建表 + PreparedStatement 写入
│       │   └── exception/
│       │       └── MetricsParseException.java # [异常] 携带 rawData 的可追溯异常
│       └── resources/
│           ├── agent.properties            # 真实配置 (不入库)
│           ├── agent.properties.example    # 配置模板
│           └── logback.xml                 # 日志: 控制台着色 + 滚动文件
├── docs/                                   # 架构设计 + 指标字典
├── scripts/                                # 辅助脚本 (预留)
├── src/                                    # 预留: Spring Boot Server 端
└── .gitignore
```

---

## 演进路线

- [x] **v0.1 — 单机 CLI MVP**：跨节点 SSH 采集 → CSV 解析 → Fail-Fast 容错 → SQLite 单次落盘。
- [x] **v0.2 — 常驻守护进程**：`ScheduledExecutorService` 定时调度、JVM Shutdown Hook 优雅停机、指数退避重试、超阈值 MVP 告警、Logback 滚动日志。*(当前版本)*
- [ ] **v0.3 — Client-Server 架构**：Spring Boot 服务端统一接入多探针数据，RESTful API 查询，Grafana Dashboard 可视化 GPU 温度曲线与显存趋势。
