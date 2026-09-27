-- ---------------------------------------------------------------------------
-- 知识库与 Agent 对话相关表
--
-- 与 hmdp.sql 中的表互不影响，可重复执行。
-- 知识库以「店铺」为索引对象，店铺文案与知识库正文见 shop_profile.sql、shop_knowledge.sql。
-- ---------------------------------------------------------------------------

-- Agent 会话
CREATE TABLE IF NOT EXISTS `tb_agent_conversation` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '新对话' COMMENT '会话标题',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_update`(`user_id`, `update_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 会话' ROW_FORMAT = Compact;

-- Agent 对话消息
CREATE TABLE IF NOT EXISTS `tb_agent_message` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` bigint(20) UNSIGNED NOT NULL COMMENT '会话id',
  `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'user 或 assistant',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '消息内容',
  `citations` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '引用来源 JSON',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_conversation`(`conversation_id`, `id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 对话消息' ROW_FORMAT = Compact;
