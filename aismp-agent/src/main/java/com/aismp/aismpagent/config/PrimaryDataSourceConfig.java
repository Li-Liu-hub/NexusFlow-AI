package com.aismp.aismpagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 显式声明<b>主数据源</b>（PostgreSQL）。
 *
 * <p><b>为什么必须显式写</b>：本模块引入业务数据源（MySQL，见
 * {@code terminal.config.BusinessDataSourceConfig}）之后，
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 因为
 * {@code @ConditionalOnMissingBean(DataSource.class)} 不再成立，<b>就不创建
 * {@code spring.datasource} 对应的那个主数据源了</b>。
 * 结果全模块只剩一个 MySQL DataSource，Spring AI 的 PgVectorStore 拿到它去执行
 * PostgreSQL 语法，启动即报：
 * <pre>
 *   bad SQL grammar [CREATE EXTENSION IF NOT EXISTS vector]
 *   at com.mysql.cj.jdbc.exceptions.SQLError ...
 * </pre>
 * 所以这里手动把 PG 数据源补上，并标 {@code @Primary}。
 *
 * <p><b>注意包路径</b>：Spring Boot 4 把 JDBC 相关从 {@code spring-boot-autoconfigure}
 * 拆到了独立的 {@code spring-boot-jdbc} 模块，包名随之变为
 * {@code org.springframework.boot.jdbc.autoconfigure.*}（3.x 时是
 * {@code org.springframework.boot.autoconfigure.jdbc.*}）。
 */
@Configuration
public class PrimaryDataSourceConfig {

    /** 绑定 spring.datasource（PG：向量库 + 对话记忆）。 */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    /** bean 名固定为 dataSource —— Spring AI 的自动配置按这个标准名装配。 */
    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }
}
