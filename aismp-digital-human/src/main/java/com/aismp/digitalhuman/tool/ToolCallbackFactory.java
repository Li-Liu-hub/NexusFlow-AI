package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.ToolDefPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具构造工厂：按 {@code tool_type} 分发到对应的 {@link DigitalToolBuilder}。
 *
 * <p>所有实现类由 Spring 注入（构造参数 {@code List<DigitalToolBuilder>} 自动收集），
 * 新增一种工具类型只需新增一个 {@code @Component} 实现类 —— 本类和装配器都不必修改。
 */
@Component
public class ToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(ToolCallbackFactory.class);

    /** 默认类型：存量工具没有 tool_type 时按 SQL 模板处理（向后兼容） */
    public static final String DEFAULT_TYPE = "SQL_TEMPLATE";

    private final Map<String, DigitalToolBuilder> builders;

    public ToolCallbackFactory(List<DigitalToolBuilder> builderList) {
        this.builders = builderList.stream()
                .collect(Collectors.toMap(
                        DigitalToolBuilder::supportedType,
                        Function.identity(),
                        (a, b) -> {
                            // 同名类型重复注册是配置错误，明确报出来而不是静默覆盖
                            throw new IllegalStateException(
                                    "工具类型重复注册: " + a.supportedType());
                        },
                        LinkedHashMap::new));
        log.info("工具构造器注册完成，已支持类型: {}", builders.keySet());
    }

    /**
     * 按类型构造工具。
     *
     * @throws IllegalArgumentException 类型未支持，或该类型定义不合法
     */
    public ToolCallback create(ToolDefPO def, DigitalToolBuilder.ToolBuildContext ctx) {
        String type = (def.getToolType() == null || def.getToolType().isBlank())
                ? DEFAULT_TYPE
                : def.getToolType();

        DigitalToolBuilder builder = builders.get(type);
        if (builder == null) {
            throw new IllegalArgumentException(
                    "不支持的工具类型 [" + type + "]，已支持: " + builders.keySet());
        }
        ToolCallback raw = builder.build(def, ctx);
        // 统一包一层审计装饰器：横切关注点收在一处，新增工具类型自动获得审计，
        // 实现类不必也不该自己写一遍（首版写在 SqlTemplateToolCallback 里，新类型就漏了）
        return new AuditedToolCallback(def, raw, ctx.auditor(), ctx.json(), ctx.approvalService());
    }

    /** 当前支持的工具类型清单，供运维界面展示可选值。 */
    public List<String> supportedTypes() {
        return List.copyOf(builders.keySet());
    }

    /**
     * 校验工具定义：通用规则 + 该类型的专属规则。
     *
     * <p>把校验也收进工厂，是为了让「加一种工具类型」这件事保持单一入口 ——
     * 调用方（ToolAdminController / 装配器）永远不需要知道有哪些类型。
     *
     * @throws IllegalArgumentException 校验不通过，消息可直接展示给使用者
     */
    public void validate(ToolDefPO def) {
        com.aismp.digitalhuman.tool.SqlGuard.validateCommon(def);

        String type = (def.getToolType() == null || def.getToolType().isBlank())
                ? DEFAULT_TYPE
                : def.getToolType();
        DigitalToolBuilder builder = builders.get(type);
        if (builder == null) {
            throw new IllegalArgumentException(
                    "不支持的工具类型 [" + type + "]，已支持: " + builders.keySet());
        }
        builder.validate(def);
    }
}
