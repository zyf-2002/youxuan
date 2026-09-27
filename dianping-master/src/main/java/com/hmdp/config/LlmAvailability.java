package com.hmdp.config;

import jakarta.annotation.Resource;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.stereotype.Component;

/**
 * <p>
 * 判断大模型到底配没配好。
 * </p>
 * <p>
 * 为什么需要这个类：Spring AI 在创建 {@code OpenAiApi} 时会校验 api-key 不能为空，
 * 一旦为空就抛 {@code IllegalArgumentException: OpenAI API key must be set}，
 * <b>整个应用都起不来</b>——连秒杀、登录、商户这些和 AI 无关的功能一起挂掉。
 * </p>
 * <p>
 * 所以 application.yaml 给 api-key 准备了一个占位值，保证 Spring AI 能创建 bean、
 * 应用能正常启动；是不是真的配了 key，由这里来判断。占位值和 yaml 里必须保持一致。
 * </p>
 */
@Component
public class LlmAvailability {

    /**
     * 未配置时的占位 api-key。必须与 application.yaml 里 spring.ai.openai.api-key 的默认值一致。
     */
    public static final String UNCONFIGURED = "__llm_not_configured__";

    @Resource
    private OpenAiConnectionProperties connectionProperties;

    /**
     * 是否真的配置了可用的大模型 api-key
     */
    public boolean configured() {
        String apiKey = connectionProperties.getApiKey();
        return apiKey != null
                && !apiKey.trim().isEmpty()
                && !UNCONFIGURED.equals(apiKey.trim());
    }

    /**
     * 没配置时给用户看的话
     */
    public String unconfiguredMessage() {
        return "尚未配置大模型 API Key（环境变量 DEEPSEEK_API_KEY），对话与知识库摘要暂不可用。"
                + "请在 .env 里写入 DEEPSEEK_API_KEY=sk-xxxx 后重启。";
    }
}
