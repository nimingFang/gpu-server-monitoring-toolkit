package com.monitor.agent.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.monitor.agent.config.AgentConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * JSch SSH 会话管理器 —— 建立到 target 靶机的 exec 通道并返回命令执行结果。
 *
 * <p>每次调用 {@link #executeCommand(String)} 都创建全新会话，用完即销毁，
 * 不维护长连接池。适合探针低频采样（≥15s）场景，避免 keep-alive 与断线
 * 重连带来的复杂度。</p>
 *
 * <p>内置<strong>指数退避重试</strong>：单次 executeCommand 内部最多重试 3 次
 * （间隔 2s → 4s），屏蔽瞬时网络抖动。重试放在本层而非上层编排器，原因是
 * 只有本层知道失败是"连接级"还是"业务级"——上层只能看到字符串结果，无法区分。</p>
 *
 * <p>设计模式：<strong>方法级会话隔离 + 指数退避重试</strong>。</p>
 *
 * @author nimingFang
 * @since 0.2
 */
@Slf4j
public class SshConnectionManager {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 2000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final AgentConfig config;

    public SshConnectionManager() {
        this.config = AgentConfig.getInstance();
    }

    /**
     * 通过 SSH exec 通道执行一条命令，内建指数退避重试。
     *
     * @param command 要执行的 Shell 命令
     * @return 命令输出，或失败描述文本（不抛异常以保护调用方采集循环）
     */
    public String executeCommand(String command) {
        JSch jsch = new JSch();
        long currentBackoff = INITIAL_BACKOFF_MS;

        // 重试放在 SSH 内部而非 AgentMain 编排层，因为只有此处能区分"网络层失败
        // （JSchException/IOException，值得重试）"和"命令执行失败（非零 exitCode，
        // 不重试）"。如果抛给上层，AgentMain 只能拿到结果字符串，无法判断该不该重试。
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Session session = null;
            ChannelExec channel = null;

            try {
                session = jsch.getSession(config.getSshUser(), config.getSshHost(), config.getSshPort());
                session.setPassword(config.getSshPassword());

                // 跳过 known_hosts 检查：探针运行在内网隔离环境，第一次连接时
                // ~/.ssh/known_hosts 不存在，若开启 StrictHostKeyChecking=ask/yes
                // 会直接抛 JSchException，无法完成首次握手。
                session.setConfig("StrictHostKeyChecking", "no");
                session.connect(10000);

                channel = (ChannelExec) session.openChannel("exec");
                channel.setCommand(command);

                ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
                channel.setErrStream(errCapture);

                InputStream stdout = channel.getInputStream();
                channel.connect(5000);

                String out = readAll(stdout);
                String err = errCapture.toString(StandardCharsets.UTF_8.name());

                int exitStatus = channel.getExitStatus();

                if (exitStatus != 0 || !err.isEmpty()) {
                    return String.format("[执行失败] ExitCode: %d\nSTDERR: %s\nSTDOUT: %s",
                            exitStatus, err.trim(), out.trim());
                }
                return out.trim();
            } catch (JSchException e) {
                if (attempt == MAX_RETRIES) {
                    return String.format("[执行失败] SSH连接彻底失败，已耗尽 %d 次重试机会: %s",
                            MAX_RETRIES, e.getMessage());
                }
                log.warn("SSH连接失败 (尝试 {}/{}), {}ms 后进行指数退避重试: {}",
                        attempt, MAX_RETRIES, currentBackoff, e.getMessage());
            } catch (IOException e) {
                if (attempt == MAX_RETRIES) {
                    return String.format("[执行失败] IO读取彻底失败，已耗尽 %d 次重试机会: %s",
                            MAX_RETRIES, e.getMessage());
                }
                log.warn("IO读取失败 (尝试 {}/{}), {}ms 后进行指数退避重试: {}",
                        attempt, MAX_RETRIES, currentBackoff, e.getMessage());
            } finally {
                // 每次尝试后必须断开 Session，否则重试时旧连接的 TCP 端口残留
                // 在 TIME_WAIT 状态 —— 3 次重试 → 3 个端口泄漏
                if (channel != null) {
                    channel.disconnect();
                }
                if (session != null) {
                    session.disconnect();
                }
            }

            // 退避休眠：让 target 有时间从瞬断中恢复（sshd 重启、网络收敛）
            try {
                Thread.sleep(currentBackoff);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "[执行失败] 重试等待被中断，放弃本次命令执行";
            }
            currentBackoff = (long) (currentBackoff * BACKOFF_MULTIPLIER);
        }

        return "[执行失败] SSH连接彻底失败，已耗尽 " + MAX_RETRIES + " 次重试机会";
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int len;
        while ((len = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, len);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    public static void main(String[] args) {
        SshConnectionManager manager = new SshConnectionManager();

        System.out.println("===== 执行 uptime =====");
        System.out.println(manager.executeCommand("uptime"));

        System.out.println();

        System.out.println("===== 执行 free -h =====");
        System.out.println(manager.executeCommand("free -h"));
    }
}
