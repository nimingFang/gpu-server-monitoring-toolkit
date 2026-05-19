package com.monitor.agent.collect;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

/**
 * 基于 OSHI 的宿主机指标采集器。
 *
 * <p>设计为<strong>无状态静态工具类</strong>，每次调用独立获取最新快照，
 * 不缓存跨采样的状态，避免生命周期管理负担。</p>
 *
 * @author nimingFang
 * @since 0.1
 */
public class OshiCollector {

    private OshiCollector() {
    }

    /**
     * 采集 CPU 使用率，内部通过两次 tick 快照差值计算，会阻塞 1 秒。
     *
     * @return 百分比数值（已保留两位小数），如 12.34 表示 12.34%。
     *         中断或计算异常时返回 -1.0。
     */
    public static double collectCpuUsage() {
        SystemInfo si = new SystemInfo();
        HardwareAbstractionLayer hal = si.getHardware();
        CentralProcessor processor = hal.getProcessor();

        // getSystemCpuLoadTicks() 返回的数组索引与 /proc/stat 对齐：
        // [0]user [1]nice [2]system [3]idle [4]iowait [5]irq [6]softirq [7]steal
        // 其中 [7] steal 仅存在于开启了虚拟化的环境，旧内核该列缺失 ——
        // 所以下面遍历时必须用 .length 而非硬编码 8
        long[] ticksT0 = processor.getSystemCpuLoadTicks();

        // 为什么 sleep(1000)：CPU tick 是开机以来的累计值，只有一次快照毫无意义。
        // 必须等 1 秒让内核累积足够的 tick 增量，两次快照的差值才能反映真实使用率。
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // 必须恢复中断标志位。JVM 抛出 InterruptedException 时会自动清除标志位，
            // 如果不重新设置，上层调用 Thread.interrupted() 将返回 false，
            // 中断信号被"吞掉"，程序无法响应外部终止请求。
            Thread.currentThread().interrupt();
            return -1.0;
        }

        long[] ticksT1 = processor.getSystemCpuLoadTicks();

        long totalT0 = 0L, idleT0 = 0L;
        long totalT1 = 0L, idleT1 = 0L;

        for (int i = 0; i < ticksT0.length; i++) {
            totalT0 += ticksT0[i];
            if (i == 3 || i == 4) { // idle + iowait = 未执行实际计算任务的 CPU 时间
                idleT0 += ticksT0[i];
            }
        }
        for (int i = 0; i < ticksT1.length; i++) {
            totalT1 += ticksT1[i];
            if (i == 3 || i == 4) {
                idleT1 += ticksT1[i];
            }
        }

        long totalDelta = totalT1 - totalT0;
        long idleDelta = idleT1 - idleT0;
        long busyDelta = totalDelta - idleDelta;

        // 防除零：极罕见情况下两次快照 tick 总量无变化（系统刚启动或 CPU 完全停顿）
        if (totalDelta <= 0) {
            return -1.0;
        }

        double rawUsage = (double) busyDelta / totalDelta * 100.0;
        return Math.round(rawUsage * 100.0) / 100.0;
    }

    /**
     * 采集可用内存。
     *
     * @return 可用内存字节数。底层取 /proc/meminfo 的 MemAvailable 而非 MemFree，
     *         因为内核 page cache 在内存紧张时可立即回收，MemAvailable 才是
     *         真正可供新进程申请的内存。
     */
    public static long collectAvailableMemory() {
        SystemInfo si = new SystemInfo(); // 每次新建实例，避免低频采样下跨周期状态污染
        HardwareAbstractionLayer hal = si.getHardware();
        GlobalMemory memory = hal.getMemory();
        return memory.getAvailable();
    }

    public static void main(String[] args) {
        System.out.println("=== OSHI 本地探针测试 ===");

        System.out.print("正在采集 CPU 使用率（需耗时约 1 秒）... ");
        double cpu = collectCpuUsage();
        System.out.println(cpu >= 0 ? cpu + "%" : "采集失败（返回 " + cpu + "）");

        long memAvailable = collectAvailableMemory();
        double memGB = memAvailable / (1024.0 * 1024.0 * 1024.0);
        System.out.printf("可用内存: %d bytes (约 %.2f GB)%n", memAvailable, memGB);

        System.out.println("=== 采集完成 ===");
    }
}
