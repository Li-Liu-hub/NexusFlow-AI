package com.aismp.aismpagent.terminal.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
// MyBatis-Plus 3.5.9+ 模块重构：MybatisSqlSessionFactoryBean 从
// com.baomidou.mybatisplus.extension.spring 迁到了 com.baomidou.mybatisplus.spring
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 终端业务的数据源与 MyBatis-Plus 配置。
 *
 * <p><b>为什么要单独配</b>：本模块现在有<b>两个数据源</b> ——
 * <ul>
 *   <li><b>PostgreSQL</b>：Spring AI 的 PGVector 向量库 + JDBC 对话记忆（走的 {@code spring.datasource}，
 *       自动配置，不能动）；</li>
 *   <li><b>MySQL</b>：终端业务表 {@code terminal / warehouse / transfer_order / transfer_order_item / notice}。</li>
 * </ul>
 * 业务代码是从 {@code aismp-backend} 迁来的，那边的 Mapper 默认连自己的库；
 * 这里必须显式把 Mapper 绑到 MySQL，否则会误连 PG 报表不存在。
 *
 * <p><b>关键约定（照抄自 40-terminal-schema.sql 的注释）</b>：业务表列名一律用<b>驼峰</b>，
 * 且 mybatis-plus 配了 {@code map-underscore-to-camel-case: false}，
 * 实体字段名与列名必须逐字相同（如 {@code createdAt} ↔ {@code createdAt}）。
 * 这里用代码显式设置，避免依赖外部 yml 是否被读到。
 */
@Configuration
@MapperScan(
        basePackages = "com.aismp.aismpagent.terminal.mapper",
        sqlSessionFactoryRef = "businessSqlSessionFactory"
)
public class BusinessDataSourceConfig {

    /** 业务数据源（MySQL）。前缀 business.datasource 见 application.yml。 */
    @Bean("businessDataSource")
    @ConfigurationProperties(prefix = "business.datasource")
    public DataSource businessDataSource() {
        return DataSourceBuilder.create().build();
    }

    /** MyBatis-Plus 的 SqlSessionFactory，只认业务数据源。 */
    @Bean("businessSqlSessionFactory")
    public SqlSessionFactory businessSqlSessionFactory(
            @Qualifier("businessDataSource") DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        // 列名是驼峰，不要做下划线→驼峰转换，否则 createdAt 会被映射成 created_at 而查不到
        configuration.setMapUnderscoreToCamelCase(false);
        factory.setConfiguration(configuration);
        return factory.getObject();
    }
}
