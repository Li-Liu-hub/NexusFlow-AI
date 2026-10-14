package com.aismp.digitalhuman.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 网关签名工具（数字员工内核侧）。
 *
 * <p>与 `aismp-client-sdk` / 编排层 `sdk/sign.py` 逐字段对齐：
 * {@code sign = sha256_hex(body + "." + secretKey)}，body 为 null 时用字面量 "null"。
 *
 * <p><b>为什么自己实现而不引 aismp-client-sdk</b>：
 * 那个 SDK 是给第三方开发者用的（JDK 8 编译），而数字员工内核跑在 JDK 21 上。
 * 为了一段 10 行的哈希去引入跨模块依赖，会让版本与 JDK 兼容性绑在一起 ——
 * 这里用 JDK 自带的 MessageDigest 更干净。
 */
public final class GatewaySigner {

    private GatewaySigner() {
    }

    /**
     * 生成签名。
     *
     * <p>注意 body 为 null 时必须拼成字面量 "null." + secretKey ——
     * 这是网关侧的既有约定（Java 字符串拼接 null 的结果），不能改成空串。
     */
    public static String sign(String body, String secretKey) {
        String content = (body == null ? "null" : body) + "." + secretKey;
        return sha256Hex(content);
    }

    /** 高熵随机串（32 位 hex），防重放用，与编排层 gen_nonce 一致。 */
    public static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 秒级时间戳字符串（网关校验 5 分钟窗口）。 */
    public static String timestamp() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                  .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
