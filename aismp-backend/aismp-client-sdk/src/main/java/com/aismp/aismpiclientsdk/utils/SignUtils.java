package com.aismp.aismpiclientsdk.utils;

import cn.hutool.crypto.digest.DigestAlgorithm;
import cn.hutool.crypto.digest.Digester;

/**
 *     /   签名加密获取sign
 */
public class SignUtils {

    public static String getSign(String body, String secretKey ){
        Digester md5 = new Digester(DigestAlgorithm.SHA256);
        String content = body + "." + secretKey;
        return  md5.digestHex(content);

    }
}
