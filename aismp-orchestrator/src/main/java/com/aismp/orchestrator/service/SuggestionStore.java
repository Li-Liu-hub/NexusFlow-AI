package com.aismp.orchestrator.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建议缓存：把「本次算出来的建议」暂存起来，等用户点「申请调拨」时按 id 取回。
 *
 * <p>为什么要缓存而不是重算：用户看到的是某一刻的库存与建议，点确认时若重算，
 * 数字可能已经变了 —— 那样"确认"的就不是他看到的东西。缓存把这次决策冻结下来。
 *
 * <p>用进程内 TTL 而不是 Redis：建议只在一次会话内有效（30 分钟），
 * 单实例部署下够用；多实例时再换 Redis（届时 key 加实例无关的前缀即可）。
 */
@Component
public class SuggestionStore {

    private static final long TTL_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_ENTRIES = 200;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public String save(Map<String, Object> payload) {
        evictExpired();
        if (store.size() >= MAX_ENTRIES) {
            // 简单淘汰：清掉最早过期的一条，避免内存无界增长
            store.keySet().stream().findFirst().ifPresent(store::remove);
        }
        String id = "sug-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        store.put(id, new Entry(new LinkedHashMap<>(payload), System.currentTimeMillis() + TTL_MILLIS));
        return id;
    }

    /** 取回并校验过期；取不到返回 null（调用方给出"建议已过期，请重新生成"） */
    public Map<String, Object> get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Entry e = store.get(id);
        if (e == null || e.expireAt < System.currentTimeMillis()) {
            store.remove(id);
            return null;
        }
        return e.payload;
    }

    public int size() {
        return store.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(en -> en.getValue().expireAt < now);
    }

    private record Entry(Map<String, Object> payload, long expireAt) {
    }
}
