package com.aismp.aismpgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;

/**
 * 网关入口
 *
 * <p><b>阶段3 去 Dubbo 清理</b>：
 * <ul>
 *   <li>删掉启动类里 {@code @DubboReference} 的 DemoService 示例（原 doSayHello/doSayHello2，
 *       字段已随注解清理被移除，只剩悬空调用，编译不过）；</li>
 *   <li>排除项的包名按 Spring Boot 4 更新：JDBC 自动配置迁到了
 *       {@code org.springframework.boot.jdbc.autoconfigure}；</li>
 *   <li>不再排除 {@code HibernateJpaAutoConfiguration}：网关类路径上没有 JPA/Hibernate ORM
 *       （只有 hibernate-validator），该自动配置本就不会触发，引用它反而编译不过。</li>
 * </ul>
 *
 * <p><b>为什么仍要排除 DataSource 自动配置</b>：网关自身不用数据库，但依赖链
 * {@code aismp-common → mybatis-plus-spring-boot4-starter → spring-boot-starter-jdbc}
 * 会把 DataSource/HikariCP 带进类路径，不排除会在启动时报
 * "Failed to configure a DataSource: 'url' attribute is not specified"。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class})
public class AismpGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AismpGatewayApplication.class, args);
    }

}