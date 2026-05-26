package com.monitor.agent;

import com.monitor.agent.collect.OshiCollector;
import com.monitor.agent.exception.MetricsParseException;
import com.monitor.agent.model.GpuMetrics;
import com.monitor.agent.parse.NvidiaSmiParser;
import com.monitor.agent.ssh.SshConnectionManager;

/**
 * 探针编排入口 —— Facade/Orchestrator，串联本地采集 → 远程采集 → 解析 → 输出。
 *
 * <p>采用<strong>部分失败容忍策略</strong>：任何一个环节的异常只记录日志并跳过，
 * 不会中断后续采集步骤。监控系统暂停 15 秒远比崩溃 15 分钟代价小。</p>
 *
 * <p>编排职责：顺序调用各模块（OshiCollector → SshConnectionManager → NvidiaSmiParser），
 * 模块间通过内存数据耦合，无消息队列、无 RPC 依赖。</p>
 *
 * @author nimingFang
 * @since 0.1
 */
public class AgentMain {

    // nvidia-smi 查询模板 —— CSV 输出，不含表头与单位，直接产出可解析的 4 列数据
    private static final String GPU_QUERY_CMD =
            "nvidia-smi --query-gpu=name,temperature.gpu,memory.used,memory.total --format=csv,noheader,nounits";

    public static void main(String[] args) {
        try {
            System.out.println("=== GPU Monitor Toolkit 探针启动 ===");
            System.out.println();

            runLocalCollection();
            System.out.println();
            runRemoteCollection();
            System.out.println();

            System.out.println("=== 采集任务完成，探针退出 ===");
        } catch (Exception e) {
            // 全局兜底：任何穿透到这里的异常都是意料之外的致命错误，
            // 打印信息后体面退出，而非让 JVM 吐出几百行堆栈吓唬运维。
            System.err.println("[致命错误] 探针异常终止: " + e.getMessage());
        }
    }

    private static void runLocalCollection() {
        System.out.println("--- 本地 OSHI 采集 ---");

        try {
            double cpu = OshiCollector.collectCpuUsage();
            System.out.printf("CPU 使用率: %.2f%%%n", cpu >= 0 ? cpu : 0.0);
        } catch (Exception e) {
            System.out.println("[警告] CPU 采集失败: " + e.getMessage());
        }

        try {
            long memBytes = OshiCollector.collectAvailableMemory();
            double memGB = memBytes / (1024.0 * 1024.0 * 1024.0);
            System.out.printf("可用内存: %.2f GB%n", memGB);
        } catch (Exception e) {
            System.out.println("[警告] 内存采集失败: " + e.getMessage());
        }
    }

    private static void runRemoteCollection() {
        System.out.println("--- 远程 SSH 采集 ---");

        SshConnectionManager ssh = new SshConnectionManager();
        String result = ssh.executeCommand(GPU_QUERY_CMD);

        // v0.1 临时通过返回字符串前缀判断失败，v0.2 将重构为 CommandResult(value, exitCode, success) 对象
        if (result.startsWith("[执行失败]") || result.startsWith("SSH连接失败") || result.startsWith("读取命令输出时IO异常")) {
            System.out.println("[警告] SSH 执行未成功，跳过本次 GPU 解析");
            System.out.println("  详情: " + result.lines().findFirst().orElse("无"));
            return;
        }

        try {
            GpuMetrics gpu = NvidiaSmiParser.parse(result);
            System.out.println("GPU 型号: " + gpu.getGpuName());
            System.out.println("GPU 温度: " + gpu.getTemperature() + "°C");
            System.out.println("GPU 显存: " + gpu.getMemoryUsed() + " / " + gpu.getMemoryTotal() + " MiB");
        } catch (MetricsParseException e) {
            // 解析失败 ≠ 采集失败：SSH 可能拿到了非标准格式输出（驱动差异），记录后继续
            System.out.println("[警告] GPU 数据解析失败: " + e.getMessage());
        }
    }
}
