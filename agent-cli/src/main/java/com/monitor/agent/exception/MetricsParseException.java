package com.monitor.agent.exception;

/**
 * 指标解析异常 —— 当 nvidia-smi 输出无法按预期格式解析时抛出。
 *
 * <p>区别于泛化的 {@link RuntimeException}，本异常携带原始脏数据 {@code rawData}，
 * 确保排查问题时能还原现场，而非只看到一句"解析失败"。</p>
 *
 * @author nimingFang
 * @since 0.1
 */
public class MetricsParseException extends RuntimeException {

    /** 引发解析失败的原始文本，保留案发现场用于日志排查 */
    private final String rawData;

    /**
     * @param message 解析失败的具体原因，如"字段数不匹配: 期望4列, 实际3列"
     * @param rawData 原始脏数据，写入日志供回溯
     */
    public MetricsParseException(String message, String rawData) {
        super(message);
        this.rawData = rawData;
    }

    /**
     * 重写以在日志中同时打印错误原因和原始数据，无需再去翻上下文日志。
     */
    @Override
    public String getMessage() {
        return String.format("%s | RawData: [%s]", super.getMessage(), rawData);
    }

    public String getRawData() {
        return rawData;
    }
}
