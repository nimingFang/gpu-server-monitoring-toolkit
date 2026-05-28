package com.monitor.agent.store;

import com.monitor.agent.model.GpuMetrics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * SQLite 本地落盘写入器。
 *
 * <p>封装 JDBC 建表与指标插入操作。每次 {@link #insertMetrics} 调用都
 * 通过 TWR 新建连接，用完即关，不维护长连接池——探针间隔 ≥15s 的低频写入
 * 场景下，连接创建开销（~2ms）远小于连接泄漏的风险。</p>
 *
 * <p>设计模式：<strong>方法级连接隔离</strong>，与 SSH 的
 * {@code SshConnectionManager} 保持一致的资源管理哲学。</p>
 *
 * @author nimingFang
 * @since 0.1
 */
public class SqliteWriter {

    private static final String DB_URL = "jdbc:sqlite:gpu_metrics.db";

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS gpu_metrics (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp       TEXT    NOT NULL,
                cpu_usage       REAL,
                mem_available   INTEGER,
                gpu_name        TEXT,
                gpu_temp        INTEGER,
                gpu_mem_used    INTEGER,
                gpu_mem_total   INTEGER
            )
            """;

    // INSERT 语句用占位符 ? 防止 SQL 注入——即使本探针是内网工具，
    // 也不应该养成拼接字符串入库的习惯
    private static final String INSERT_SQL = """
            INSERT INTO gpu_metrics
                (timestamp, cpu_usage, mem_available, gpu_name, gpu_temp, gpu_mem_used, gpu_mem_total)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /** 建表，若表已存在则跳过。应在探针启动时调用一次。 */
    public void initDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            System.out.println("[SQLite] 数据库初始化完成: " + DB_URL);
        } catch (SQLException e) {
            System.err.println("[SQLite] 建表失败: " + e.getMessage());
        }
    }

    /**
     * 写入一条采集记录。
     *
     * @param cpu  CPU 使用率百分比
     * @param mem  可用内存字节数
     * @param gpu  GPU 指标，<strong>可为 null</strong>——SSH 故障导致 GPU 采集失败时，
     *             仍然落盘 CPU/内存数据，GPU 列填入 null，保证监控不出现全量空白窗口
     */
    public void insertMetrics(double cpu, long mem, GpuMetrics gpu) {
        // 防御：SQLite 的 auto-commit 模式下每条 INSERT 自动提交事务，
        // 无需手动 BEGIN/COMMIT
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

            ps.setString(1, Instant.now().toString());
            ps.setDouble(2, cpu >= 0 ? cpu : 0.0);

            // 可用内存可能因 OSHI 故障返回 -1，此时存入 0 而非异常值
            ps.setLong(3, mem >= 0 ? mem : 0L);

            // 容忍 GPU 部分缺失：SSH 瞬断时不能因为显卡数据拿不到就扔掉整条记录，
            // 那样 CPU 和内存的历史曲线也会产生断点，影响根因分析
            if (gpu != null) {
                ps.setString(4, gpu.getGpuName());
                ps.setInt(5, gpu.getTemperature());
                ps.setLong(6, gpu.getMemoryUsed());
                ps.setLong(7, gpu.getMemoryTotal());
            } else {
                ps.setNull(4, java.sql.Types.VARCHAR);
                ps.setNull(5, java.sql.Types.INTEGER);
                ps.setNull(6, java.sql.Types.BIGINT);
                ps.setNull(7, java.sql.Types.BIGINT);
            }

            ps.executeUpdate();
            System.out.println("[SQLite] 记录已写入 (gpu=" + (gpu != null ? "有效" : "缺失") + ")");
        } catch (SQLException e) {
            System.err.println("[SQLite] 写入失败: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SqliteWriter writer = new SqliteWriter();
        writer.initDatabase();

        // 模拟一条带 GPU 数据的写入
        GpuMetrics gpu = GpuMetrics.builder()
                .gpuName("Tesla T4")
                .temperature(45)
                .memoryUsed(1024)
                .memoryTotal(15360)
                .build();

        writer.insertMetrics(12.34, 8_589_934_592L, gpu);

        // 模拟 GPU 采集失败时的部分写入
        System.out.println();
        System.out.println("--- 模拟 SSH 故障场景 (gpu=null) ---");
        writer.insertMetrics(8.56, 7_200_000_000L, null);
    }
}
