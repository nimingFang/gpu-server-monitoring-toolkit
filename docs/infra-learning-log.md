# GPU 监控项目 — 基础设施与排障日志

> 核心理念：拒绝形式主义，只记录真实的基线与踩坑复盘。

## 一、 核心环境与安全基线 (v1.0)

### 1. Rocky 核心测试靶机配置
| 项 | 配置 |
|---|---|
| **OS / 架构** | Rocky Linux 10.1 (RHEL 系) / x86_64 |
| **网卡 / IP** | `ens160` / `192.168.160.140/24`（NAT 模式，网关: 192.168.160.2） |
| **高权账号** | `fang`（已加入 `wheel` 组，具备 sudo 审计提权能力） |

### 2. 安全加固策略
| 项 | 配置 |
|---|---|
| **SSH 访问控制** | 已禁用 Root 远程直连（`PermitRootLogin no`） |
| **身份认证防御** | 彻底禁用密码（`PasswordAuthentication no`），仅限 RSA 4096 位密钥对登录 |
| **操作审计** | 强制使用普通用户（`fang`）+ `sudo` 提权执行特权命令 |

---

## 二、 实战与排障流水账 (按时间倒序)

### 2026-05-18：容器网络穿透与 Docker Compose 编排打通
* **故障现象**：未配置 Docker 端口映射前，宿主机防火墙已放行 80 端口，但外部 Windows 探测时遭遇 `Connection refused` (L7 无进程监听)。
* **处理 SOP**：
  1. **查宿主机监听**：`ss -tulnp` (确认无端口冲突)
  2. **打通宿主机防火墙**：
     - `sudo firewall-cmd --add-port=8080/tcp --permanent`
     - `sudo firewall-cmd --reload`
  3. **声明式编排**：在 `~/gpu-monitor-toolkit/scripts` 编写 `docker-compose.yml`，执行 `docker compose up -d` 映射 8080:80。
  4. **闭环验证**：Windows 执行 `curl.exe -v http://192.168.160.140:8080`，成功拿到 `200 OK`。