package com.hmdp.rag.indexing;

import lombok.Data;

/**
 * <p>
 * 一条分块记录。两级分块，父块（level=1）写 MySQL，叶子块（level=2）写 Milvus。
 * </p>
 */
@Data
public class Chunk {

    /**
     * 全局唯一分块id，形如 26::l1::0 或 26::l2::0::1
     */
    private String chunkId;

    private String text;

    /**
     * 1 父块 / 2 叶子块，数字越小越粗
     */
    private int level;

    /**
     * 同层内的序号
     */
    private int index;

    /**
     * 上一层分块的id，L1 为空
     */
    private String parentChunkId;

    /**
     * 所属 L1 分块id
     */
    private String rootChunkId;

    /**
     * 文本的 SHA-256，用于幂等判断
     */
    private String contentHash;
}
