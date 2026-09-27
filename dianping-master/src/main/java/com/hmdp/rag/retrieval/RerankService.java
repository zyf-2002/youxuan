package com.hmdp.rag.retrieval;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 可选精排，对应原 Python 侧的 RerankStage。
 * </p>
 * <p>
 * 失败时**保留召回顺序**并把稳定的错误码回传出去，不静默伪装成功。
 * </p>
 */
@Slf4j
@Component
public class RerankService {

    @Resource
    private RagProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 精排结果
     */
    public static class Outcome {
        private final List<RetrievedChunk> hits;
        private final String error;

        public Outcome(List<RetrievedChunk> hits, String error) {
            this.hits = hits;
            this.error = error;
        }

        public List<RetrievedChunk> getHits() {
            return hits;
        }

        public String getError() {
            return error;
        }
    }

    public boolean enabled() {
        return properties.getRerank().isEnabled()
                && StrUtil.isNotBlank(properties.getRerank().getBindingHost());
    }

    public Outcome apply(String query, List<RetrievedChunk> hits) {
        if (!enabled() || hits == null || hits.isEmpty()) {
            return new Outcome(hits, null);
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("query", query);
            if (StrUtil.isNotBlank(properties.getRerank().getModel())) {
                payload.put("model", properties.getRerank().getModel());
            }
            ArrayNode documents = payload.putArray("documents");
            for (RetrievedChunk hit : hits) {
                documents.add(StrUtil.blankToDefault(hit.getText(), ""));
            }
            payload.put("top_n", hits.size());

            HttpRequest request = HttpRequest.post(properties.getRerank().getBindingHost())
                    .header("Content-Type", "application/json")
                    .body(payload.toString())
                    .timeout(properties.getRerank().getTimeoutMs());
            if (StrUtil.isNotBlank(properties.getRerank().getApiKey())) {
                request.header("Authorization", "Bearer " + properties.getRerank().getApiKey());
            }

            HttpResponse response = request.execute();
            if (!response.isOk()) {
                log.warn("精排服务返回 HTTP {}，保留召回顺序", response.getStatus());
                return new Outcome(hits, "RERANK_FAILED:HTTP_" + response.getStatus());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.has("results") ? root.get("results") : root.get("data");
            if (results == null || !results.isArray() || results.size() == 0) {
                log.warn("精排服务没有返回结果，保留召回顺序");
                return new Outcome(hits, "RERANK_EMPTY");
            }

            List<RetrievedChunk> ordered = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= hits.size()) {
                    continue;
                }
                double score = item.has("relevance_score")
                        ? item.get("relevance_score").asDouble()
                        : item.path("score").asDouble();
                if (score < properties.getRerank().getMinScore()) {
                    continue;
                }
                RetrievedChunk enriched = copy(hits.get(index));
                enriched.setRerankScore(score);
                enriched.setRank(ordered.size() + 1);
                ordered.add(enriched);
            }

            // 没被精排选中的补在后面，避免丢内容
            for (RetrievedChunk hit : hits) {
                boolean kept = false;
                for (RetrievedChunk picked : ordered) {
                    if (picked.getChunkId() != null && picked.getChunkId().equals(hit.getChunkId())) {
                        kept = true;
                        break;
                    }
                }
                if (!kept) {
                    RetrievedChunk tail = copy(hit);
                    tail.setRerankScore(null);
                    ordered.add(tail);
                }
            }

            if (ordered.isEmpty()) {
                return new Outcome(hits, "RERANK_EMPTY");
            }
            return new Outcome(ordered, null);
        } catch (Exception e) {
            log.warn("精排失败，保留召回顺序：{}", e.getMessage());
            return new Outcome(hits, "RERANK_FAILED:" + e.getClass().getSimpleName());
        }
    }

    private RetrievedChunk copy(RetrievedChunk source) {
        RetrievedChunk target = new RetrievedChunk();
        target.setChunkId(source.getChunkId());
        target.setShopId(source.getShopId());
        target.setTitle(source.getTitle());
        target.setText(source.getText());
        target.setChunkLevel(source.getChunkLevel());
        target.setParentChunkId(source.getParentChunkId());
        target.setRootChunkId(source.getRootChunkId());
        target.setContentHash(source.getContentHash());
        target.setScore(source.getScore());
        target.setRank(source.getRank());
        target.setRerankScore(source.getRerankScore());
        return target;
    }
}
