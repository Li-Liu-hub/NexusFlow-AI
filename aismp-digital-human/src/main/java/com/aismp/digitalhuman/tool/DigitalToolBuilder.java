package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.ToolDefPO;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 数字员工工具构造器：一种「工具类型」对应一个实现。
 *
 * <p><b>这是本次架构优化的核心抽象</b>。
 *
 * <p>改造前：{@code AgentAssembler} 里直接 {@code new SqlTemplateToolCallback(...)} ——
 * 装配器被迫认识每一种具体工具实现。这意味着每加一种工具类型（HTTP 接口、Spring Bean 方法、
 * MCP Server、知识库检索）都要回头改装配器，而装配器本应只认配置。
 *
 * <p>改造后：装配器只调 {@link ToolCallbackFactory#create}，
 * 新增类型 = 新增一个实现类 + 声明 {@link #supportedType()}，**装配器一行都不用动**。
 *
 * <p>实现类注册为 Spring Bean 即被自动发现（见 ToolCallbackFactory 的构造注入）。
 */
public interface DigitalToolBuilder {

    /**
     * 本构造器负责的工具类型，与 {@code a_llm_tool.tool_type} 取值一一对应。
     *
     * <p>用字符串而非枚举：类型集合会随扩展增长，枚举会让「加类型」变成「改公共代码」。
     */
    String supportedType();

    /**
     * 把一条工具定义构造为 Spring AI 可调用的 ToolCallback。
     *
     * @param def 工具定义（a_llm_tool 一行）
     * @param ctx 构造所需的外部依赖
     * @throws IllegalArgumentException 定义不合法（消息面向使用者可读）
     */
    ToolCallback build(ToolDefPO def, ToolBuildContext ctx);

    /**
     * 类型专属校验。
     *
     * <p><b>为什么校验也必须跟着类型走</b>：首版只在构造侧做了类型分发，
     * 校验仍由 ToolAdminController 用一套 SQL 规则统一处理 —— 结果
     * KB_RETRIEVAL 类型（sqlTemplate 为 null）创建时直接被「SQL 模板不能为空」拒掉。
     * 抽象只做一半，等于没做。
     *
     * <p>通用校验（工具名/描述/schema 等）由工厂统一执行，本方法只关心该类型的特有规则。
     *
     * @throws IllegalArgumentException 校验不通过
     */
    default void validate(ToolDefPO def) {
        // 默认无类型专属规则
    }

    /**
     * 构造工具所需的外部依赖集合。
     *
     * <p>打成 record 传递，而不是给 build() 加一长串参数 ——
     * 每加一种工具类型可能需要更多依赖，用参数列表会不断破坏所有实现类的签名。
     */
    record ToolBuildContext(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper json,
            String tenantCode,
            ToolCallAuditor auditor,
            /** 调用外部服务（如知识库检索）时的出口 */
            String gatewayBaseUrl,
            String accessKey,
            String secretKey,
            /** 审批单落单出口：approval_required=1 的工具被调用时由拦截器使用 */
            ToolApprovalService approvalService
    ) {
    }
}
