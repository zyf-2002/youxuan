package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <p>
 * 知识库与 Agent 对话相关配置
 * </p>
 * <p>
 * 大模型的接口地址与 api-key 由 Spring AI 统一管理，见 {@code spring.ai.openai.*}（application.yaml），
 * api-key 通过环境变量 DEEPSEEK_API_KEY 注入，不写死在代码或配置文件里。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.agent")
public class AgentProperties {

    /**
     * 是否开启知识库与对话功能
     */
    private boolean enabled = true;

    /**
     * 知识库扫描间隔，单位毫秒
     */
    private long scanIntervalMs = 15000;

    /**
     * 应用启动后首次扫描的延迟，避开启动高峰
     */
    private long scanInitialDelayMs = 20000;

    private Llm llm = new Llm();

    /**
     * <p>
     * agent 一次问答里最多执行几次工具调用。
     * </p>
     * <p>
     * 这个上限必须自己兜住：Spring AI 的内置工具调用循环<b>没有任何轮次上限</b>
     * （{@code ToolCallingProperties} 只有 throwExceptionOnError 一个开关），
     * 模型若反复要求调工具，循环会一直转下去，每轮都是一次完整的模型往返。
     * 见 {@code NotifyingToolCallback}。
     * </p>
     * <p>
     * 注意这里是<b>累计调用次数</b>，不是「轮数」——一轮里模型可以并行发多个工具调用，
     * 那些都会各自计数。
     * </p>
     */
    private int maxToolCalls = 6;

    /**
     * <p>
     * 只保留「用哪个模型」这一层选择。
     * </p>
     * <p>
     * 接口地址、api-key、对话默认模型都已交给 Spring AI 管理，
     * 见 {@code spring.ai.openai.*}（application.yaml）。
     * 这里不再重复定义，避免同一件事有两处真相。
     * </p>
     */
    @Data
    public static class Llm {
        /**
         * 便宜快速的角色，用于检索管线里的分类/评分/重写这类小任务
         */
        private String fastModel = "deepseek-chat";

        /**
         * 单次问答的超时，用于决定 SSE 连接的最长存活时间
         */
        private int timeoutMs = 180000;
    }
}
