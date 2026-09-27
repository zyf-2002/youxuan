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
 * 知识库父块：父子分块里"父"的那一半，只存原文，不参与向量检索。
 * </p>
 * <p>
 * 分块是两级：父块（{@code parentSize}=1200）和叶子块（{@code leafSize}=400）。
 * 叶子块连同向量写在 Milvus 里负责召回；父块写在这张表里，召回命中同一父块下足够多的
 * 叶子后，按 {@code chunk_id} 把它取回来替换叶子，给模型更完整的上下文。
 * </p>
 * <p>
 * 之所以不放在 Milvus：父块不需要向量（白算），而且在 Milvus 里会把 BM25 的
 * IDF/avgdl 统计一起污染掉。这里只按 {@code chunk_id} 批量点查，关系库最合适。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop_knowledge_chunk")
public class ShopKnowledgeChunk implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 父块id，形如 {@code 1::l1::0}
     */
    private String chunkId;
    /**
     * 店铺id。它同时也是这份文档的身份（不再有 "shop-" 前缀的 docId）
     */
    private Long shopId;
    /**
     * 店铺名，作为证据的标题
     */
    private String title;
    /**
     * 父块正文
     */
    private String text;
    /**
     * 租户，和 Milvus 里的过滤条件保持一致
     */
    private String tenantId;
    /**
     * 层级，父块固定 1
     */
    private Integer chunkLevel;
    /**
     * 同层内的序号
     */
    private Integer chunkIdx;
    /**
     * 父块的父块，根块为空串
     */
    private String parentChunkId;
    /**
     * 根块id
     */
    private String rootChunkId;
    /**
     * 正文哈希
     */
    private String contentHash;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
