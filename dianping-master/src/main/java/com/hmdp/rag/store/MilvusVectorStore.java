package com.hmdp.rag.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hmdp.rag.config.RagProperties;
import com.hmdp.rag.indexing.Chunk;
import com.hmdp.rag.retrieval.RetrievedChunk;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * Milvus 向量存储：建集合、写入、删除、检索。
 * </p>
 * <p>
 * <b>只有叶子块存在这里</b>（父块在 MySQL 的 {@code tb_shop_knowledge_chunk}），身份是
 * {@code shop_id}。检索有两条路：
 * </p>
 * <ul>
 *   <li>{@link #hybridSearch}：稠密向量 + Milvus 原生 BM25 稀疏向量，两路各取 2×k，
 *       再用 RRF 融合排名（默认入口）；</li>
 *   <li>{@link #search}：只走稠密向量（降级路径，或者想对比两种召回时用）。</li>
 * </ul>
 */
@Slf4j
@Component
public class MilvusVectorStore implements DisposableBean {

    private static final int QUERY_LIMIT = 16384;

    /**
     * 融合前每一路各自取多少候选（相对最终 limit 的倍数）
     */
    private static final int PER_PATH_MULTIPLIER = 2;

    @Resource
    private RagProperties properties;

    private final Gson gson = new Gson();

    private final Object lock = new Object();

    private volatile MilvusClientV2 client;

    // ------------------------------------------------------------------
    // 连接与建表
    // ------------------------------------------------------------------

    public MilvusClientV2 client() {
        if (client != null) {
            return client;
        }
        synchronized (lock) {
            if (client == null) {
                log.info("连接 Milvus {}", properties.getMilvusUri());
                MilvusClientV2 created = new MilvusClientV2(ConnectConfig.builder()
                        .uri(properties.getMilvusUri())
                        .connectTimeoutMs(properties.getConnectTimeoutMs())
                        .rpcDeadlineMs(properties.getRpcDeadlineMs())
                        .build());
                try {
                    ensureCollection(created);
                } catch (RuntimeException e) {
                    // 建连成功但集合没准备好（例如 Milvus 刚启动、Proxy 还没就绪）时，
                    // 不能把这个半成品客户端缓存下来，否则后续调用会一直失败
                    try {
                        created.close();
                    } catch (Exception ignored) {
                        // 忽略关闭异常
                    }
                    throw e;
                }
                client = created;
            }
        }
        return client;
    }

    /**
     * 丢弃当前客户端，下次调用会重新建连。
     * Milvus SDK 在连接失效时会一直重试，重置是让它恢复的最快办法。
     */
    public void resetClient() {
        synchronized (lock) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // 关闭失败无所谓，反正要丢弃
                }
                client = null;
            }
        }
    }

    private void ensureCollection(MilvusClientV2 client) {
        String name = properties.getCollection();
        Boolean exists = client.hasCollection(
                HasCollectionReq.builder().collectionName(name).build());
        if (Boolean.TRUE.equals(exists)) {
            client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
            return;
        }

        log.info("创建集合 {}（dim={}，dense + BM25 sparse）", name, properties.getDimension());
        CreateCollectionReq.CollectionSchema schema =
                MilvusSchemaFactory.buildSchema(client, properties.getDimension());
        client.createCollection(CreateCollectionReq.builder()
                .collectionName(name)
                .collectionSchema(schema)
                .indexParams(MilvusSchemaFactory.buildIndexParams())
                .build());
        client.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
    }

    // ------------------------------------------------------------------
    // 写入与删除
    // ------------------------------------------------------------------

    /**
     * 按 shopId 覆盖写入：先删这个店铺的旧分块，再写新分块（幂等）。
     * <p>
     * 传进来的 {@code chunks} 只应该是叶子块（父块由调用方写 MySQL）。
     *
     * @return 写入的分块数
     */
    public int replaceDocument(Long shopId, String title, String tenantId,
                               List<Chunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("分块数与向量数不一致");
        }
        MilvusClientV2 c = client();
        deleteDocument(shopId);
        if (chunks.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            JsonObject row = new JsonObject();
            row.addProperty(MilvusSchemaFactory.FIELD_CHUNK_ID, chunk.getChunkId());
            row.addProperty(MilvusSchemaFactory.FIELD_SHOP_ID, shopId);
            row.addProperty(MilvusSchemaFactory.FIELD_TITLE, truncate(title, 2000));
            // 只给原文：sparse 由 Milvus 的 BM25 Function 按 text 生成，这里不能自己塞
            row.addProperty(MilvusSchemaFactory.FIELD_TEXT, chunk.getText());
            row.addProperty(MilvusSchemaFactory.FIELD_TENANT_ID, tenantId);
            row.addProperty(MilvusSchemaFactory.FIELD_CHUNK_LEVEL, chunk.getLevel());
            row.addProperty(MilvusSchemaFactory.FIELD_CHUNK_IDX, chunk.getIndex());
            row.addProperty(MilvusSchemaFactory.FIELD_PARENT_CHUNK_ID,
                    chunk.getParentChunkId() == null ? "" : chunk.getParentChunkId());
            row.addProperty(MilvusSchemaFactory.FIELD_ROOT_CHUNK_ID,
                    chunk.getRootChunkId() == null ? "" : chunk.getRootChunkId());
            row.addProperty(MilvusSchemaFactory.FIELD_CONTENT_HASH,
                    chunk.getContentHash() == null ? "" : chunk.getContentHash());
            row.addProperty(MilvusSchemaFactory.FIELD_CREATED_AT, now);
            row.add(MilvusSchemaFactory.FIELD_DENSE, gson.toJsonTree(vectors.get(i)));
            rows.add(row);
        }

        try {
            c.insert(InsertReq.builder().collectionName(properties.getCollection())
                    .data(rows).build());
        } catch (RuntimeException e) {
            log.error("Milvus 写入失败，重置客户端以便重连：{}", e.getMessage());
            resetClient();
            throw e;
        }
        // 刻意不调用 flush：Milvus 的 flush 被限流（约 0.1 次/秒），频繁入库会直接报
        // rate limit exceeded。插入的数据会先落在 growing segment 里，立即可检索，
        // 且由 WAL 保证持久化，后续由 Milvus 自动 seal，不需要手工 flush。
        return rows.size();
    }

    /**
     * 删除某个店铺的全部分块（叶子）
     *
     * @return 删除的分块数
     */
    public int deleteDocument(Long shopId) {
        MilvusClientV2 c = client();
        String filter = MilvusSchemaFactory.FIELD_SHOP_ID + " == " + shopId;
        List<QueryResp.QueryResult> existing = c.query(QueryReq.builder()
                .collectionName(properties.getCollection())
                .filter(filter)
                .outputFields(Collections.singletonList(MilvusSchemaFactory.FIELD_CHUNK_ID))
                .limit(QUERY_LIMIT)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build()).getQueryResults();
        if (existing == null || existing.isEmpty()) {
            return 0;
        }
        c.delete(DeleteReq.builder()
                .collectionName(properties.getCollection())
                .filter(filter)
                .build());
        return existing.size();
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    /**
     * 稠密向量检索，只在指定层级上做。降级路径，也是对比实验的对照组。
     */
    public List<RetrievedChunk> search(float[] queryVector, int limit,
                                       String tenantId, int chunkLevel) {
        String filter = levelFilter(tenantId, chunkLevel);

        SearchResp resp;
        try {
            resp = client().search(SearchReq.builder()
                .collectionName(properties.getCollection())
                .data(Collections.singletonList(new FloatVec(queryVector)))
                .annsField(MilvusSchemaFactory.FIELD_DENSE)
                .topK(limit)
                .filter(filter)
                .consistencyLevel(ConsistencyLevel.STRONG)
                    .outputFields(Arrays.asList(MilvusSchemaFactory.OUTPUT_FIELDS))
                    .build());
        } catch (RuntimeException e) {
            log.error("Milvus 检索失败，重置客户端以便重连：{}", e.getMessage());
            resetClient();
            throw e;
        }
        return toHits(resp);
    }

    /**
     * 混合检索：稠密向量 + BM25 稀疏向量，两路各取 {@code limit × 2} 个候选，
     * 再用 RRF 按排名融合（不比分数，避免 BM25 和内积量纲不可比的问题）。
     * <p>
     * 稀疏那一路传的是<b>查询原文</b>：Milvus 会用 schema 里声明的同一个中文 analyzer
     * 切词，和写入时落在同一套词项上。
     */
    public List<RetrievedChunk> hybridSearch(String queryText, float[] queryVector, int limit,
                                             String tenantId, int chunkLevel) {
        String filter = levelFilter(tenantId, chunkLevel);
        int perPath = limit * PER_PATH_MULTIPLIER;

        AnnSearchReq dense = AnnSearchReq.builder()
                .vectorFieldName(MilvusSchemaFactory.FIELD_DENSE)
                .vectors(Collections.singletonList(new FloatVec(queryVector)))
                .metricType(IndexParam.MetricType.IP)
                .topK(perPath)
                .params("{\"ef\": 64}")
                .expr(filter)
                .build();

        AnnSearchReq sparse = AnnSearchReq.builder()
                .vectorFieldName(MilvusSchemaFactory.FIELD_SPARSE)
                .vectors(Collections.singletonList(new EmbeddedText(queryText)))
                .metricType(IndexParam.MetricType.BM25)
                .topK(perPath)
                .params("{\"drop_ratio_search\": " + properties.getHybridDropRatioSearch() + "}")
                .expr(filter)
                .build();

        SearchResp resp;
        try {
            resp = client().hybridSearch(HybridSearchReq.builder()
                    .collectionName(properties.getCollection())
                    .searchRequests(Arrays.asList(dense, sparse))
                    .ranker(new RRFRanker(properties.getHybridRrfK()))
                    .topK(limit)
                    .outFields(Arrays.asList(MilvusSchemaFactory.OUTPUT_FIELDS))
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
        } catch (RuntimeException e) {
            log.error("Milvus 混合检索失败，重置客户端以便重连：{}", e.getMessage());
            resetClient();
            throw e;
        }
        return toHits(resp);
    }

    /**
     * 把 search / hybridSearch 的返回整理成 RetrievedChunk
     */
    private List<RetrievedChunk> toHits(SearchResp resp) {
        List<RetrievedChunk> hits = new ArrayList<>();
        List<List<SearchResp.SearchResult>> results = resp == null ? null : resp.getSearchResults();
        if (results == null || results.isEmpty()) {
            return hits;
        }
        int rank = 0;
        for (SearchResp.SearchResult result : results.get(0)) {
            rank++;
            RetrievedChunk chunk = toChunk(result.getEntity(), result.getScore(), rank);
            // 主键不会出现在 entity 里，必须从 getId() 取；
            // 少了这一步 chunkId 就是空串，后面的去重会把所有结果都丢掉
            chunk.setChunkId(asString(result.getId()));
            hits.add(chunk);
        }
        return hits;
    }

    /**
     * 租户 + 层级的过滤条件（写入侧不做权限过滤，召回阶段就必须生效）
     */
    private static String levelFilter(String tenantId, int chunkLevel) {
        return MilvusSchemaFactory.FIELD_TENANT_ID + " == \"" + escape(tenantId) + "\""
                + " and " + MilvusSchemaFactory.FIELD_CHUNK_LEVEL + " == " + chunkLevel;
    }

    public String collectionName() {
        return properties.getCollection();
    }

    // ------------------------------------------------------------------

    private RetrievedChunk toChunk(Map<String, Object> entity, Number score, int rank) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setChunkId(asString(entity.get(MilvusSchemaFactory.FIELD_CHUNK_ID)));
        chunk.setShopId(asLong(entity.get(MilvusSchemaFactory.FIELD_SHOP_ID)));
        chunk.setTitle(asString(entity.get(MilvusSchemaFactory.FIELD_TITLE)));
        chunk.setText(asString(entity.get(MilvusSchemaFactory.FIELD_TEXT)));
        chunk.setChunkLevel(asInt(entity.get(MilvusSchemaFactory.FIELD_CHUNK_LEVEL)));
        chunk.setParentChunkId(asString(entity.get(MilvusSchemaFactory.FIELD_PARENT_CHUNK_ID)));
        chunk.setRootChunkId(asString(entity.get(MilvusSchemaFactory.FIELD_ROOT_CHUNK_ID)));
        chunk.setContentHash(asString(entity.get(MilvusSchemaFactory.FIELD_CONTENT_HASH)));
        chunk.setScore(score == null ? 0d : score.doubleValue());
        chunk.setRank(rank);
        return chunk;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int asInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * Milvus filter 字符串转义，避免注入
     */
    static String escape(String value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("关闭 Milvus 客户端失败：{}", e.getMessage());
                }
                client = null;
            }
        }
    }
}
