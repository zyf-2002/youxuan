package com.hmdp.rag.retrieval;

/**
 * <p>
 * 一次检索的结论：给模型看的「这批材料到底够不够回答」。
 * </p>
 * <p>
 * 存在的意义是把 Grader 的判断**用起来**。以前评分算完只写进 trace（给人调试看的），
 * 没有传出去，工具也就无从告诉模型「这些片段其实答不了这个问题」，
 * 结果模型拿着不充分的材料硬编答案。
 * </p>
 */
public enum RetrievalStatus {

    /**
     * 检索到了相关证据，且足以回答问题
     */
    ANSWERABLE("足以回答"),

    /**
     * 检索到了相关内容，但信息不足以完整回答
     */
    INSUFFICIENT("找到了相关内容，但不足以回答"),

    /**
     * 检索正常，但知识库里确实没有相关内容
     */
    NO_KNOWLEDGE("没有检索到相关内容"),

    /**
     * 本次没做相关性评分（模型未配置、评分失败，或走的是不含 LLM 的单次检索），
     * 已保留全部召回结果，不敢对「够不够」下结论
     */
    GRADE_SKIPPED("未做相关性评分，保留全部召回结果");

    private final String label;

    RetrievalStatus(String label) {
        this.label = label;
    }

    /**
     * 拼进工具返回值里给模型看的中文说明
     */
    public String getLabel() {
        return label;
    }
}
