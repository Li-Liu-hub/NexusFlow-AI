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

    public Llm getLlm() {
        return llm;
    }

    public DigitalHuman getDigitalHuman() {
        return digitalHuman;
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
