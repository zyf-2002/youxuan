package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.AgentProperties;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopKnowledge;
import com.hmdp.mapper.ShopKnowledgeMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.rag.indexing.IngestResult;
import com.hmdp.rag.indexing.KnowledgeIndexer;
import com.hmdp.service.IShopKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 店铺知识库服务实现：一家店铺 = 一份知识库文档
 * </p>
 * <p>
 * 知识库的素材是 {@code tb_shop_knowledge.content} 里那一段写好的长文，
 * 入库过程没有任何智能成分：分块 → 本地 bge-m3 向量化 → 写 Milvus。
 * 不调用大模型，也就没有失败重试、没有指纹之外的中间状态。
 * </p>
 * <p>
 * 是否要重新入库由 {@code content_hash} 决定：它记录的是「上一次成功入库时的正文指纹」，
 * 和当前正文对不上就重做。失败时不更新指纹，所以下一轮扫描会自动重试。
 * </p>
 * <p>
 * 因此要全量重建，把 {@code content_hash} 清空即可，下一轮扫描会把所有店铺重做一遍：
 * {@code UPDATE tb_shop_knowledge SET content_hash = NULL;}
 * </p>
 */
@Slf4j
@Service
public class ShopKnowledgeServiceImpl
        extends ServiceImpl<ShopKnowledgeMapper, ShopKnowledge>
        implements IShopKnowledgeService {

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private KnowledgeIndexer knowledgeIndexer;

    @Resource
    private AgentProperties properties;

    /**
     * 定时检查正文有没有变化，变了就重新入库。
     * <p>
     * 正文是静态内容，绝大多数轮次只是比对一下 md5 就结束了。
     * {@code fixedDelay} 保证上一轮跑完才会开始下一轮。
     * </p>
     */
    @Scheduled(
            fixedDelayString = "${hmdp.agent.scan-interval-ms:15000}",
            initialDelayString = "${hmdp.agent.scan-initial-delay-ms:20000}")
    public void indexChangedShops() {
        if (!properties.isEnabled()) {
            return;
        }
        List<ShopKnowledge> rows = list();
        int indexed = 0;
        for (ShopKnowledge row : rows) {
            if (StrUtil.isBlank(row.getContent())) {
                continue;
            }
            if (StrUtil.equals(hash(row.getContent()), row.getContentHash())) {
                continue;
            }
            try {
                index(row);
                indexed++;
            } catch (Exception e) {
                // 指纹没有更新，下一轮会自动重试
                log.error("店铺 {} 知识入库失败：{}", row.getShopId(), e.getMessage());
            }
        }
        if (indexed > 0) {
            log.info("本轮知识库入库完成，共 {} 家", indexed);
        }
    }

    /**
     * 单条正文入库：分块 → 向量化 → 父块写 MySQL、叶子写 Milvus → 记录指纹与分块数
     */
    private void index(ShopKnowledge knowledge) {
        Long shopId = knowledge.getShopId();
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            log.warn("店铺 {} 不存在，跳过知识库入库", shopId);
            return;
        }

        IngestResult response = knowledgeIndexer.index(
                shopId,
                StrUtil.blankToDefault(shop.getName(), "店铺" + shopId),
                knowledge.getContent());

        knowledge.setContentHash(hash(knowledge.getContent()))
                .setChunkCount(response == null ? null : response.getChunks())
                .setIndexedAt(LocalDateTime.now());
        updateById(knowledge);

        log.info("店铺 {}（{}）知识入库完成，分块数={}", shopId,
                StrUtil.blankToDefault(shop.getName(), ""),
                response == null ? null : response.getChunks());
    }

    private String hash(String content) {
        return DigestUtil.md5Hex(StrUtil.blankToDefault(content, ""));
    }
}
