package com.aismp.digitalhuman.service;

import com.aismp.digitalhuman.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数字员工运行时注册表：装配结果缓存 + version 热更新。
 *
 * <p><b>热更新机制</b>：每次取用时先查一次 a_agent.version（主键索引，微秒级），
 * 与缓存中的 version 比对，不一致才重建。因此改配置、改挂载关系后
 * <b>不需要重启应用</b>，下一个请求就会用上新配置。
 *
 * <p><b>为什么不监听 Nacos/轮询</b>：数字员工配置是「低频变更、高频读取」，
 * 每次比对一个 int 比维护一条监听链路简单得多，且本机没有 Nacos 依赖。
 *
 * <p><b>为什么比对 version 而不是 update_time</b>：update_time 会因为
 * 「改了但内容没变」而变动，造成无谓重建；version 只在真实变更时自增。
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final Map<String, AssembledAgent> cache = new ConcurrentHashMap<>();
    private final AgentRepository repository;
    private final AgentAssembler assembler;

    public AgentRegistry(AgentRepository repository, AgentAssembler assembler) {
        this.repository = repository;
        this.assembler = assembler;
    }

    /**
     * 取数字员工（必要时重建）。
     *
     * @return 装配好的数字员工；配置不存在/停用/装配失败返回 null
     */
    public AssembledAgent get(String agentCode) {
        Integer latestVersion = repository.findVersionByCode(agentCode);
        if (latestVersion == null) {
            return null;
        }

        AssembledAgent cached = cache.get(agentCode);
        if (cached != null && cached.version() == latestVersion) {
            return cached;
        }

        synchronized (this) {
            // 双重检查：并发时避免重复装配
            cached = cache.get(agentCode);
            if (cached != null && cached.version() == latestVersion) {
                return cached;
            }
            AssembledAgent fresh = assembler.assemble(agentCode);
            if (fresh != null) {
                cache.put(agentCode, fresh);
                if (cached == null) {
                    log.info("数字员工 {} 首次装配 v{}", agentCode, fresh.version());
                } else {
                    log.info("数字员工 {} 配置变更，v{} → v{}，已重建",
                            agentCode, cached.version(), fresh.version());
                }
            }
            return fresh;
        }
    }

    /** 手动逐出缓存，下次取用时重建。 */
    public boolean reload(String agentCode) {
        boolean removed = cache.remove(agentCode) != null;
        log.info("数字员工 {} 手动刷新，原缓存{}", agentCode, removed ? "已清除" : "不存在");
        return removed;
    }

    /** 当前已缓存的数字员工及其版本，供运维界面观察运行时状态。 */
    public Map<String, Integer> cachedVersions() {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        cache.forEach((k, v) -> result.put(k, v.version()));
        return result;
    }
}
