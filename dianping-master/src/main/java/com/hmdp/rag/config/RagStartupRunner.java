package com.hmdp.rag.config;

import com.hmdp.rag.embedding.BgeM3EmbeddingService;
import com.hmdp.rag.store.MilvusVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * <p>
 * RAG 启动初始化：确保 Milvus 集合存在，并按需预热向量模型。
 * </p>
 * <p>
 * 之所以放在启动阶段：bge-m3 首次装载要几秒到几十秒，不预热的话第一个用户请求会莫名卡住。
 * </p>
 */
@Slf4j
@Component
public class RagStartupRunner implements ApplicationRunner {

    @Resource
    private RagProperties properties;

    @Resource
    private MilvusVectorStore vectorStore;

    @Resource
    private BgeM3EmbeddingService embeddingService;

    /**
     * 启动时最多重试几次（每次间隔 10 秒，约 2 分钟）
     */
    private static final int maxAttempts = 12;
    private static final long retryIntervalMs = 10_000L;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("RAG 知识库功能已关闭（hmdp.rag.enabled=false）");
            return;
        }

        // Milvus 端口打开不等于 Proxy 就绪，冷启动时可能要等一两分钟，这里退避重试
        boolean ready = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                vectorStore.client();
                log.info("Milvus 集合就绪：{}（第 {} 次尝试）", vectorStore.collectionName(), attempt);
                ready = true;
                break;
            } catch (Exception e) {
                log.warn("连接 Milvus 失败（第 {}/{} 次）：{}", attempt, maxAttempts, e.getMessage());
                vectorStore.resetClient();
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(retryIntervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (!ready) {
            log.error("Milvus 始终不可用，知识库检索与入库暂不可用；"
                    + "等 Milvus 恢复后首次调用会自动重连");
            return;
        }

        if (properties.isWarmupOnStart()) {
            try {
                embeddingService.warmup();
            } catch (Exception e) {
                log.error("向量模型预热失败，首次检索会变慢：{}", e.getMessage());
            }
        }
    }
}
