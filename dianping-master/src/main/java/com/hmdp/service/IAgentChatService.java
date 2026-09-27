package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.rag.retrieval.Citation;
import com.hmdp.entity.AgentConversation;
import com.hmdp.entity.AgentMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * <p>
 * Agent 对话服务：基于知识库做 RAG 检索，并流式生成带引用的回答
 * </p>
 */
public interface IAgentChatService extends IService<AgentConversation> {

    /**
     * 查询用户的会话列表
     */
    List<AgentConversation> listConversations(Long userId);

    /**
     * 新建会话
     */
    AgentConversation createConversation(Long userId, String title);

    /**
     * 查询会话内的消息（按时间正序）
     */
    List<AgentMessage> listMessages(Long userId, Long conversationId);

    /**
     * 删除会话及其消息
     */
    void deleteConversation(Long userId, Long conversationId);

    /**
     * 提问并由 agent 自主决定调用哪些工具后流式回答
     *
     * @param userId         当前用户
     * @param conversationId 会话id，为空则自动新建
     * @param question       用户问题
     * @param onToken        每生成一段文本回调一次
     * @param onTool         每次决定调用工具时回调一次，用于给前端显示「正在检索…」
     * @return 最终回答与引用来源
     */
    ChatResult streamAnswer(Long userId, Long conversationId, String question,
                            Consumer<String> onToken, Consumer<String> onTool);

    /**
     * 一次问答的结果
     */
    class ChatResult {
        private final Long conversationId;
        private final String answer;
        private final List<Citation> citations;

        public ChatResult(Long conversationId, String answer, List<Citation> citations) {
            this.conversationId = conversationId;
            this.answer = answer;
            this.citations = citations;
        }

        public Long getConversationId() {
            return conversationId;
        }

        public String getAnswer() {
            return answer;
        }

        public List<Citation> getCitations() {
            return citations;
        }
    }
}
