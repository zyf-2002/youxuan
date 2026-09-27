package com.hmdp.rag.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.ShopKnowledgeChunk;
import com.hmdp.mapper.ShopKnowledgeChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * 父块自动合并：同一父块下命中足够多的叶子块时，用父块整体替换这些叶子块。
 * </p>
 * <p>
 * 对应原 Python 侧的 auto_merge。分块只有两级，检索命中的都是叶子（L2），
 * 所以这里只需要向上合并一层：够数的父块从 MySQL 取回原文替换掉子块。
 * </p>
 */
@Slf4j
@Component
public class AutoMerger {

    @Resource
    private ShopKnowledgeChunkMapper shopKnowledgeChunkMapper;

    public List<RetrievedChunk> merge(List<RetrievedChunk> hits, int threshold, int leafLevel) {
        if (threshold <= 1 || hits == null || hits.isEmpty()) {
            return hits == null ? new ArrayList<>() : new ArrayList<>(hits);
        }

        List<RetrievedChunk> merged = new ArrayList<>(hits);

        for (int targetLevel = leafLevel; targetLevel > 1; targetLevel--) {
            // 按父块分组
            Map<String, List<RetrievedChunk>> grouping = new HashMap<>();
            for (RetrievedChunk hit : merged) {
                if (hit.getChunkLevel() == targetLevel
                        && hit.getChunkId() != null && !hit.getChunkId().isEmpty()
                        && hit.getParentChunkId() != null && !hit.getParentChunkId().isEmpty()) {
                    grouping.computeIfAbsent(hit.getParentChunkId(), k -> new ArrayList<>()).add(hit);
                }
            }

            // 找出命中数达标的父块
            Map<String, List<RetrievedChunk>> replaceable = new HashMap<>();
            for (Map.Entry<String, List<RetrievedChunk>> entry : grouping.entrySet()) {
                Set<String> distinct = new LinkedHashSet<>();
                for (RetrievedChunk child : entry.getValue()) {
                    distinct.add(child.getChunkId());
                }
                if (distinct.size() >= threshold) {
                    replaceable.put(entry.getKey(), entry.getValue());
                }
            }
            if (replaceable.isEmpty()) {
                continue;
            }

            Map<String, RetrievedChunk> parents =
                    fetchParents(new ArrayList<>(replaceable.keySet()));

            Set<String> replacedChildIds = new HashSet<>();
            List<RetrievedChunk> promoted = new ArrayList<>();
            for (Map.Entry<String, List<RetrievedChunk>> entry : replaceable.entrySet()) {
                RetrievedChunk parent = parents.get(entry.getKey());
                if (parent == null) {
                    continue;
                }
                double bestScore = Double.NEGATIVE_INFINITY;
                int bestRank = Integer.MAX_VALUE;
                for (RetrievedChunk child : entry.getValue()) {
                    replacedChildIds.add(child.getChunkId());
                    bestScore = Math.max(bestScore, child.getScore());
                    bestRank = Math.min(bestRank, child.getRank());
                }
                RetrievedChunk merged_ = new RetrievedChunk();
                merged_.setChunkId(parent.getChunkId());
                merged_.setShopId(parent.getShopId());
                merged_.setTitle(parent.getTitle());
                merged_.setText(parent.getText());
                merged_.setChunkLevel(parent.getChunkLevel());
                merged_.setParentChunkId(parent.getParentChunkId());
                merged_.setRootChunkId(parent.getRootChunkId());
                merged_.setContentHash(parent.getContentHash());
                merged_.setScore(bestScore == Double.NEGATIVE_INFINITY ? 0d : bestScore);
                merged_.setRank(bestRank == Integer.MAX_VALUE ? 0 : bestRank);
                promoted.add(merged_);
            }

            if (replacedChildIds.isEmpty()) {
                continue;
            }

            List<RetrievedChunk> next = new ArrayList<>();
            for (RetrievedChunk hit : merged) {
                if (!replacedChildIds.contains(hit.getChunkId())) {
                    next.add(hit);
                }
            }
            next.addAll(promoted);
            merged = next;
        }

        // 重新排序并重排名次
        merged.sort((a, b) -> {
            int byRank = Integer.compare(a.getRank(), b.getRank());
            return byRank != 0 ? byRank : Double.compare(b.getScore(), a.getScore());
        });
        for (int i = 0; i < merged.size(); i++) {
            merged.get(i).setRank(i + 1);
        }
        return merged;
    }

    /**
     * 按 chunk_id 批量取父块。父块只在 MySQL 里（Milvus 只存叶子和向量），
     * 取不到的父块会被跳过，调用方保留原来的叶子块。
     */
    private Map<String, RetrievedChunk> fetchParents(List<String> chunkIds) {
        Map<String, RetrievedChunk> parents = new HashMap<>();
        if (chunkIds.isEmpty()) {
            return parents;
        }
        List<ShopKnowledgeChunk> rows = shopKnowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<ShopKnowledgeChunk>()
                        .in(ShopKnowledgeChunk::getChunkId, chunkIds));
        for (ShopKnowledgeChunk row : rows) {
            RetrievedChunk parent = new RetrievedChunk();
            parent.setChunkId(row.getChunkId());
            parent.setShopId(row.getShopId());
            parent.setTitle(row.getTitle());
            parent.setText(row.getText());
            parent.setChunkLevel(row.getChunkLevel() == null ? 1 : row.getChunkLevel());
            parent.setParentChunkId(row.getParentChunkId());
            parent.setRootChunkId(row.getRootChunkId());
            parent.setContentHash(row.getContentHash());
            parents.put(row.getChunkId(), parent);
        }
        return parents;
    }
}
