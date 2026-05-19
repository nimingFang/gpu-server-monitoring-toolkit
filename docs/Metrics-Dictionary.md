# 监控指标字典 (Metrics Dictionary)

## 概述

本文档定义 GPU Monitor Toolkit 采集的所有监控指标。每个指标包含：名称、数据类型、单位、采集源、以及对应的 OSHI API 或解析方式。

## 宿主机指标 (Host Metrics) — 采集源: OSHI

### system.cpu.usage

| 属性 | 值 |
|---|---|
| 指标全名 | `system.cpu.usage` |
| 中文描述 | 系统 CPU 使用率 |
| 数据类型 | `double` |
| 取值范围 | 0.0 ~ 1.0 (归一化值，1.0 表示 100%) |
| 单位 | 比率 (ratio) |
| 采集源 | OSHI — `CentralProcessor.getSystemCpuLoad()` |
| 计算方式 | OSHI 内部通过两次间隔采样的 `/proc/stat` 差值计算 |
| 采集间隔 | 由 `agent.collect.interval` 控制 (默认 15s) |

> **注意**: `getSystemCpuLoad()` 调用时会基于上次调用时间计算差值，因此连续两次调用之间需要时间间隔才能返回有效值。若调用间隔 < 1s，可能返回 -1（即数据不可用）。

### system.memory.available

| 属性 | 值 |
|---|---|
| 指标全名 | `system.memory.available` |
| 中文描述 | 系统可用内存 |
| 数据类型 | `long` |
| 单位 | 字节 (bytes) |
| 采集源 | OSHI — `GlobalMemory.getAvailable()` |
| 底层来源 | Linux `/proc/meminfo` 中的 `MemAvailable` 字段 |
| 采集间隔 | 由 `agent.collect.interval` 控制 (默认 15s) |

> **注意**: `MemAvailable` 不同于 `MemFree`。Linux 内核会利用空闲内存做文件缓存 (page cache)，这些缓存可在应用申请内存时立即释放，因此 `MemAvailable` 更准确地反映了可供新进程使用的内存量。

## GPU 指标 (GPU Metrics) — 采集源: nvidia-smi (SSH 远程执行)

> GPU 指标将在 v0.1 后续迭代中加入。以下为规划中的指标：

### gpu.temperature (v0.1 待实现)

| 属性 | 值 |
|---|---|
| 指标全名 | `gpu.temperature` |
| 中文描述 | GPU 核心温度 |
| 数据类型 | `int` |
| 单位 | 摄氏度 (°C) |
| 采集源 | SSH 远程执行 `nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader` |
| 告警阈值 | 默认 85°C，由 `agent.gpu.threshold.temperature` 配置 |

### gpu.memory.used (v0.1 待实现)

| 属性 | 值 |
|---|---|
| 指标全名 | `gpu.memory.used` |
| 中文描述 | GPU 已用显存 |
| 数据类型 | `long` |
| 单位 | MiB (兆字节) |
| 采集源 | SSH 远程执行 `nvidia-smi --query-gpu=memory.used --format=csv,noheader` |

### gpu.memory.total (v0.1 待实现)

| 属性 | 值 |
|---|---|
| 指标全名 | `gpu.memory.total` |
| 中文描述 | GPU 总显存 |
| 数据类型 | `long` |
| 单位 | MiB (兆字节) |
| 采集源 | SSH 远程执行 `nvidia-smi --query-gpu=memory.total --format=csv,noheader` |

## SQLite 表结构 (规划)

```sql
CREATE TABLE IF NOT EXISTS gpu_metrics (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   TEXT    NOT NULL,           -- ISO 8601 格式, 例: 2026-05-19T11:20:00.123+08:00
    host        TEXT    NOT NULL,           -- 来源主机 SSH host
    cpu_usage   REAL,                       -- system.cpu.usage (可为 NULL, SSH 采集失败时)
    mem_avail   INTEGER,                    -- system.memory.available (bytes)
    gpu_name    TEXT,                       -- GPU 型号名称
    gpu_temp    INTEGER,                    -- GPU 温度 (°C)
    gpu_mem_used INTEGER,                   -- GPU 已用显存 (MiB)
    gpu_mem_total INTEGER                   -- GPU 总显存 (MiB)
);
```
