package com.hmdp.rag.retrieval;

import lombok.Data;

/**
 * <p>
 * 一条检索命中的分块。
 * </p>
 * <p>
 * 对应原 Python 侧 retrieve 返回的 hit 字典；{@code rank} 等价于原来的 rrf_rank，
 * 在纯向量检索里就是向量检索的名次。
 * </p>
 */
@Data
public class RetrievedChunk {

    private String chunkId;

    private Long shopId;

    private String title;

    private String text;

    /**
     * 1 父块 / 2 叶子块
     */
    private int chunkLevel;

    private String parentChunkId;

    private String rootChunkId;

    private String contentHash;

    /**
     * 排序分，两种检索模式的量纲不同：混合检索下是 RRF 融合分（Σ 1/(k+rank)，
     * 实测挤在 0.012~0.033，只有同一次请求内的相对意义），纯向量检索下才是余弦相似度（0~1）。
     * 要按绝对值读相关性请看 {@link #rerankScore}。
     */
    private double score;

    /**
     * 名次，从 1 开始
     */
    private int rank;

    /**
     * 精排分数，未启用精排时为 null
     */
    private Double rerankScore;
}
