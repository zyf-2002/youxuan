package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * <p>
 * 智能助手流式回答的线程池
 * </p>
 * <p>
 * 必须有独立线程：Spring MVC 的 {@code SseEmitter} 要在控制器返回之后才由
 * ResponseBodyEmitterReturnValueHandler 初始化 handler，若在控制器方法内同步
 * 推送事件，所有事件都会堆在 earlySendAttempts 里，直到方法返回才一次性刷出，
 * 流式就退化成「憋完再吐」。
 * </p>
 */
@Configuration
public class AgentChatExecutorConfig {

    @Bean(name = "agentChatExecutor")
    public ThreadPoolTaskExecutor agentChatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("agent-chat-");
        executor.initialize();
        return executor;
    }
}
