package com.aismp.digitalhuman.tool.builder;

import com.aismp.digitalhuman.model.ToolDefPO;
import com.aismp.digitalhuman.tool.DigitalToolBuilder;
import com.aismp.digitalhuman.tool.SqlTemplateToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * SQL 模板型工具构造器 —— 把一条工具定义包成 {@link SqlTemplateToolCallback}。
 *
 * <p>执行细节（三重安全闸门、命名参数绑定、强制 LIMIT）都在 SqlTemplateToolCallback 里，
 * 本类只负责把它装配出来。这样「类型分发」与「执行实现」各司其职。
 */
@Component
public class SqlTemplateToolBuilder implements DigitalToolBuilder {

    public static final String TYPE = "SQL_TEMPLATE";

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public ToolCallback build(ToolDefPO def, ToolBuildContext ctx) {
        return new SqlTemplateToolCallback(
                def, ctx.jdbc(), ctx.json(), ctx.tenantCode(), ctx.auditor());
    }

    /** SQL 类型的专属校验：模板只读性 + 参数一致性（通用部分由工厂执行）。 */
    @Override
    public void validate(ToolDefPO def) {
        com.aismp.digitalhuman.tool.SqlGuard.validateSqlTemplate(def);
    }
}
