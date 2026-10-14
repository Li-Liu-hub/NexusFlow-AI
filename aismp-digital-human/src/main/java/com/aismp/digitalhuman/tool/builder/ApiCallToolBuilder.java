package com.aismp.digitalhuman.tool.builder;

import com.aismp.digitalhuman.model.ToolDefPO;
import com.aismp.digitalhuman.tool.ApiCallToolCallback;
import com.aismp.digitalhuman.tool.DigitalToolBuilder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

/**
 * 接口调用型工具构造器 —— 把一条工具定义包成 {@link ApiCallToolCallback}。
 *
 * <p>与 {@link SqlTemplateToolBuilder} 形成对照：
 * <ul>
 *   <li>SQL_TEMPLATE：工具 = 一条只读 SQL，内核查自己的库；</li>
 *   <li>API_CALL：工具 = 一个业务 HTTP 接口，内核改为调业务服务。</li>
 * </ul>
 *
 * <p><b>注册方式即扩展方式</b>：本类标 {@code @Component} 就会被
 * {@code ToolCallbackFactory} 的构造注入（{@code List<DigitalToolBuilder>}）自动收集，
 * 装配器（AgentAssembler）一行都不用改。这就是当初把类型分发抽象出来的收益。
 *
 * <p>注入 {@code ObjectProvider<DiscoveryClient>} 而不是直接注入：
 * 未启用服务发现的环境（如单测、或关掉 nacos-discovery）里本类仍能装配，
 * 只是退化为用 config_json 里的 baseUrl 直连。
 */
@Component
public class ApiCallToolBuilder implements DigitalToolBuilder {

    public static final String TYPE = "API_CALL";

    /** 取不到时为 null，ApiCallToolCallback 会走 baseUrl 直连兜底。 */
    private final DiscoveryClient discoveryClient;

    public ApiCallToolBuilder(ObjectProvider<DiscoveryClient> discoveryClientProvider) {
        this.discoveryClient = discoveryClientProvider.getIfAvailable();
    }

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public ToolCallback build(ToolDefPO def, ToolBuildContext ctx) {
        return new ApiCallToolCallback(def, ctx.json(), discoveryClient);
    }

    /**
     * API_CALL 的专属校验：必须给出 endpoint。
     *
     * <p>SQL_TEMPLATE 校验的是「模板只读性」，本类型校验的是「目标接口是否明确」——
     * 两者规则完全不同，所以校验也必须跟着类型走（见 DigitalToolBuilder 接口的说明）。
     */
    @Override
    public void validate(ToolDefPO def) {
        String cfg = def.getConfigJson();
        if (cfg == null || cfg.isBlank()) {
            throw new IllegalArgumentException(
                    "API_CALL 类型工具必须配置 config_json（至少包含 endpoint，如 "
                            + "{\"serviceName\":\"aismp-agent\",\"endpoint\":\"/terminal/inventory/aggregate\"}）");
        }
        if (!cfg.contains("endpoint")) {
            throw new IllegalArgumentException("API_CALL 的 config_json 缺少 endpoint 字段");
        }
    }
}
