package com.monitor.agent.config;

import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Getter
public class AgentConfig {

    private static volatile AgentConfig instance;

    private final String sshHost;
    private final int sshPort;
    private final String sshUser;
    private final String sshPassword;
    private final int collectInterval;
    private final int gpuTempThreshold;

    private AgentConfig() {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("agent.properties")) {
            if (is == null) {
                throw new IllegalStateException("agent.properties not found in classpath");
            }
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load agent.properties", e);
        }

        this.sshHost = props.getProperty("ssh.host", "127.0.0.1");
        this.sshPort = Integer.parseInt(props.getProperty("ssh.port", "22"));
        this.sshUser = props.getProperty("ssh.user", "root");
        this.sshPassword = props.getProperty("ssh.password", "");
        this.collectInterval = Integer.parseInt(props.getProperty("agent.collect.interval", "15"));
        this.gpuTempThreshold = Integer.parseInt(props.getProperty("alert.gpu.temp.threshold", "85"));
    }

    public static AgentConfig getInstance() {
        if (instance == null) {
            synchronized (AgentConfig.class) {
                if (instance == null) {
                    instance = new AgentConfig();
                }
            }
        }
        return instance;
    }

    public static void main(String[] args) {
        AgentConfig config = AgentConfig.getInstance();
        System.out.println("SSH Host: " + config.getSshHost());
        System.out.println("SSH Port: " + config.getSshPort());
        System.out.println("SSH User: " + config.getSshUser());
        System.out.println("SSH Password: " + config.getSshPassword());
        System.out.println("Collect Interval: " + config.getCollectInterval() + "s");
        System.out.println("GPU Temp Threshold: " + config.getGpuTempThreshold() + "°C");
    }
}
