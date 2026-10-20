package com.aismp.orchestrator.graph;

import java.util.Map;

/**
 * 节点里反复出现的两个取值动作：拿一段文本、拿一个 map。
 *
 * <p>抽出来的原因很朴素：三个节点类各写了一份一模一样的实现（共 5 个方法），
 * 而且写法有细微差别（有的 trim、有的不 trim），改一处很容易漏掉另一处。
 */
public final class StateSupport {

    private StateSupport() {
    }

    /** 取字符串并去首尾空白；null 得空串。 */
    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 取 map；不是 map（或为 null）时给空 map，调用方不用判空。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
