package com.hmdp.rag.retrieval;

import lombok.Data;

/**
 * <p>
 * 一条引用来源，编号与证据文本里的 [n] 对应。
 * </p>
 */
@Data
public class Citation {

    private int index;

    private Long shopId;

    private String title;

    private String chunkId;

    private int chunkLevel;

    /**
     * 含义随检索模式变化，见 {@link RetrievedChunk#getScore()}；对外真正可读的是 {@link #rerankScore}
     */
    private double score;

    private Double rerankScore;

    private int rank;

    private String snippet;

    private String contentHash;
}
