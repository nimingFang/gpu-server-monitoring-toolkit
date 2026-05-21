package com.monitor.agent.parse;

import com.monitor.agent.exception.MetricsParseException;
import com.monitor.agent.model.GpuMetrics;

/**
 * nvidia-smi CSV 输出解析器。
 *
 * <p>职责：将 SSH 远程执行的 nvidia-smi 查询结果（CSV 行）转换为
 * {@link GpuMetrics} 对象。采用<strong>Fail-Fast 策略</strong>：
 * 任何格式异常立即抛 {@link MetricsParseException}，绝不吞掉错误或返回 null。</p>
 *
 * <p>设计模式：<strong>无状态静态解析函数</strong> —— 纯输入 → 输出转换，
 * 不依赖外部状态，天然线程安全。</p>
 *
 * @author nimingFang
 * @since 0.1
 */
public class NvidiaSmiParser {

    private static final int EXPECTED_FIELDS = 4; // gpuName, temperature, memoryUsed, memoryTotal
    private static final String CSV_DELIMITER = ",";

    private NvidiaSmiParser() {
    }

    /**
     * 解析单行 CSV 输出为 GpuMetrics。
     *
     * @param csvOutput nvidia-smi 输出的一行 CSV，如 "Tesla T4, 45, 1024, 15360"
     * @return GpuMetrics 对象，永不返回 null
     * @throws MetricsParseException 当输入为空、字段数错误或数值无法解析时
     */
    public static GpuMetrics parse(String csvOutput) {
        // Fail-Fast：空输入不产生空对象，直接终止，避免 null 在后续流程中扩散
        if (csvOutput == null || csvOutput.isBlank()) {
            throw new MetricsParseException("输入为空或仅含空白字符", String.valueOf(csvOutput));
        }

        String[] fields = csvOutput.split(CSV_DELIMITER);

        // Fail-Fast：字段数对不上说明 nvidia-smi 输出格式发生了预期外的变化
        // （如驱动升级后 CSV 表头新增了列），此时必须终止并保留现场数据
        if (fields.length != EXPECTED_FIELDS) {
            throw new MetricsParseException(
                    String.format("字段数不匹配: 期望%d列, 实际%d列", EXPECTED_FIELDS, fields.length),
                    csvOutput);
        }

        try {
            String gpuName = fields[0].trim();
            int temperature = Integer.parseInt(fields[1].trim());
            long memoryUsed = Long.parseLong(fields[2].trim());
            long memoryTotal = Long.parseLong(fields[3].trim());

            return GpuMetrics.builder()
                    .gpuName(gpuName)
                    .temperature(temperature)
                    .memoryUsed(memoryUsed)
                    .memoryTotal(memoryTotal)
                    .build();
        } catch (NumberFormatException e) {
            // 捕获后包装成 MetricsParseException，保留原始 CSV 文本用于排查
            throw new MetricsParseException("数值字段无法解析: " + e.getMessage(), csvOutput);
        }
    }

    public static void main(String[] args) {
        // 模拟正常数据
        String validCsv = "Tesla T4, 45, 1024, 15360";
        System.out.println("===== 正常解析 =====");
        GpuMetrics metrics = NvidiaSmiParser.parse(validCsv);
        System.out.println("GPU: " + metrics.getGpuName());
        System.out.println("温度: " + metrics.getTemperature() + "°C");
        System.out.println("显存: " + metrics.getMemoryUsed() + " / " + metrics.getMemoryTotal() + " MiB");

        // 模拟残缺脏数据
        System.out.println("\n===== 异常拦截测试 =====");

        String[] badInputs = {
                null,
                "",
                "Tesla T4, 45, 1024",                    // 缺一列
                "Tesla T4, abc, 1024, 15360",             // 温度字段非数字
                "Tesla T4, 45, 1024, 15360, 999"          // 多一列
        };

        for (String input : badInputs) {
            try {
                NvidiaSmiParser.parse(input);
                System.out.println("意外: 未抛出异常 —— " + input);
            } catch (MetricsParseException e) {
                System.out.println("[已拦截] " + e.getMessage());
            }
        }
    }
}
