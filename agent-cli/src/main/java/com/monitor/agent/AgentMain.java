package com.monitor.agent;

import com.monitor.agent.collect.OshiCollector;
import com.monitor.agent.exception.MetricsParseException;
import com.monitor.agent.model.GpuMetrics;
import com.monitor.agent.parse.NvidiaSmiParser;
import com.monitor.agent.ssh.SshConnectionManager;
import com.monitor.agent.store.SqliteWriter;
import lombok.extern.slf4j.Slf4j;

/**
 * 探针编排入口 —— Facade/Orchestrator，串联 本地采集 → 远程采集 → 解析 → 落盘。
 *
 * <p>采用<strong>部分失败容忍策略</strong>：任何一个环节的异常只记录日志并产生
 * 哨兵值（-1 / null），绝不中断后续步骤。最终无论成败，insertMetrics 必被调用，
 * 保证时序数据库不出现整行空缺。</p>
 *
 * @author nimingFang
 * @since 0.1
 */
@Slf4j
public class AgentMain {

    private static final String GPU_QUERY_CMD =
            "nvidia-smi --query-gpu=name,temperature.gpu,memory.used,memory.total --format=csv,noheader,nounits";

    /** 本地采集结果承载对象，Java 17 record 自动生成构造器/getter/equals/hashCode */
    private record SystemMetrics(double cpu, long mem) {}

    public static void main(String[] args) {
        SqliteWriter writer = new SqliteWriter();
        writer.initDatabase();

        // 声明在 try 外部确保 catch 后仍可落盘
        SystemMetrics sys = new SystemMetrics(-1.0, -1L);
        GpuMetrics gpu = null;

        try {
            log.info("=== GPU Monitor Toolkit 探针启动 ===");

            sys = runLocalCollection();
            gpu = runRemoteCollection();
        } catch (Exception e) {
            log.error("[致命错误] 探针异常终止: {}", e.getMessage());
        } finally {
            // 无论采集环节全部崩还是部分败，落盘必须在 finally 中执行，
            // 确保时序连续性 —— 一条空记录比一条缺失记录对监控的价值大得多
            writer.insertMetrics(sys.cpu(), sys.mem(), gpu);
            log.info("[AgentMain] 本次采集周期结束，数据已落盘。");
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

        // v0.1 临时通过返回字符串前缀判断失败，v0.2 将重构为 CommandResult(value, exitCode, success) 对象
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
            return gpu;
        } catch (MetricsParseException e) {
            log.warn("GPU 数据解析失败: {}", e.getMessage());
            return null;
        }
    }
}
