package com.aismp.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 编排层配置（对应原 Python 版 config.py 的 Settings）。
 *
 * <p>所有敏感值只从环境变量注入，配置文件里不写死 —— 与原版口径一致。
 */
@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {

    private final Llm llm = new Llm();
    private final DigitalHuman digitalHuman = new DigitalHuman();
    private final Terminal terminal = new Terminal();

    public Llm getLlm() {
        return llm;
    }

    public DigitalHuman getDigitalHuman() {
        return digitalHuman;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    /**
     * 终端业务接口（aismp-agent）。
     *
     * <p>这里配的是<b>网关</b>地址（容器 http://gateway:8090，本机 127.0.0.1:8090）：
     * 业务接口在网关上做身份注入与接口登记，直连 agent 会返回 50000 系统错误。
     * 编排层用服务账号登录换 Bearer 令牌（见 PortalTokenProvider）。
     */
    public static class Terminal {
        private String baseUrl = "http://127.0.0.1:8090";
        /** 根仓编码：它的可见范围是全部仓 */
        private String rootWarehouse = "9B00";
        /** 已知仓编码（用于推算下级仓；空则由取数结果自行归纳） */
        private String warehouses = "9B00,9BZA,9BZA01,9BZA02,9BZA03,9BZA04";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getRootWarehouse() {
            return rootWarehouse;
        }

        public void setRootWarehouse(String rootWarehouse) {
            this.rootWarehouse = rootWarehouse;
        }

        public String getWarehouses() {
            return warehouses;
        }

        public void setWarehouses(String warehouses) {
            this.warehouses = warehouses;
        }

        public java.util.List<String> warehouseList() {
            if (warehouses == null || warehouses.isBlank()) {
                return java.util.List.of();
            }
            return java.util.Arrays.stream(warehouses.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    /** 路由用 LLM（OpenAI 兼容端点，只做意图分类，温度固定 0）。 */
    public static class Llm {
        private String baseUrl = "https://api.z.ai/api/coding/paas/v4";
        private String apiKey = "";
        private String model = "glm-5.2";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    /**
     * 数字员工内核连接信息。
     *
     * <p>当前只实现「直连内核」形态（原版 DIGITAL_HUMAN_VIA_GATEWAY=false 那条分支）：
     * 网关 AK/SK 签名（含 nonce 防重放）属于下一步，见类注释里的 TODO。
     */
    public static class DigitalHuman {
        private String directUrl = "http://127.0.0.1:8126";
        private String agentCode = "terminal_manager";
        private String serviceUser = "";
        private String servicePassword = "";

        public String getDirectUrl() {
            return directUrl;
        }

        public void setDirectUrl(String directUrl) {
            this.directUrl = directUrl;
        }

        public String getAgentCode() {
            return agentCode;
        }

        public void setAgentCode(String agentCode) {
            this.agentCode = agentCode;
        }

        public String getServiceUser() {
            return serviceUser;
        }

        public void setServiceUser(String serviceUser) {
            this.serviceUser = serviceUser;
        }

        public String getServicePassword() {
            return servicePassword;
        }

        public void setServicePassword(String servicePassword) {
            this.servicePassword = servicePassword;
        }
    }
}
