package com.hmdp.rag.indexing;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>
 * 入库结果
 * </p>
 */
@Data
public class IngestResult {

    private Long shopId;

    private String title;

    /**
     * 分块总数（父块 + 叶子块）
     */
    private int chunks;

    /**
     * 各层级分块数，如 {1=1, 2=4}
     */
    private Map<Integer, Integer> levels = new LinkedHashMap<>();

    /**
     * 清洗后的字符数
     */
    private int characters;
}
