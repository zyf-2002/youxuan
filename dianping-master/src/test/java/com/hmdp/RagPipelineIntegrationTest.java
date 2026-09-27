package com.hmdp;

import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.indexing.IngestResult;
import com.hmdp.rag.indexing.KnowledgeIndexer;
import com.hmdp.rag.indexing.TextChunker;
import com.hmdp.rag.indexing.Chunk;
import com.hmdp.rag.retrieval.Citation;
import com.hmdp.rag.retrieval.KnowledgeSearchPipeline;
import com.hmdp.rag.retrieval.RetrievalResult;
import com.hmdp.rag.store.MilvusVectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>
 * 全 Java RAG 链路的集成测试：分块 → 向量化 → 写入 Milvus → 检索 → 证据打包。
 * </p>
 * <p>
 * 会启动完整的 Spring 容器（需要 MySQL / Redis / RabbitMQ / Milvus 在运行），
 * 并真实调用一次大模型（用于检索管线的分类/评分），没有配 Key 时会自动降级。
 * </p>
 */
@SpringBootTest
public class RagPipelineIntegrationTest {

    /**
     * 测试用的假店铺id，取一个远离真实店铺的段，避免污染线上数据
     */
    private static final long SHOP_A = 900001L;
    private static final long SHOP_B = 900002L;
    private static final long SHOP_C = 900003L;

    @Resource
    private TextChunker textChunker;

    @Resource
    private KnowledgeIndexer knowledgeIndexer;

    @Resource
    private KnowledgeSearchPipeline knowledgeSearchPipeline;

    @Resource
    private MilvusVectorStore vectorStore;

    @Resource
    private RagProperties properties;


    @AfterEach
    public void cleanup() {
        for (long shopId : new long[]{SHOP_A, SHOP_B, SHOP_C}) {
            try {
                knowledgeIndexer.delete(shopId);
            } catch (Exception ignored) {
                // 清理失败不影响断言结果
            }
        }
    }

    /**
     * 分块器应当切出两级父子结构
     */
    @Test
    public void chunkerProducesTwoLevels() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("这是第").append(i).append("段测试文本，用来撑大内容以便切出多个分块。")
                    .append("这里是杭州一家很好吃的店，人均消费大约五十元。")
                    .append("招牌菜有东坡肉、龙井虾仁、宋嫂鱼羹，味道都很地道。\n\n");
        }

        List<Chunk> chunks = textChunker.split(text.toString(), SHOP_A);
        assertFalse(chunks.isEmpty(), "应该切出分块");

        long level1 = chunks.stream().filter(c -> c.getLevel() == 1).count();
        long level2 = chunks.stream().filter(c -> c.getLevel() == 2).count();

        System.out.printf("分块结果：L1=%d L2=%d%n", level1, level2);
        assertTrue(level1 >= 1, "至少有一个 L1");
        assertTrue(level2 >= level1, "叶子块不少于父块");
        // 两级分块下不应再出现 L3
        assertEquals(0, chunks.stream().filter(c -> c.getLevel() == 3).count(),
                "两级分块不应产生 L3");
        // 分块id就是纯数字店铺id，没有 "shop-" 之类的文档前缀
        assertEquals("900001::l1::0", chunks.get(0).getChunkId(), "父块id应是 shopId::l1::序号");

        // 父子指针要连得上
        for (Chunk chunk : chunks) {
            if (chunk.getLevel() == 2) {
                assertFalse(chunk.getParentChunkId().isEmpty(), "叶子块必须有父块");
                assertEquals(chunk.getRootChunkId(), chunk.getParentChunkId(),
                        "两级分块下叶子块的父块就是根块");
            }
        }
    }

    /**
     * 入库 + 检索的完整链路
     */
    @Test
    public void ingestThenRetrieve() {
        IngestResult a = knowledgeIndexer.index(SHOP_A, "中山北路的杭帮菜小馆",
                "藏在巷子里的杭帮菜小馆，人均60元，招牌是东坡肉和龙井虾仁，宋嫂鱼羹酸酸的也很开胃。"
                        + "地址在杭州市下城区中山北路188号，避开周末晚上饭点，停车不太方便。");
        IngestResult b = knowledgeIndexer.index(SHOP_B, "杭州平价的港式茶餐厅",
                "又吃到一家好吃的港式茶餐厅，怀旧tvb港风，人均30元，招牌黯然销魂饭和漏奶华，"
                        + "还有神仙一口西多士和怀旧五柳炸蛋饭，都很平价。");
        IngestResult c = knowledgeIndexer.index(SHOP_C, "浪漫的花园西餐厅",
                "小筑里神秘浪漫花园西餐厅，到处都是花，战斧牛排外焦里嫩，奶油培根意面也很好吃，"
                        + "环境很有格调，适合约会。");

        System.out.printf("入库：A=%d块 %s | B=%d块 %s | C=%d块 %s%n",
                a.getChunks(), a.getLevels(), b.getChunks(), b.getLevels(),
                c.getChunks(), c.getLevels());
        assertTrue(a.getChunks() > 0 && b.getChunks() > 0 && c.getChunks() > 0);

        // 删除不存在的店铺应当返回 0，而不是误删到别的内容
        assertEquals(0, vectorStore.deleteDocument(900004L), "删除不存在的店铺应返回 0");

        RetrievalResult result = knowledgeSearchPipeline.retrieve("杭州平价的港式茶餐厅推荐？");

        System.out.println("检索 route=" + result.getRoute()
                + " mode=" + result.getRetrievalMode()
                + " 候选=" + result.getCandidateCount()
                + " 引用=" + result.getCitations().size());
        for (Citation citation : result.getCitations()) {
            System.out.printf("  #%d shop=%d score=%.4f level=%d %s%n",
                    citation.getIndex(), citation.getShopId(), citation.getScore(),
                    citation.getChunkLevel(), citation.getTitle());
        }

        assertFalse(result.getCitations().isEmpty(), "应该检索到内容");
        assertNotNull(result.getEvidence());
        assertFalse(result.getEvidence().isEmpty(), "证据文本不能为空");
        assertTrue(result.getEvidence().startsWith("[1]"), "证据应以 [1] 开头");

        // 茶餐厅那篇必须命中
        boolean hitTeaRestaurant = result.getCitations().stream()
                .anyMatch(x -> Long.valueOf(SHOP_B).equals(x.getShopId()));
        assertTrue(hitTeaRestaurant, "应该命中港式茶餐厅那条，实际：" + shopIds(result));

        // 证据编号与引用编号一致
        for (int i = 0; i < result.getCitations().size(); i++) {
            assertEquals(i + 1, result.getCitations().get(i).getIndex(), "引用编号应连续");
        }
    }

    private static String shopIds(RetrievalResult result) {
        StringBuilder builder = new StringBuilder();
        for (Citation citation : result.getCitations()) {
            builder.append(citation.getShopId()).append(' ');
        }
        return builder.toString();
    }

    /**
     * 配置能读到
     */
    @Test
    public void propertiesLoaded() {
        assertTrue(properties.isEnabled());
        assertEquals(1024, properties.getDimension());
        assertEquals(2, properties.getLeafLevel());
        System.out.println("集合=" + properties.getCollection()
                + " 模型目录=" + properties.getModelDir());
    }
}
