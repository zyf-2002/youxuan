package com.hmdp.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <p>
 * RAG 知识库配置（全 Java 版，不再依赖 Python 服务）
 * </p>
 * <p>
 * 对应原来 Python 侧 backend/config.py 里的 MilvusSettings / EmbeddingSettings /
 * RetrievalSettings / RerankSettings，合并成一份。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.rag")
public class RagProperties {

    /**
     * 是否启用知识库功能
     */
    private boolean enabled = true;

    /**
     * bge-m3 ONNX 模型目录（里面应有 onnx/model.onnx 与 onnx/tokenizer.json）
     */
    private String modelDir = "/home/zyf/springboot/hmdp/.tools/models/bge-m3-onnx";

    /**
     * 启动时预热向量模型，避免第一个请求卡住
     */
    private boolean warmupOnStart = true;

    /**
     * Milvus 连接地址
     */
    private String milvusUri = "http://127.0.0.1:19530";

    /**
     * 单次 Milvus RPC 的超时（毫秒）。
     * 不设的话 Milvus SDK 在连接断开时会无限重试，导致用户请求一直挂起。
     */
    private long rpcDeadlineMs = 8000;

    /**
     * 建连超时（毫秒）
     */
    private long connectTimeoutMs = 5000;

    /**
     * 集合名。
     * <p>
     * schema 改过就得换名字（{@code ensureCollection} 只判断集合存不存在、不校验 schema），
     * 老集合留着可以回滚；v2 起是「dense + BM25 稀疏」的混合检索版本。
     * </p>
     */
    private String collection = "hmdp_knowledge_v2";

    /**
     * 多租户隔离字段（当前固定 default）
     */
    private String tenantId = "default";

    /**
     * 向量维度，bge-m3 固定 1024
     */
    private int dimension = 1024;

    /**
     * 单条文本向量化时的最大 token 数。
     * bge-m3 本身支持 8192，但叶子块最大 800 汉字约 650 token，
     * 原来写死 512 会把超长块的尾部截掉（向量里等于不存在）。
     */
    private int maxTokens = 1024;

    /**
     * 单篇文档清洗后的最大字符数
     */
    private int maxTextCharacters = 32768;

    /**
     * 两级分块：父块（L1）尺寸与重叠。
     * hmdp 的博客正文上限是 2048 字，原来照搬 SuperMew 的 2400/1600
     * 比整篇还大，导致 L1/L2 永远只切出 1 块、形同虚设。
     */
    private int parentSize = 1200;
    private int parentOverlap = 100;

    /**
     * 两级分块：叶子块（L2）尺寸与重叠，检索只在这一层做
     */
    private int leafSize = 400;
    private int leafOverlap = 50;

    /**
     * 叶子分块层级；两级分块固定为 2
     */
    private int leafLevel = 2;

    /**
     * 召回候选数
     */
    private int candidateK = 20;

    /**
     * 是否用「稠密 + BM25 稀疏」混合召回。
     * 关掉或 Milvus 不支持稀疏字段时退化成纯向量召回。
     */
    private boolean hybridEnabled = true;

    /**
     * RRF 融合的平滑常数 k，越大越压低单路高排名的影响
     */
    private int hybridRrfK = 60;

    /**
     * 查询时 BM25 稀疏向量的丢弃比例，两路召回各自取 candidateK*2 条再融合
     */
    private double hybridDropRatioSearch = 0.2;

    /**
     * 最终送给模型的证据条数
     */
    private int topK = 5;

    /**
     * 是否启用父块自动合并
     */
    private boolean autoMerge = true;

    /**
     * 同一父块下命中多少个叶子块就提升为父块
     */
    private int autoMergeThreshold = 2;

    /**
     * 证据文本的字符预算
     */
    private int evidenceBudget = 6000;

    /**
     * 是否启用 LLM 检索管线（问题分类 / 子问题 / 相关性评分 / 重写）。
     * 关掉就退化成一次向量召回。
     */
    private boolean usePipeline = true;

    /**
     * 相关性不足时最多重写几次问题
     */
    private int maxRewrites = 1;

    /**
     * 向量化批量写入大小
     */
    private int writeBatchSize = 32;

    private Rerank rerank = new Rerank();

    @Data
    public static class Rerank {
        /**
         * 外部精排服务地址，留空表示不启用
         */
        private boolean enabled = false;
        private String bindingHost = "";
        private String apiKey = "";
        private String model = "";
        private double minScore = 0.0;
        private int timeoutMs = 8000;
    }
}
