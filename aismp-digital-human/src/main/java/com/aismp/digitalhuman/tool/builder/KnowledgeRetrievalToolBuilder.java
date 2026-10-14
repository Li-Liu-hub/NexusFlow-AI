package com.aismp.digitalhuman.tool.builder;

import com.aismp.digitalhuman.model.ToolDefPO;
import com.aismp.digitalhuman.tool.DigitalToolBuilder;
import com.aismp.digitalhuman.tool.KnowledgeRetrievalToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * 知识检索型工具构造器。
 *
 * <p><b>这是「工具类型可扩展」的第二个实现</b> —— 它的存在本身证明了架构改造是有意义的：
 * 新增这一种能力，没有修改 AgentAssembler / ToolCallbackFactory / 任何既有工具类，
 * 只是新增了这个类 + 一条工具定义数据。
 */
@Component
public class KnowledgeRetrievalToolBuilder implements DigitalToolBuilder {

    public static final String TYPE = "KB_RETRIEVAL";

    @Override
    public String supportedType() {
        return TYPE;
    }

    @Override
    public ToolCallback build(ToolDefPO def, ToolBuildContext ctx) {
        return new KnowledgeRetrievalToolCallback(
                def, ctx.json(), ctx.gatewayBaseUrl(), ctx.accessKey(), ctx.secretKey());
    }

    /**
     * 知识检索类型的专属校验：只检查 config_json 是否可解析。
     *
     * <p>注意这里**不校验 sqlTemplate** —— 本类型根本不使用它。
     * 这正是把校验下放到各类型的原因：统一校验会让非 SQL 类型无法创建。
     */
    @Override
    public void validate(ToolDefPO def) {
        if (def.getConfigJson() != null && !def.getConfigJson().isBlank()) {
            try {
                ctxJson.readValue(def.getConfigJson(), java.util.Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("config_json 不是合法 JSON: " + e.getMessage());
            }
        }
    }

    private static final tools.jackson.databind.ObjectMapper ctxJson =
            new tools.jackson.databind.ObjectMapper();
}
