package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AgentProperties;
import com.hmdp.dto.AgentChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IAgentChatService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 智能助手对话与知识库接口
 */
@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentChatController {

    @Resource
    private IAgentChatService agentChatService;

    @Resource
    private AgentProperties properties;

    @Resource(name = "agentChatExecutor")
    private ThreadPoolTaskExecutor agentChatExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 会话列表
     */
    @GetMapping("/conversations")
    public Result listConversations() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(agentChatService.listConversations(user.getId()));
    }

    /**
     * 新建一个空会话，标题由第一条消息自动补上
     */
    @PostMapping("/conversations")
    public Result createConversation() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(agentChatService.createConversation(user.getId(), null));
    }

    /**
     * 会话内的历史消息
     */
    @GetMapping("/conversations/{id}/messages")
    public Result listMessages(@PathVariable("id") Long id) {
        UserDTO user = UserHolder.getUser();
        try {
            return Result.ok(agentChatService.listMessages(user.getId(), id));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/conversations/{id}")
    public Result deleteConversation(@PathVariable("id") Long id) {
        UserDTO user = UserHolder.getUser();
        try {
            agentChatService.deleteConversation(user.getId(), id);
            return Result.ok();
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 流式问答，事件类型：token / tool / citations / done / error
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest request, HttpServletResponse response) {
        UserDTO user = UserHolder.getUser();
        // 异步线程拿不到 UserHolder，先取出 userId
        final Long userId = user.getId();
        // 关掉 nginx 缓冲，否则流式会被攒成一坨
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        final SseEmitter emitter = new SseEmitter(properties.getLlm().getTimeoutMs() + 30_000L);
        // 先返回 emitter 让 Spring 初始化 handler，再在独立线程推送事件
        agentChatExecutor.execute(() -> streamChat(userId, request, emitter));
        return emitter;
    }

    /**
     * 在独立线程里完成检索与流式回答
     */
    private void streamChat(Long userId, AgentChatRequest request, SseEmitter emitter) {
        try {
            IAgentChatService.ChatResult result = agentChatService.streamAnswer(
                    userId,
                    request.getConversationId(),
                    request.getQuestion(),
                    token -> sendEvent(emitter, "token", token),
                    tool -> sendEvent(emitter, "tool", tool));

            sendEvent(emitter, "citations", writeJson(
                    result.getCitations() == null ? Collections.emptyList() : result.getCitations()));
            sendEvent(emitter, "done", String.valueOf(result.getConversationId()));
            emitter.complete();
        } catch (IllegalArgumentException | NonTransientAiException e) {
            log.warn("对话请求被拒绝：{}", e.getMessage());
            sendEvent(emitter, "error", StrUtil.blankToDefault(e.getMessage(), "请求失败"));
            emitter.complete();
        } catch (Exception e) {
            log.error("对话处理失败", e);
            sendEvent(emitter, "error", "对话处理失败：" + e.getMessage());
            emitter.complete();
        }
    }

    /**
     * 发 SSE 事件；连接已断开时只记录日志，不再向上抛
     */
    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            // 这里不能用 StrUtil.blankToDefault：Hutool 的 isBlank 把纯空白也当空，
            // 模型单独吐出的换行 token（"\n" / "\n\n"）会被整块换成空串，
            // 正文分段就全没了。
            emitter.send(SseEmitter.event().name(name).data(data == null ? "" : data));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 事件 {} 发送失败，连接可能已断开：{}", name, e.getMessage());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化失败：{}", e.getMessage());
            return "[]";
        }
    }
}
