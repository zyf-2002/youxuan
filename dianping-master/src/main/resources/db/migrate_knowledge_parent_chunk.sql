-- ---------------------------------------------------------------------------
-- 知识库父块表：父子分块「分开存储」里的父块那一半
--
--   * 父块只存原文，不参与向量检索（没有向量字段）
--   * 叶子块仍然在 Milvus（dense + BM25 稀疏向量），只带 shop_id / parent_chunk_id
--   * 检索命中叶子后，靠 chunk_id 回这张表取回父块原文（auto-merge）
--
-- 注意：
--   1) 不要写 ROW_FORMAT = Compact。本库其它表是老 dump 带过来的 Compact，
--      那种格式下索引键最长 767 字节，chunk_id varchar(512) utf8mb4 的唯一索引
--      （2048 字节）会直接建不出来（错误 1071）。本机默认 row_format=dynamic，
--      不显式指定即可（键长上限 3072 字节）。
--   2) 身份只有 shop_id（数字）：不存 doc_id，也没有 "shop-" 前缀。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `tb_shop_knowledge_chunk` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `chunk_id` varchar(512) NOT NULL COMMENT '父块id，形如 1::l1::0',
  `shop_id` bigint unsigned NOT NULL COMMENT '店铺id（同时也是文档身份）',
  `title` varchar(2048) NULL DEFAULT NULL COMMENT '店铺名，作为证据标题',
  `text` mediumtext NOT NULL COMMENT '父块正文',
  `tenant_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '租户，与 Milvus 过滤条件一致',
  `chunk_level` tinyint NOT NULL DEFAULT 1 COMMENT '层级，父块固定 1',
  `chunk_idx` int NOT NULL DEFAULT 0 COMMENT '同层序号',
  `parent_chunk_id` varchar(512) NOT NULL DEFAULT '' COMMENT '父块的父块（根块为空）',
  `root_chunk_id` varchar(512) NOT NULL DEFAULT '' COMMENT '根块id',
  `content_hash` char(64) NOT NULL DEFAULT '' COMMENT '正文哈希',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_chunk_id`(`chunk_id`) USING BTREE,
  INDEX `idx_shop_id`(`shop_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '知识库父块（只存原文，不参与向量检索）';
