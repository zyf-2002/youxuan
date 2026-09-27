package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.AgentToolContext;
import com.hmdp.agent.NotifyingToolCallback;
import com.hmdp.agent.tool.AgentTools;
import com.hmdp.config.AgentProperties;
import com.hmdp.config.LlmAvailability;
import com.hmdp.entity.AgentConversation;
import com.hmdp.entity.AgentMessage;
import com.hmdp.mapper.AgentConversationMapper;
import com.hmdp.mapper.AgentMessageMapper;
import com.hmdp.rag.retrieval.Citation;
import com.hmdp.service.IAgentChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 对话服务：把 {@link AgentTools} 挂到 Spring AI 的 ChatClient 上，
 * 由框架驱动工具调用循环（本类里没有那个循环，别找了），回答流式返回。
 * 需要自己接的两件事：工具进度提示见 {@link NotifyingToolCallback}，
 * 引用回传见 {@link AgentToolContext}。
 */
@Slf4j
@Service
public class AgentChatServiceImpl
        extends ServiceImpl<AgentConversationMapper, AgentConversation>
        implements IAgentChatService {

    private static final int TITLE_MAX_LENGTH = 20;

    /**
     * 带进上下文的历史消息条数
     */
    private static final int HISTORY_LIMIT = 6;

    /**
     * 系统提示词，按「数据来源 / 调用工具时 / 回答格式」三块组织。
     * <p>
     * 这些规则是唯一的约束，代码侧没有兜底（模型偶尔不听话就直接漏给用户），
     * 所以改动时尤其要注意规则之间别互相矛盾。
     * 唯一的例外是 {@code agent.html}，它会把正文里残留的 [n] 编号抹掉。
     * </p>
     * <p>
     * 两条规则写得最硬，因为它们最容易被模型绕过：
     * </p>
     * <ul>
     *   <li>【数据来源】里那条「上一轮的结果不算数」——上下文里带着历史消息，
     *       模型很容易拿上一轮查到的店直接回答，而不是重新检索知识库；</li>
     *   <li>【调用工具时】里那条「正文必须是空的」——模型习惯先说一句
     *       「我来帮你查一下」或 "I'll search for ..."，而这句话会原样显示给用户。</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT =
            "你是优选生活的智能助手，可以调用工具帮用户查询平台数据。\n"
                    + "\n【数据来源】\n"
                    + "- 涉及本平台商户的问题，一律先调用工具、再用工具返回的内容作答，"
                    + "不要凭自己的记忆或印象编造。\n"
                    + "- 下面这些一律算站内数据问题，必须先调用工具："
                    + "找店、推荐店、哪家好、值不值得去、有什么优惠券，"
                    + "以及商户的店名、地址、人均、评分、营业时间、特色。\n"
                    + "- 只有闲聊、以及不指向具体商户的纯常识（例如「火锅一般人均多少」）可以不调工具直接回答。\n"
                    + "- 只要问题指向本平台的商户，每一轮都要重新查一次，"
                    + "不能因为「刚才查过」或者「我大概知道」就跳过工具。\n"
                    + "- 上下文里可能有你上一轮查到的结果和写过的回答。它们只能帮你理解用户在问什么，"
                    + "不能当作本轮的依据：用户再问一次、换个说法问、或者问另一家商户，都要重新查，"
                    + "不要把上一轮出现过的店名、地址、人均直接搬到本轮的回答里。\n"
                    + "- 只能介绍本平台的商户：不要推荐、列举、对比平台之外的店铺，"
                    + "也不要用记忆补充平台外的店名、地址、人均。平台里没有就如实说没有，不要拿站外的店凑数。\n"
                    + "- 你没有联网能力，不要说「我上网查了」「网上评价说」这类话。\n"
                    + "- 工具没查到就如实说没查到，不要编造，也不要假装查过。\n"
                    + "\n【调用工具时】\n"
                    + "- 要调用工具，就只发出工具调用，正文必须是空的——任何语言、任何字数都不行。\n"
                    + "- 「我来帮你查一下」「让我看看」「Let me check」「I'll search for ...」这类过渡话一律不要说："
                    + "它们会原样显示给用户，是纯粹的噪音。\n"
                    + "- 需要连续调用多个工具时，两次调用之间同样不要说话；"
                    + "文字只在你已经拿到工具结果、准备给出最终回答时才输出。\n"
                    + "\n【回答格式】\n"
                    + "- 用中文回答。除了店名之类的专有名词，不要出现英文单词或英文句子。\n"
                    + "- 回答要简洁，条目较多时用分行列表。\n"
                    + "- 不要在回答里输出引用编号或来源标注（如 [1]、[2]），店铺链接由前端统一放在回答末尾。\n"
                    + "- 不要向用户暴露工具名称、参数、JSON 等实现细节。\n"
                    + "- 推荐多家商户时，每家单独成段、段与段之间空一行；"
                    + "同一家商户的多条信息分行写，不要把几家店的内容挤在同一段里。";

    /**
     * 工具名 -> 给用户看的提示语
     */
    private static final Map<String, String> TOOL_LABELS = new HashMap<>();

    static {
        TOOL_LABELS.put(AgentTools.SEARCH_KNOWLEDGE_BASE, "正在检索商户知识库");
        TOOL_LABELS.put(AgentTools.SEARCH_SHOPS, "正在搜索商户");
        TOOL_LABELS.put(AgentTools.GET_SHOP_DETAIL, "正在查询商户详情");
        TOOL_LABELS.put(AgentTools.LIST_HOT_BLOGS, "正在查看热门笔记");
    }

    private static final String FALLBACK_ANSWER =
            "抱歉，我没能整理出答案。可以换个说法再问一次，或者问得更具体一些。";

    @Resource
    private AgentMessageMapper agentMessageMapper;

    @Resource
    private ChatClient.Builder chatClientBuilder;

    @Resource
    private AgentTools agentTools;

    @Resource
    private AgentProperties properties;

    @Resource
    private LlmAvailability llmAvailability;

    private ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void initChatClient() {
        this.chatClient = chatClientBuilder.build();
    }

    // ------------------------------------------------------------------
    // 会话管理
    // ------------------------------------------------------------------

    @Override
    public List<AgentConversation> listConversations(Long userId) {
        return lambdaQuery()
                .eq(AgentConversation::getUserId, userId)
                .orderByDesc(AgentConversation::getUpdateTime)
                .list();
    }

    @Override
    public AgentConversation createConversation(Long userId, String title) {
        AgentConversation conversation = new AgentConversation()
                .setUserId(userId)
                .setTitle(StrUtil.blankToDefault(title, "新对话"));
        save(conversation);
        return conversation;
    }

    @Override
    public List<AgentMessage> listMessages(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
        return agentMessageMapper.selectList(new QueryWrapper<AgentMessage>()
                .eq("conversation_id", conversationId)
                .orderByAsc("id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long userId, Long conversationId) {
        requireOwned(userId, conversationId);
        agentMessageMapper.delete(new QueryWrapper<AgentMessage>()
                .eq("conversation_id", conversationId));
        removeById(conversationId);
    }

    // ------------------------------------------------------------------
    // 问答主流程
    // ------------------------------------------------------------------

    @Override
    public ChatResult streamAnswer(Long userId, Long conversationId, String question,
                                   Consumer<String> onToken, Consumer<String> onTool) {
        String normalized = StrUtil.blankToDefault(question, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        // 没配 key 就直接报错，不要跑去模型接口换个 401 回来
        if (!llmAvailability.configured()) {
            throw new IllegalArgumentException(llmAvailability.unconfiguredMessage());
        }

        AgentConversation conversation = resolveConversation(userId, conversationId, normalized);
        List<Message> history = loadHistory(conversation.getId());

        // 先落用户消息，后面失败也不会丢提问
        agentMessageMapper.insert(new AgentMessage()
                .setConversationId(conversation.getId())
                .setRole(AgentMessage.ROLE_USER)
                .setContent(normalized));

        // 每次请求一份上下文：带 userId 进去，带 citations 回来
        AgentToolContext toolContext = new AgentToolContext(userId);
        StringBuilder answer = new StringBuilder();

        ToolCallback[] callbacks = NotifyingToolCallback.wrapAll(agentTools, name -> {
            if (onTool != null) {
                onTool.accept(labelOf(name));
            }
        }, properties.getMaxToolCalls(), "会话 " + conversation.getId());

        for (ChatResponse response : chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .messages(history)
                .user(normalized)
                .toolCallbacks(callbacks)
                .toolContext(Map.of(AgentToolContext.KEY, toolContext))
                .stream()
                .chatResponse()
                .toIterable()) {

            String delta = textOf(response);
            if (delta.isEmpty()) {
                continue;
            }
            // 模型在调工具前可能吐一句过渡话（提示词【调用工具时】已禁止），
            // 这里不做拦截：漏了就漏了，换来的是不必维护「前言 / 答案」的分界逻辑。
            answer.append(delta);
            onToken.accept(delta);
        }

        String finalAnswer = answer.toString().trim();
        if (finalAnswer.isEmpty()) {
            finalAnswer = FALLBACK_ANSWER;
            onToken.accept(finalAnswer);
        }

        List<Citation> citations = toolContext.getCitations();
        log.info("会话 {} 回答完成：引用数={}，回答长度={}",
                conversation.getId(), citations.size(), finalAnswer.length());

        return finish(conversation, normalized, finalAnswer, citations);
    }

    /**
     * 落库助手消息、刷新会话，并返回结果
     */
    private ChatResult finish(AgentConversation conversation, String question,
                              String answer, List<Citation> citations) {
        agentMessageMapper.insert(new AgentMessage()
                .setConversationId(conversation.getId())
                .setRole(AgentMessage.ROLE_ASSISTANT)
                .setContent(answer)
                .setCitations(writeCitations(citations)));

        AgentConversation update = new AgentConversation()
                .setId(conversation.getId())
                .setUpdateTime(LocalDateTime.now());
        if ("新对话".equals(conversation.getTitle())) {
            update.setTitle(StrUtil.maxLength(question, TITLE_MAX_LENGTH));
        }
        updateById(update);

        return new ChatResult(conversation.getId(), answer, citations);
    }

    /**
     * 取最近几条历史消息，让多轮对话能接上上下文
     */
    private List<Message> loadHistory(Long conversationId) {
        List<AgentMessage> recent = agentMessageMapper.selectList(new QueryWrapper<AgentMessage>()
                .eq("conversation_id", conversationId)
                .orderByDesc("id")
                .last("LIMIT " + HISTORY_LIMIT));
        if (recent == null || recent.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.reverse(recent);

        List<Message> history = new ArrayList<>();
        for (AgentMessage message : recent) {
            if (StrUtil.isBlank(message.getContent())) {
                continue;
            }
            if (AgentMessage.ROLE_USER.equals(message.getRole())) {
                history.add(new UserMessage(message.getContent()));
            } else if (AgentMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                history.add(new AssistantMessage(message.getContent()));
            }
        }
        return history;
    }

    private String labelOf(String toolName) {
        return TOOL_LABELS.getOrDefault(toolName, "正在处理");
    }

    /**
     * 从流式响应块里取出文本增量，取不到返回空串
     */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output == null || output.getText() == null) {
            return "";
        }
        return output.getText();
    }

    private AgentConversation resolveConversation(Long userId, Long conversationId, String question) {
        if (conversationId == null) {
            return createConversation(userId, StrUtil.maxLength(question, TITLE_MAX_LENGTH));
        }
        AgentConversation conversation = getById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        return conversation;
    }

    private void requireOwned(Long userId, Long conversationId) {
        AgentConversation conversation = getById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
    }

    private String writeCitations(List<Citation> citations) {
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (Exception e) {
            log.warn("序列化引用失败：{}", e.getMessage());
            return "[]";
        }
    }
}
