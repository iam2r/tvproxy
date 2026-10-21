package com.tvproxy.core;

import com.tvproxy.adb.AdbClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 系统代理管理核心。
 *
 * 执行策略 (可配置):
 *   1. 首选: 直接调用 /system/bin/settings (许多海信固件的 settings 命令对任意进程开放写)
 *   2. 保底: 通过电视自身 adbd (127.0.0.1:5555) 的 shell 通道执行 (已验证 100% 可用的方法)
 *
 * 电视本地 clash 监听 127.0.0.1:7890 (实测 CONNECT google 200 OK)。
 */
public class ProxyManager {

    public static final String PROXY_HOST = "127.0.0.1";
    public static final String PROXY_PORT = "7890";

        /** 内嵌 RSA 私钥 (PEM) — 用于 ADB 回环认证。来自电视配对时生成的密钥。 */
    private static final String ADB_PRIVATE_KEY_B64 =
            "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC/rxVmKW/ldvBH" +
            "TowneFAtcpk3qkcFszLfcAkqCMWKk/nd0pJS2QrxIem8DwyteXZiX6FEJhTWnUmt" +
            "tEPZ+rDpaTo21i/tkaCe+imYNcplqAQx6E5G5VjP6sPm6eReR7POU8sLcNXeyD9a" +
            "AQJO3Omtp2vk3Ul/KtVAF4G9UW7w1TT9lQNXrPP8KfAXDDAxRiFSM2LKb4Om4bTv" +
            "fzoVQSbppRexSY9gYzDayfDrWKhcH5nwgumeSJe0Ke1/47Lt7OK0buetwDFMIVGC" +
            "oQKtqRaXKnZf6dFzNIJyOi3TMcajAxzNlXbdxAFZKDCUNfvowNhE95P9ikbBmF09" +
            "kIJaMjDbAgMBAAECggEABKgEJQ8soK5a9+B4lxzdFb2K60c/omvP+I2IK3U7Fzqw" +
            "/yp2U3ziPylLvD3BWRVyQsF/8V/bcbswZv/SBiGI2qKrv1lEoMjA1hvNltz3pbCH" +
            "KY8zF5XKqoJRb/KOkZu8Sm7Op+B90YObxlvv6mqDYqyGenDFsEkiFQR9KlQoFcGN" +
            "QqfOxhovrcOWcQ5gySWt4NYV15AIlJwkULNLDGGjO9aLWvuOBiFgz6ijSXgeAXGx" +
            "1zBu/VzeDnvss2fRUs8x1G/5OcMMfrQKAqpIrcfD6pXHI3+ZXBKWkLQC8BkTQAP1" +
            "MtMW44ufI/mS+j1uUvI1uf7Cj3yNPTD617FltDGzgQKBgQD+OxwYuqZLMDt0oe8Q" +
            "c9w8Qclgh9XJADhga/ewpDX7Y9OKphoqWow7itwLizZgBtP5mfgAONGsCqE/EpB0" +
            "lhxVgVwDDFgizbD1c5Q5apWL+75CpJ0cGCnjL1kYdPh19U5PkPLJcBswmqSLwGQP" +
            "rI40sX4S6A4wZn0rBl9EZY2iwwKBgQDBBI1E/+1Jm87ufVgA8R4m6S8LpLYB8c0z" +
            "kweTyQ3OBOOw0XiPND9UV2o0y6WVRJ63WglGX7LVQQOqrOuRieb2GldzJTi9Krwk" +
            "81gl3/MbmJPN5fRzKDxeaQ7PPWryl/bblVpcmkfmraz6Z7Gr2lfWW2/EQqcQ7gvA" +
            "5sB0X4MoCQKBgQC8co5SGlDX3QnFW598n5U5qT4farDijGI9vupcKmzFSmrtFFDC" +
            "4lmebUDM4cVNiuIn26SEbZmmID8EJLulX/viub+9lrHiInwwhRrzMHlnyRRwC9tV" +
            "jufFujwdr97YeRwySGxfOtU8wvSDjvnc0HicwJCGbTLPV7kiYlfNuEUzxwKBgQCL" +
            "tEq39XF/RKnrTogC7oJ/cWneyJIAKE75FGN4oR4/zqQq2AKlzBBGcJrrqbaoqqki" +
            "VqW97QhYeB06TLyYc7La0EgZjIhbyvGw4P0dNNIWWEIq1lUvgKDpSKsybubYvgsr" +
            "9hcYTYbbPTulZqZfl8LZ4Qati/y2MUYKkjocQQGCWQKBgFuSC0iO1inZdaC+frKH" +
            "xvD4g9ZzbjGAfCYnNh3IDP65VXm4ZGokRbREaJ6YZR39BOiLai5+7oF74M+ARdTI" +
            "d0+kb1m2A3qfmded9IDUXOfkFrqp7HjabntsmKMq56CrfC7Z9JdGtb/QHEzb0XkZ" +
            "DGkdxjmHjpReXbTRj/a5QFt6";


    private static final String ADB_PRIVATE_KEY_PEM =
        "-----BEGIN PRIVATE KEY-----\n" +
        ADB_PRIVATE_KEY_B64 +
        "\n-----END PRIVATE KEY-----\n";

    private static final boolean USE_DIRECT = false; // 先尝试直接 settings 命令

    // ---------- 公共 API ----------

    public static String isSystemProxyEnabled() throws Exception {
        return exec("settings get global global_http_proxy_enable");
    }

    public static void enableSystemProxy() throws Exception {
        exec("settings put global global_http_proxy_host " + PROXY_HOST);
        exec("settings put global global_http_proxy_port " + PROXY_PORT);
        exec("settings put global global_https_proxy_host " + PROXY_HOST);
        exec("settings put global global_https_proxy_port " + PROXY_PORT);
        exec("settings put global global_http_proxy_enable true");
        // 确保标准键同步 (部分 app 读取 http_proxy 标准键)
        exec("settings put global http_proxy " + PROXY_HOST + ":" + PROXY_PORT);
    }

    public static void disableSystemProxy() throws Exception {
        exec("settings delete global global_http_proxy_host");
        exec("settings delete global global_http_proxy_port");
        exec("settings delete global global_https_proxy_host");
        exec("settings delete global global_https_proxy_port");
        exec("settings delete global http_proxy");
        exec("settings put global global_http_proxy_enable false");
    }

    public static void reboot() throws Exception {
        // 走 ADB reboot: 服务 (adbd 以 root 重启, 等价 adb reboot; shell 层 svc/reboot 均被固件阉割)
        try (AdbClient client = AdbClient.connect("127.0.0.1", 5555, ADB_PRIVATE_KEY_PEM, 8000)) {
            client.rebootDevice();
        }
    }

    // ---------- 执行引擎 ----------

    private static String exec(String command) throws Exception {
        if (USE_DIRECT) {
            try {
                return execDirect(command);
            } catch (Exception directErr) {
                // 直连失败 → 回退 ADB 通道
                return execViaAdb(command);
            }
        } else {
            return execViaAdb(command);
        }
    }

    /** 路径1: 直接调用 settings 命令. 仅适用于固件对 settings 权限宽松的情况. */
    private static String execDirect(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/system/bin/toybox", "sh", "-c", command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("settings 退出码 " + code + ": " + sb.toString().trim());
        return sb.toString();
    }

    /** 路径2: ADB 回环通道 (127.0.0.1:5555). 已验证可用. */
    private static String execViaAdb(String command) throws Exception {
        try (AdbClient client = AdbClient.connect("127.0.0.1", 5555, ADB_PRIVATE_KEY_PEM, 8000)) {
            return client.shell(command);
        }
    }
}