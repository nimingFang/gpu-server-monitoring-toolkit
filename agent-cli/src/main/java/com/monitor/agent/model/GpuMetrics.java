    package com.monitor.agent.model;

    import lombok.Builder;
    import lombok.Data;

    /**
     * 单次 GPU 采集的数据模型。
     *
     * <p>{@link Data @Data} 生成 getter/setter/equals/hashCode/toString，
     * {@link Builder @Builder} 提供流式构造器，避免多参构造函数的参数顺序错误。</p>
     *
     * @author nimingFang
     * @since 0.1
     */
    @Data
    @Builder
    public class GpuMetrics {

        /** GPU 型号名称，如 "Tesla T4" */
        private final String gpuName;

        /** 核心温度，单位：摄氏度 */
        private final int temperature;

        /** 已用显存，单位：MiB */
        private final long memoryUsed;

        /** 总显存，单位：MiB */
        private final long memoryTotal;
    }
