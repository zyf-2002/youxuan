package com.hmdp.dto;

import lombok.Data;

/**
 * <p>
 * Agent 对话请求
 * </p>
 */
@Data
public class AgentChatRequest {

    /**
     * 会话id，为空表示新建会话
     */
    private Long conversationId;

    /**
     * 用户问题
     */
    private String question;
}
