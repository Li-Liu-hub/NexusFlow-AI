package com.aismp.aismpagent.config;

import org.springframework.ai.chat.memory.ChatMemory;                                    // ChatMemory 接口，定义 add/get/clear 三方法
import org.springframework.ai.chat.memory.MessageWindowChatMemory;                        // ChatMemory 实现，滑动窗口淘汰最早消息
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;       // JDBC 持久化实现，负责读写数据库
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect; // PG 方言，处理 timestamp 关键字转义等 PG 特有 SQL 差异
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;                                        // Spring JDBC 模板，执行 SQL

import javax.sql.DataSource;                                                              // 数据源（Spring Boot 从 application.yml 自动配置）

/**
 * 对话记忆持久化配置
 * 使用 PostgreSQL 替代原有的 FileBasedChatMemory
 *
 * 两层架构：
 *   存储层：JdbcChatMemoryRepository —— 管数据库读写（存哪、怎么存）
 *   算法层：MessageWindowChatMemory —— 管窗口策略（取多少、怎么淘汰）
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 存储层 Bean：JdbcChatMemoryRepository
     * v1.0.3 非 starter 版没有自动配置，需要手动构建：
     *   1. 从 DataSource 创建 JdbcTemplate（执行 SQL 的工具）
     *   2. 指定 PostgresChatMemoryRepositoryDialect（PG 方言，处理 timestamp 关键字转义）
     */
    @Bean
    public JdbcChatMemoryRepository jdbcChatMemoryRepository(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);                         // 包装数据源，提供 query/update 等便捷方法
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)                                               // 注入 JdbcTemplate，实际执行 INSERT/SELECT/DELETE
                .dialect(new PostgresChatMemoryRepositoryDialect())                       // 指定 PG 方言（SQL 适配，如 "timestamp" 加双引号）
                .build();
    }

    /**
     * 算法层 Bean：ChatMemory（MessageWindowChatMemory）
     * 包装 JdbcChatMemoryRepository，增加滑动窗口控制：
     *   - 按 conversationId 隔离不同会话
     *   - maxMessages=20：每个会话最多保留 20 条消息，超出淘汰最早的
     *   - 超出后自动 DELETE 最早消息，防止 token 爆炸
     */
    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)                                         // 注入存储层 Bean，实际读写交给 repository
                .maxMessages(20)                                                          // 滑动窗口上限，直接决定 token 消耗
                .build();
    }
}
