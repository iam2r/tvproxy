package com.tvproxy.adb;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.interfaces.RSAPrivateKey;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 极简 ADB 协议客户端 — 仅实现本 app 需要的部分:
 *   1. TCP 连接 127.0.0.1:5555
 *   2. CNXN 握手
 *   3. RSA 私钥签名认证 (SHA1withRSA over adbd 提供的 token)
 *   4. OPEN shell 交互通道
 *   5. WRTE 写命令 + 读输出
 *
 * 协议事实 (经 python adb_shell 在 TV 上实测验证):
 *   - 握手: 客户端发 CNXN + version + maxdata + banner
 *   - adbd 响应: 4字节 cmd + 4字节 arg0(长度) + 4字节 arg1 + 4字节校验(旧版0)
 *   - 若 arg0 == "AUTH" → 客户端发 AUTH + 签名(token)
 *   - 若 arg0 == "CNXN" → 完成
 *   - shell 命令: OPEN(服务名"shell:") → adbd 回复 OKAY + remote_id(4字节) + shell启动
 *   - WRTE: remote_id + 命令字节; 输出经 STDO 包传回
 */

public class AdbClient implements AutoCloseable {
    private static final byte[] CMD_CNXN = "CNXN".getBytes();
    private static final byte[] CMD_AUTH = "AUTH".getBytes();
    private static final byte[] CMD_OPEN = "OPEN".getBytes();
    private static final byte[] CMD_WRTE = "WRTE".getBytes();
    private static final byte[] CMD_STDO = "STDO".getBytes();
    private static final byte[] CMD_STDE = "STDE".getBytes();
    private static final byte[] CMD_CLSE = "CLSE".getBytes();
    private static final byte[] CMD_OKAY = "OKAY".getBytes();
    private static final byte[] CMD_FAIL = "FAIL".getBytes();

    private static final int MAX_DATA = 64 * 1024;

    private final Socket socket;
    private final OutputStream out;
    private final DataInputStream in;

    private AdbClient(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.in = new DataInputStream(socket.getInputStream());
    }

