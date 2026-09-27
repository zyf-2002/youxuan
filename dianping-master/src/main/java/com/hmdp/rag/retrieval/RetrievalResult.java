package com.hmdp.rag.retrieval;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 一次检索的完整结果。
 * </p>
 * <p>
 * 对应原 Python 侧 /rag/retrieve 的响应结构，字段名保持兼容，
 * 这样存进 tb_agent_message.citations 的 JSON 前端不用改。
 * </p>
 */
@Data
public class RetrievalResult {

    private String query;

    /**
     * simple / complex / skipped / single_pass
     */
    private String route = "simple";

    /**
     * 本次实际走的召回模式：hybrid（稠密 + BM25 稀疏）或 dense（纯向量）
     */
    private String retrievalMode = "dense";

    private int candidateCount;

    private boolean rerankApplied;

    private String rerankError;

    private List<String> subQuestions = new ArrayList<>();

    private int rewriteCount;

    private boolean gradeSkipped;

    /**
     * 本次检索的结论：材料够不够回答问题。
     * 由 Grader 的评分结果落成，工具据此决定怎么向模型交代，避免模型拿不充分的材料硬编。
     */
    private RetrievalStatus retrievalStatus = RetrievalStatus.NO_KNOWLEDGE;

    private Map<String, Object> trace = new LinkedHashMap<>();

    /**
     * 已编号的证据文本，直接拼进提示词
     */
    private String evidence = "";

    private List<Citation> citations = new ArrayList<>();
}
