package com.hmdp.rag.retrieval;

import cn.hutool.core.util.StrUtil;
import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.embedding.BgeM3EmbeddingService;
import com.hmdp.rag.store.MilvusVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 检索服务：向量化问题 → 向量检索 → 父块合并 → 可选精排 → 证据打包。
 * </p>
 * <p>
 * 对应原 Python 侧的 rag/retrieval.py + utils.py 的 retrieve_documents，
 * 但不含 LLM 编排（那部分在 {@link KnowledgeSearchPipeline}）。
 * </p>
 */
@Slf4j
@Component
public class RetrievalService {

    /**
     * 纯向量召回
     */
    public static final String MODE_DENSE = "dense";

    /**
     * 稠密 + BM25 稀疏混合召回
     */
    public static final String MODE_HYBRID = "hybrid";

    @Resource
    private RagProperties properties;

    @Resource
    private BgeM3EmbeddingService embeddingService;

    @Resource
    private MilvusVectorStore vectorStore;

    @Resource
    private AutoMerger autoMerger;

    @Resource
    private RerankService rerankService;

    @Resource
    private EvidencePacker evidencePacker;

    /**
     * 召回并整理候选：召回 + 父块自动合并
     */
    public List<RetrievedChunk> search(String question) {
        return recall(question, properties.getCandidateK()).hits();
    }

    /**
     * 召回并带回本次实际走的模式。
     * <p>
     * 优先稠密 + BM25 稀疏的混合召回；失败（例如集合没有稀疏字段）时当次退化成纯向量召回，
     * 不让一次 RPC 失败把整个问答打挂。
     * </p>
     */
    public Recall recall(String question) {
        return recall(question, properties.getCandidateK());
    }

    public Recall recall(String question, int candidateK) {
        float[] vector = embeddingService.embed(question);

        String mode = MODE_DENSE;
        List<RetrievedChunk> hits;
        if (properties.isHybridEnabled()) {
            try {
                hits = vectorStore.hybridSearch(question, vector, candidateK,
                        properties.getTenantId(), properties.getLeafLevel());
                mode = MODE_HYBRID;
            } catch (RuntimeException e) {
                log.warn("混合检索失败，本次退化为纯向量召回：{}", e.getMessage());
                hits = vectorStore.search(vector, candidateK,
                        properties.getTenantId(), properties.getLeafLevel());
            }
        } else {
            hits = vectorStore.search(vector, candidateK,
                    properties.getTenantId(), properties.getLeafLevel());
        }

        if (properties.isAutoMerge()) {
            hits = autoMerger.merge(hits, properties.getAutoMergeThreshold(),
                    properties.getLeafLevel());
        }
        return new Recall(hits, mode);
    }

    /**
     * 一次召回的结果：命中的分块 + 实际走的召回模式（hybrid / dense）
     */
    public record Recall(List<RetrievedChunk> hits, String mode) {
    }

    /**
     * 单次检索（不含 LLM 管线）：召回 → 精排 → 截断 → 证据打包
     */
    public RetrievalResult singlePass(String question) {
        long start = System.currentTimeMillis();
        String normalized = normalize(question);

        Recall recall = recall(normalized);
        List<RetrievedChunk> hits = recall.hits();
        int candidateCount = hits.size();

        boolean rerankApplied = false;
        String rerankError = null;
        if (rerankService.enabled()) {
            RerankService.Outcome outcome = rerankService.apply(normalized, hits);
            hits = outcome.getHits();
            rerankError = outcome.getError();
            rerankApplied = rerankError == null;
        }

        hits = topK(hits);

        RetrievalResult result = new RetrievalResult();
        result.setQuery(normalized);
        result.setRoute("single_pass");
        result.setRetrievalMode(recall.mode());
        result.setGradeSkipped(true);
        // 这条路不含 LLM 评分，所以不对「够不够」下结论；
        // 但「一条都没召回」是确定的事实，可以明确告诉模型
        result.setRetrievalStatus(hits.isEmpty()
                ? RetrievalStatus.NO_KNOWLEDGE : RetrievalStatus.GRADE_SKIPPED);
        result.setRerankApplied(rerankApplied);
        result.setRerankError(rerankError);
        result.setCandidateCount(hits.size());
        applyEvidence(result, hits);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("mode", "single_pass");
        trace.put("candidates", candidateCount);
        trace.put("elapsedMs", System.currentTimeMillis() - start);
        result.setTrace(trace);
        return result;
    }

    /**
     * 把结果集截断到 topK 并打包成证据
     */
    public void applyEvidence(RetrievalResult result, List<RetrievedChunk> hits) {
        List<RetrievedChunk> limited = topK(hits);
        EvidencePacker.Packed packed = evidencePacker.pack(
                limited, properties.getEvidenceBudget());
        result.setEvidence(packed.getEvidence());
        result.setCitations(packed.getCitations());
        result.setCandidateCount(limited.size());
    }

    public List<RetrievedChunk> topK(List<RetrievedChunk> hits) {
        int topK = Math.max(1, properties.getTopK());
        if (hits == null) {
            return new ArrayList<>();
        }
        return hits.size() <= topK ? new ArrayList<>(hits) : new ArrayList<>(hits.subList(0, topK));
    }

    public static String normalize(String question) {
        return StrUtil.blankToDefault(question, "").trim().replaceAll("\\s+", " ");
    }
}
