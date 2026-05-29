package com.monitor.agent;

import com.monitor.agent.collect.OshiCollector;
import com.monitor.agent.config.AgentConfig;
import com.monitor.agent.exception.MetricsParseException;
import com.monitor.agent.model.GpuMetrics;
import com.monitor.agent.parse.NvidiaSmiParser;
import com.monitor.agent.ssh.SshConnectionManager;
import com.monitor.agent.store.SqliteWriter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 探针守护进程入口 —— Daemon/Orchestrator，定时调度本地采集 → 远程采集 → 解析 → 落盘。
 *
 * <p>基于 {@link ScheduledExecutorService} 实现固定频率调度（默认每 15 秒一次）。
 * 通过 JVM ShutdownHook 实现优雅停机，Ctrl+C 或 SIGTERM 信号不会打断正在执行的采集，
 * 而是等待当前周期完成后安全退出。</p>
 *
 * <p>容错策略延续 v0.1：单周期内任何环节失败产生哨兵值（-1 / null），绝不中断调度循环。
 * 调度器本身额外受外层 try-catch(Throwable) 保护，防止未捕获异常导致线程静默死亡。</p>
 *
 * @author nimingFang
 * @since 0.2
 */
@Slf4j
public class AgentMain {

    private static final String GPU_QUERY_CMD =
            "nvidia-smi --query-gpu=name,temperature.gpu,memory.used,memory.total --format=csv,noheader,nounits";

    private record SystemMetrics(double cpu, long mem) {}

    public static void main(String[] args) {
        SqliteWriter writer = new SqliteWriter();
        writer.initDatabase();

        AgentConfig config = AgentConfig.getInstance();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        // 优雅停机：收到 SIGTERM/Ctrl+C 后不立即强制终止，而是等当前采集写入完成
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("收到停机信号，准备停止探针...");
            executor.shutdown();
            try {
                // 等待当前正在执行的采集任务自然完成，最长等待 30 秒
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    log.warn("等待超时，强制终止调度器");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("探针已安全退出");
        }, "shutdown-hook"));

        // 核心采集任务 —— 每周期完整执行 采集→解析→落盘 链路
        Runnable collectTask = () -> {
            // 外层 try-catch(Throwable) 是 ScheduledExecutor 的"安全带"：
            // scheduleAtFixedRate 的线程池在 Runnable 抛出未捕获异常时会静默吞掉异常
            // 并停止后续调度，线程不报错、不恢复、不通知——你的探针就"隐式死亡"了。
            // 只有兜住所有 Throwable 才能保证调度循环永不停摆。
            try {
                SystemMetrics sys = new SystemMetrics(-1.0, -1L);
                GpuMetrics gpu = null;

                try {
                    log.info("=== GPU Monitor Toolkit 探针启动 ===");

                    sys = runLocalCollection();
                    gpu = runRemoteCollection();
                } catch (Exception e) {
                    log.error("[致命错误] 本周期异常: {}", e.getMessage());
                } finally {
                    writer.insertMetrics(sys.cpu(), sys.mem(), gpu);
                    log.info("[AgentMain] 本次采集周期结束，数据已落盘。");
                }
            } catch (Throwable t) {
                log.error("[调度器防死] 捕获到未预期的 Throwable，调度循环继续运行", t);
            }
        };

        // 首次无延迟启动，后续按配置间隔执行
        executor.scheduleAtFixedRate(collectTask, 0, config.getCollectInterval(), TimeUnit.SECONDS);
        log.info("调度器已启动，采集间隔: {} 秒", config.getCollectInterval());

        // 保持 main 线程存活，否则 JVM 会在 scheduleAtFixedRate 返回后直接退出
        synchronized (AgentMain.class) {
            try {
                AgentMain.class.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static SystemMetrics runLocalCollection() {
        log.info("--- 本地 OSHI 采集 ---");

        double cpu = -1.0;
        long mem = -1L;

        try {
            cpu = OshiCollector.collectCpuUsage();
            log.info("CPU 使用率: {}%", String.format("%.2f", cpu >= 0 ? cpu : 0.0));
        } catch (Exception e) {
            log.warn("CPU 采集失败: {}", e.getMessage());
        }

        try {
            mem = OshiCollector.collectAvailableMemory();
            double memGB = mem / (1024.0 * 1024.0 * 1024.0);
            log.info("可用内存: {} GB", String.format("%.2f", memGB));
        } catch (Exception e) {
            log.warn("内存采集失败: {}", e.getMessage());
        }

        return new SystemMetrics(cpu, mem);
    }

    private static GpuMetrics runRemoteCollection() {
        log.info("--- 远程 SSH 采集 ---");

        SshConnectionManager ssh = new SshConnectionManager();
        String result = ssh.executeCommand(GPU_QUERY_CMD);

        if (result.startsWith("[执行失败]") || result.startsWith("SSH连接失败") || result.startsWith("读取命令输出时IO异常")) {
            log.warn("SSH 执行未成功，跳过本次 GPU 解析");
            log.warn("  详情: {}", result.lines().findFirst().orElse("无"));
            return null;
        }

        try {
            GpuMetrics gpu = NvidiaSmiParser.parse(result);
            log.info("GPU 型号: {}", gpu.getGpuName());
            log.info("GPU 温度: {}°C", gpu.getTemperature());
            log.info("GPU 显存: {} / {} MiB", gpu.getMemoryUsed(), gpu.getMemoryTotal());

            // MVP 告警：单次超阈值即触发 log.error，v0.3 引入 N 次连续超阈值才触发的去抖动机制
            int threshold = AgentConfig.getInstance().getGpuTempThreshold();
            if (gpu.getTemperature() > threshold) {
                log.error("[ALARM] GPU 温度严重超标! 当前: {}°C, 阈值: {}°C, 型号: {}",
                        gpu.getTemperature(), threshold, gpu.getGpuName());
            }
            return gpu;
        } catch (MetricsParseException e) {
            log.warn("GPU 数据解析失败: {}", e.getMessage());
            return null;
        }
    }
}
