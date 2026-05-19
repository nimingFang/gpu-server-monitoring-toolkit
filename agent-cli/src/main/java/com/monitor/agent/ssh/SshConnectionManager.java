package com.monitor.agent.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.monitor.agent.config.AgentConfig;

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
 * <p>设计模式：<strong>方法级会话隔离</strong> —— 每个命令一个独立 Session，
 * 天然隔离不同命令间的 SSH 状态干扰。</p>
 *
 * @author nimingFang
 * @since 0.1
 */
public class SshConnectionManager {

    private final AgentConfig config;

    public SshConnectionManager() {
        this.config = AgentConfig.getInstance();
    }

    /**
     * 通过 SSH exec 通道执行一条命令，返回 stdout + stderr 组合文本。
     *
     * @param command 要执行的 Shell 命令，如 "nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader"
     * @return 命令输出。若 stderr 有内容，会以 STDERR: / STDOUT: 分段标出。
     *         连接失败时返回异常消息文本而非抛异常，保护调用方不被单次 SSH 故障打断采集循环。
     */
    public String executeCommand(String command) {
        JSch jsch = new JSch();
        Session session = null;
        ChannelExec channel = null;

        try {
            session = jsch.getSession(config.getSshUser(), config.getSshHost(), config.getSshPort());
            session.setPassword(config.getSshPassword());

            // 跳过 known_hosts 检查：探针运行在内网隔离环境，第一次连接时
            // ~/.ssh/known_hosts 不存在，若开启 StrictHostKeyChecking=ask/yes
            // 会直接抛 JSchException，无法完成首次握手。
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect(10000); // 10 秒超时，避免 TCP 丢包导致无限挂起

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            // stderr 写入本地缓冲区而非直接打印到控制台，便于日志记录与解析
            ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
            channel.setErrStream(errCapture);

            InputStream stdout = channel.getInputStream();
            channel.connect(5000);

            String out = readAll(stdout);
            String err = errCapture.toString(StandardCharsets.UTF_8.name());
            
            //获取 Linux 命令的真实退出状态码 (0 表示成功)
            int exitStatus = channel.getExitStatus();

            if (exitStatus != 0 || !err.isEmpty()) {
                return String.format("[执行失败] ExitCode: %d\nSTDERR: %s\nSTDOUT: %s", 
                                     exitStatus, err.trim(), out.trim());
            }
            return out.trim();
        } catch (JSchException e) {
            return "SSH连接失败: " + e.getMessage();
        } catch (IOException e) {
            return "读取命令输出时IO异常: " + e.getMessage();
        } finally {
            // 必须显式关闭 Channel 再关闭 Session，顺序不能倒。
            // JSch 的 Session 内部持有活跃 Channel 引用计数，
            // 若先关 Session 可能导致 Channel 的 TCP 端口处于 TIME_WAIT 状态泄漏。
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    /**
     * 将 InputStream 读取到字符串，用 UTF-8 解码。
     *
     * <p>Linux 系统中 nvidia-smi 输出含特殊字符（如温度单位 ℃），
     * 必须显式声明 UTF-8。若使用系统默认编码 (GBK/Cp1252)，这些字符会变成乱码。</p>
     */
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
