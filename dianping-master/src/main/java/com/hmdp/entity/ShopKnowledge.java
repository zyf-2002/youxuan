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
 * 店铺知识库正文：一家店铺一条，{@code content} 就是进 RAG 知识库的原始素材。
 * </p>
 * <p>
 * 内容是在离线阶段写好的（有用户博客的店铺参考博客，没有的按店铺信息合理推测），
 * 入库过程只负责把它分块、向量化、写进 Milvus，不做任何再加工，也不调用大模型。
 * </p>
 * <p>
 * 前端展示用的短文案（介绍/特色/建议）在 {@link Shop} 上，和这张表分开：
 * 那边要分段好看，这边要一整段长文，分块才有意义。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop_knowledge")
public class ShopKnowledge implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 店铺id
     */
    private Long shopId;
    /**
     * 知识库正文，一整段连贯文字
     */
    private String content;
    /**
     * 上一次成功入库时的 content 指纹，和当前 content 对不上就说明该重新入库
     */
    private String contentHash;
    /**
     * 入库分块数
     */
    private Integer chunkCount;
    /**
     * 最近一次成功入库的时间
     */
    private LocalDateTime indexedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
