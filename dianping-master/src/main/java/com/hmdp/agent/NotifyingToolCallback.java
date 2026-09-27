package com.hmdp.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 工具回调包装器，比 Spring AI 原生回调多三件事：
 * 调用前后打日志、调用前发进度提示、把异常转成文本回给模型、限制单次问答的工具调用次数
 * （Spring AI 的内置工具循环没有轮次上限）。
 */
@Slf4j
public final class NotifyingToolCallback implements ToolCallback {

    /**
     * 工具返回空内容时给模型的占位文本
     */
    private static final String EMPTY_RESULT = "（工具没有返回内容）";

    /**
     * 日志里参数和结果各留多长，太长的正文会把日志淹掉
     */
    private static final int LOG_PREVIEW_LIMIT = 160;

    private final ToolCallback delegate;

    private final Consumer<String> onToolCall;

    /**
     * 本次问答已执行的调用次数，同一批工具共享
     */
    private final AtomicInteger callCount;

    /**
     * 本次问答允许的调用总次数，&lt;=0 表示不限
     */
    private final int maxCalls;

    /**
     * 日志前缀，用来把一次问答里的多行日志串起来（例如「会话 123」）
     */
    private final String logPrefix;

    private NotifyingToolCallback(ToolCallback delegate, Consumer<String> onToolCall,
                                  AtomicInteger callCount, int maxCalls, String logPrefix) {
        this.delegate = delegate;
        this.onToolCall = onToolCall;
        this.callCount = callCount;
        this.maxCalls = maxCalls;
        this.logPrefix = logPrefix == null ? "" : logPrefix;
    }

    /**
     * 把对象上所有 {@code @Tool} 方法包装成 ToolCallback
     *
     * @param toolObject 带 @Tool 方法的 Bean
     * @param onToolCall 每次调用前的回调，参数是工具名；可为 null
     * @param maxCalls   本次问答允许的调用总次数，&lt;=0 表示不限
     * @param logPrefix  日志前缀，用来把一次问答的日志串起来；可为 null
     */
    public static ToolCallback[] wrapAll(Object toolObject, Consumer<String> onToolCall,
                                         int maxCalls, String logPrefix) {
        ToolCallback[] originals = ToolCallbacks.from(toolObject);
        // 计数器必须在包装前建好，这样同一批工具共享一个配额
        AtomicInteger sharedCount = new AtomicInteger(0);
        ToolCallback[] wrapped = new ToolCallback[originals.length];
        for (int i = 0; i < originals.length; i++) {
            wrapped[i] = new NotifyingToolCallback(originals[i], onToolCall, sharedCount,
                    maxCalls, logPrefix);
        }
        return wrapped;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = getToolDefinition().name();
        // 超限就直接返回，不执行也不发进度提示
        if (maxCalls > 0 && callCount.incrementAndGet() > maxCalls) {
            log.warn("{} 工具 {} 超出本次问答的调用上限（{} 次），拒绝执行，参数 {}",
                    logPrefix, name, maxCalls, brief(toolInput));
            return "工具调用次数已达上限（" + maxCalls + " 次），本次不再执行该工具。"
                    + "请立即基于你已经拿到的信息作答，不要再请求调用工具；"
                    + "信息不足就如实说明没查到，不要编造。";
        }
        if (onToolCall != null) {
            onToolCall.accept(name);
        }
        // 调用前先记一条：日志里要能看出「调了哪个工具、模型给了什么参数」
        log.info("{} 调用工具 {}，参数 {}", logPrefix, name, brief(toolInput));
        long start = System.currentTimeMillis();
        try {
            String result = delegate.call(toolInput, toolContext);
            log.info("{} 工具 {} 执行完成，耗时 {}ms，结果长度 {}，结果预览 {}",
                    logPrefix, name, System.currentTimeMillis() - start,
                    result == null ? 0 : result.length(), brief(result));
            return result == null || result.trim().isEmpty() ? EMPTY_RESULT : result;
        } catch (Exception e) {
            log.error("{} 工具 {} 执行异常（耗时 {}ms），参数 {}",
                    logPrefix, name, System.currentTimeMillis() - start, brief(toolInput), e);
            return "工具执行失败：" + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 参数和结果都可能很长，日志里只留一行短预览
     */
    private static String brief(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (oneLine.isEmpty()) {
            return "（空）";
        }
        return oneLine.length() <= LOG_PREVIEW_LIMIT
                ? oneLine
                : oneLine.substring(0, LOG_PREVIEW_LIMIT) + "…";
    }
}