    public static AdbClient connect(String host, int port, String privateKeyPem, int timeoutMs)
            throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        AdbClient c = new AdbClient(s);
        try {
            c.handshake(privateKeyPem);
        } catch (IOException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /**
     * CNXN 握手 → 若需 AUTH 则签名回送, 直到收到 CNXN 或 FAIL。
     */
    private void handshake(String privateKeyPem) throws IOException {
        String banner = "tvproxy::" + System.currentTimeMillis();
        Packet cnxn = new Packet(CMD_CNXN, 0x01000000, MAX_DATA,
                (banner + "\0").getBytes());
        sendPacket(cnxn);

        while (true) {
            Packet resp = readPacket();
            String cmd = new String(resp.cmd);
            if ("AUTH".equals(cmd)) {
                byte[] sig = rsaSign(resp.data, privateKeyPem);
                sendPacket(new Packet(CMD_AUTH, 2, 0, sig)); // arg0=AUTH_SIGNATURE(2)
            } else if ("CNXN".equals(cmd)) {
                return; // 握手完成
            } else if ("FAIL".equals(cmd)) {
                throw new IOException("ADB 认证失败: " + new String(resp.data).trim());
            } else {
                throw new IOException("ADB 握手异常命令: " + cmd);
            }
        }
    }

    /** 请求设备重启 (adbd 的 reboot: 服务, 等价 adb reboot, 无需 shell 权限) */
    public void rebootDevice() throws IOException {
        int lid = localId++;
        // OPEN reboot: 服务 → adbd 直接以 root 重启设备
        sendPacket(new Packet(CMD_OPEN, lid, 0, ("reboot:").getBytes()));
        // adbd 收到后立即重启, 连接随之断开; 不等待响应
    }

    /** 请求设备重启 (adbd 的 reboot: 服务, 等价 adb reboot, 无需 shell 权限) */
    public void rebootDevice(String reason) throws IOException {
        int lid = localId++;
        sendPacket(new Packet(CMD_OPEN, lid, 0, ("reboot:" + reason).getBytes()));
    }

    /** 执行一条命令, 返回合并输出. 把命令拼进 OPEN (无交互回显, 与 adb_shell 一致) */
    private int localId = 1;

    public String shell(String command) throws IOException {
        int lid = localId++;
        // OPEN: arg0=local_id, data="shell:" + command + "\0" (adbd 直接执行, 无回显)
        String dest = "shell:" + command;
        Packet open = new Packet(CMD_OPEN, lid, 0, (dest + "\0").getBytes());
        sendPacket(open);

        // 读取 OPEN 响应: OKAY (arg0 = remote_id)
        int remoteId = -1;
        Packet resp = readPacket();
        String cmd = new String(resp.cmd);
        if (CMD_OKAY[0] == resp.cmd[0]) {
            remoteId = resp.arg0;
        } else if ("FAIL".equals(cmd)) {
            throw new IOException("OPEN shell 失败: " + new String(resp.data).trim());
        } else {
            throw new IOException("OPEN 响应异常: " + cmd + " arg0=" + resp.arg0);
        }

        // 收集输出直到 CLSE
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            Packet p = readPacket();
            String pc = new String(p.cmd);
            if (CMD_WRTE[0] == p.cmd[0]) {
                // 设备发送 WRTE: arg0=remote_id, data=输出
                if (p.arg0 == remoteId && p.data.length > 0) {
                    sb.append(new String(p.data));
                }
                // 回 OKAY 确认 (arg0=lid, arg1=remoteId)
                sendPacket(new Packet(CMD_OKAY, lid, remoteId, new byte[0]));
            } else if (CMD_STDO[0] == p.cmd[0] || CMD_STDE[0] == p.cmd[0]) {
                if (p.arg0 == remoteId && p.data.length > 0) {
                    sb.append(new String(p.data));
                }
            } else if (CMD_CLSE[0] == p.cmd[0]) {
                break;
            }
        }
        return sb.toString().trim();
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    // ---------- 协议底层 ----------

    private static final class Packet {
        final byte[] cmd;
        final int arg0, arg1;
        final byte[] data;
        Packet(byte[] cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd; this.arg0 = arg0; this.arg1 = arg1;
            this.data = data == null ? new byte[0] : data;
        }
    }

    private void sendPacket(Packet p) throws IOException {
        byte[] header = new byte[24];
        // 协议: 6×LE uint32 [cmd, arg0, arg1, data_length, data_checksum, magic]
        // cmd 字段 = ASCII 字节正序
        System.arraycopy(p.cmd, 0, header, 0, Math.min(4, p.cmd.length));
        le32(header, 4, p.arg0);          // arg0
        le32(header, 8, p.arg1);          // arg1
        le32(header, 12, p.data.length);  // data_length
        le32(header, 16, checksum(p.data)); // data_checksum = 数据字节和 (mod 2^32)
        int cmdAsInt = (p.cmd[0] & 0xff) | ((p.cmd[1] & 0xff) << 8)
                | ((p.cmd[2] & 0xff) << 16) | ((p.cmd[3] & 0xff) << 24);
        le32(header, 20, cmdAsInt ^ 0xFFFFFFFF);  // magic = ~cmd
        out.write(header);
        if (p.data.length > 0) out.write(p.data);
        out.flush();
    }

    /** ADB checksum: 数据字节和 (与 adb_shell 一致) */
    private static int checksum(byte[] data) {
        long sum = 0;
        for (byte b : data) sum += (b & 0xff);
        return (int) (sum & 0xffffffffL);
    }

    private Packet readPacket() throws IOException {
        byte[] header = new byte[24];
        readFully(header);
        byte[] cmd = new byte[4];
        System.arraycopy(header, 0, cmd, 0, 4);
        int arg0 = le32(header, 4);
        int arg1 = le32(header, 8);
        int len = le32(header, 12);
        if (len < 0 || len > 16 * 1024 * 1024) {
            throw new IOException("非法数据长度: " + len);
        }
        byte[] data = new byte[len];
        if (len > 0) readFully(data);
        return new Packet(cmd, arg0, arg1, data);
    }

    /** 4字节命令串 → 小端整数 ("CNXN" → 0x4e4e434e) */
    private static int leChars(byte[] cmd) {
        return (cmd[0] & 0xff) | ((cmd[1] & 0xff) << 8)
                | ((cmd[2] & 0xff) << 16) | ((cmd[3] & 0xff) << 24);
    }

    /** 小端整数 → 4字节命令串 */
    private static void le32ToBytes(int v, byte[] out) {
        out[0] = (byte) (v & 0xff);
        out[1] = (byte) ((v >> 8) & 0xff);
        out[2] = (byte) ((v >> 16) & 0xff);
        out[3] = (byte) ((v >> 24) & 0xff);
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("连接中断");
            off += n;
        }
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off+1] = (byte) ((v >> 8) & 0xff);
        b[off+2] = (byte) ((v >> 16) & 0xff);
        b[off+3] = (byte) ((v >> 24) & 0xff);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off+1] & 0xff) << 8)
                | ((b[off+2] & 0xff) << 16) | ((b[off+3] & 0xff) << 24);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off+1] & 0xff) << 16)
                | ((b[off+2] & 0xff) << 8) | (b[off+3] & 0xff);
    }

    private static byte[] be32(int v) {
        return new byte[]{
                (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v};
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // ---------- RSA 签名 ----------

    private static byte[] rsaSign(byte[] token, String pem) {
        try {
            String der = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] derBytes = Base64.getDecoder().decode(der);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            RSAPrivateKey key = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(derBytes));
            // adb 用 Prehashed(SHA1): 把 token 直接作为 SHA1 摘要做 EMSA-PKCS1-v1_5 签名
            // 即: 0x0001 || 0xFF.. || 0x00 || DigestInfo(SHA1, token)  → RSA 私钥运算
            byte[] digestInfo = new byte[]{0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                    0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
            int k = key.getModulus().bitLength() / 8; // 字节长度 (256)
            byte[] em = new byte[k];
            em[0] = 0x00; em[1] = 0x01;
            int j = 2;
            while (j < k - digestInfo.length - token.length - 1) em[j++] = (byte) 0xff;
            em[j++] = 0x00;
            System.arraycopy(digestInfo, 0, em, j, digestInfo.length);
            System.arraycopy(token, 0, em, j + digestInfo.length, token.length);
            // RSA 私钥运算: s = em^d mod n
            java.math.BigInteger m = new java.math.BigInteger(1, em);
            java.math.BigInteger sigBig = m.modPow(key.getPrivateExponent(), key.getModulus());
            byte[] raw = sigBig.toByteArray();
            // 结果可能带前导 0 或不足 k 字节, 规范化为 k 字节
            byte[] out = new byte[k];
            if (raw.length == k) return raw;
            if (raw.length == k + 1 && raw[0] == 0) System.arraycopy(raw, 1, out, 0, k);
            else if (raw.length < k) System.arraycopy(raw, 0, out, k - raw.length, raw.length);
            else throw new RuntimeException("签名过长: " + raw.length);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("RSA 签名失败: " + e.getMessage(), e);
        }
    }
}