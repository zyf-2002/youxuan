package com.hmdp.rag.indexing;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.ShopKnowledgeChunk;
import com.hmdp.mapper.ShopKnowledgeChunkMapper;
import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.embedding.BgeM3EmbeddingService;
import com.hmdp.rag.store.MilvusVectorStore;
import com.hmdp.utils.TextCleaner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 文档入库编排：清洗 → 两级分块 → 父块写 MySQL、叶子块向量化写 Milvus。
 * </p>
 * <p>
 * 对应原 Python 侧的 backend/indexing/writer.py。
 * </p>
 */
@Slf4j
@Component
public class KnowledgeIndexer {

    @Resource
    private RagProperties properties;

    @Resource
    private TextChunker textChunker;

    @Resource
    private BgeM3EmbeddingService embeddingService;

    @Resource
    private MilvusVectorStore vectorStore;

    @Resource
    private ShopKnowledgeChunkMapper shopKnowledgeChunkMapper;

    /**
     * 把一家店铺的博客写进知识库；同 shopId 会被覆盖，天然幂等。
     * 父块先删后插（MySQL），叶子块整体覆盖（Milvus），最后才由调用方更新 content_hash，
     * 中途失败下一次扫描会重做。
     *
     * @param shopId 店铺id，同时也是文档身份
     * @param title  店铺名
     * @param text   正文（博客原文）
     */
    public IngestResult index(Long shopId, String title, String text) {
        String cleaned = TextCleaner.cleanBlogContent(text);
        if (StrUtil.isBlank(cleaned)) {
            throw new IllegalArgumentException("清洗后没有可用文本");
        }
        if (cleaned.length() > properties.getMaxTextCharacters()) {
            cleaned = cleaned.substring(0, properties.getMaxTextCharacters());
        }

        List<Chunk> chunks = textChunker.split(cleaned, shopId);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("分块后没有可用内容");
        }

        List<Chunk> parents = new ArrayList<>();
        List<Chunk> leaves = new ArrayList<>();
        for (Chunk chunk : chunks) {
            if (chunk.getLevel() == 1) {
                parents.add(chunk);
            } else {
                leaves.add(chunk);
            }
        }

        // 只有叶子块需要向量
        List<float[]> vectors = embeddingService.embedAll(
                leaves.stream().map(Chunk::getText).collect(java.util.stream.Collectors.toList()));

        String safeTitle = StrUtil.blankToDefault(title, String.valueOf(shopId));
        replaceParents(shopId, safeTitle, parents);
        int written = vectorStore.replaceDocument(
                shopId, safeTitle, properties.getTenantId(), leaves, vectors);

        IngestResult result = new IngestResult();
        result.setShopId(shopId);
        result.setTitle(safeTitle);
        result.setChunks(parents.size() + written);
        result.setCharacters(cleaned.length());
        for (Chunk chunk : chunks) {
            result.getLevels().merge(chunk.getLevel(), 1, Integer::sum);
        }

        log.info("店铺 {} 入库完成：父块 {} 叶子 {}，等级分布 {}",
                shopId, parents.size(), written, result.getLevels());
        return result;
    }

    /**
     * 从知识库删除一家店铺（父块 + 叶子块）
     */
    public int delete(Long shopId) {
        int parents = shopKnowledgeChunkMapper.delete(new LambdaQueryWrapper<ShopKnowledgeChunk>()
                .eq(ShopKnowledgeChunk::getShopId, shopId));
        int leaves = vectorStore.deleteDocument(shopId);
        log.info("店铺 {} 已从知识库删除，父块 {} 叶子 {}", shopId, parents, leaves);
        return parents + leaves;
    }

    /**
     * 覆盖写父块：先按 shopId 删干净再插，避免残留旧块
     */
    private void replaceParents(Long shopId, String title, List<Chunk> parents) {
        shopKnowledgeChunkMapper.delete(new LambdaQueryWrapper<ShopKnowledgeChunk>()
                .eq(ShopKnowledgeChunk::getShopId, shopId));
        for (Chunk chunk : parents) {
            shopKnowledgeChunkMapper.insert(new ShopKnowledgeChunk()
                    .setChunkId(chunk.getChunkId())
                    .setShopId(shopId)
                    .setTitle(title)
                    .setText(chunk.getText())
                    .setTenantId(properties.getTenantId())
                    .setChunkLevel(chunk.getLevel())
                    .setChunkIdx(chunk.getIndex())
                    .setParentChunkId(StrUtil.nullToEmpty(chunk.getParentChunkId()))
                    .setRootChunkId(StrUtil.nullToEmpty(chunk.getRootChunkId()))
                    .setContentHash(StrUtil.nullToEmpty(chunk.getContentHash())));
        }
    }
}
