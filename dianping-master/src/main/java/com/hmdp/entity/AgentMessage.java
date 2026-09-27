package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * Agent 对话消息
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_agent_message")
public class AgentMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 会话id
     */
    private Long conversationId;
    /**
     * user 或 assistant
     */
    private String role;
    /**
     * 消息内容
     */
    private String content;
    /**
     * 引用来源 JSON
     */
    private String citations;

    private LocalDateTime createTime;
}
