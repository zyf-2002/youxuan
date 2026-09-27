package com.hmdp.agent;

import com.hmdp.rag.retrieval.Citation;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具调用上下文：带 userId 进去，带 citations 回来。
 * <p>
 * 工具跑在 Spring AI 的回调线程里，拿不到 UserHolder 的 ThreadLocal，
 * 所以身份要通过 Spring AI 的 {@link ToolContext} 显式传递。每次请求 new 一个。
 */
public class AgentToolContext {

    /**
     * 在 Spring AI {@link ToolContext} 的 map 里存放本对象的 key
     */
    public static final String KEY = "hmdpAgentContext";

    private final Long userId;

    private final List<Citation> citations = new ArrayList<>();

    public AgentToolContext(Long userId) {
        this.userId = userId;
    }

    /**
     * 从 Spring AI 的 ToolContext 里取回本对象；取不到返回 null。
     */
    public static AgentToolContext from(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(KEY);
        return value instanceof AgentToolContext ? (AgentToolContext) value : null;
    }

    public Long getUserId() {
        return userId;
    }

    /**
     * 知识库工具命中时把引用登记进来，供前端渲染可点击的来源卡片
     */
    public void addCitations(List<Citation> found) {
        if (found == null || found.isEmpty()) {
            return;
        }
        for (Citation citation : found) {
            boolean duplicated = citations.stream().anyMatch(existing ->
                    existing.getShopId() != null
                            && existing.getShopId().equals(citation.getShopId()));
            if (!duplicated) {
                citations.add(citation);
            }
        }
    }

    public List<Citation> getCitations() {
        return Collections.unmodifiableList(citations);
    }
}
