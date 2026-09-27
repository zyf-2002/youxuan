package com.hmdp;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.utility.request.FlushReq;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技术验证：Milvus Java SDK 做纯向量（不含稀疏/BM25）的建集合、写入、检索。
 * <p>
 * 复用 {@link EmbeddingSmokeTest} 那套 DJL 本地向量化，把「向量化 + 向量库」整条链路
 * 一起验证掉。用的是独立的测试集合，不会动线上的 hmdp_knowledge_v2。
 * </p>
 */
public class MilvusSmokeTest {

    private static final String MILVUS_URI = "http://127.0.0.1:19530";
    private static final String COLLECTION = "hmdp_java_smoke_test";
    private static final int DIMENSION = 1024;

    private static final String DEFAULT_MODEL_DIR =
            "/home/zyf/springboot/hmdp/.tools/models/bge-m3-onnx";

    private final Gson gson = new Gson();

    // ------------------------------------------------------------------
    // 向量化（与 EmbeddingSmokeTest 同款，这里内联一份避免测试间耦合）
    // ------------------------------------------------------------------

    private static class EmbeddingTranslator implements Translator<String, float[]> {
        private final HuggingFaceTokenizer tokenizer;

        EmbeddingTranslator(HuggingFaceTokenizer tokenizer) {
            this.tokenizer = tokenizer;
        }

        @Override
        public Batchifier getBatchifier() {
            return null;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            Encoding encoding = tokenizer.encode(input);
            NDManager manager = ctx.getNDManager();
            NDArray ids = manager.create(encoding.getIds())
                    .reshape(new Shape(1, encoding.getIds().length))
                    .toType(DataType.INT64, false);
            NDArray mask = manager.create(encoding.getAttentionMask())
                    .reshape(new Shape(1, encoding.getAttentionMask().length))
                    .toType(DataType.INT64, false);
            return new NDList(ids, mask);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            for (NDArray array : list) {
                if (array.getShape().size() == DIMENSION) {
                    return array.toFloatArray();
                }
            }
            throw new IllegalStateException("找不到 1024 维句向量");
        }
    }

    private Predictor<String, float[]> buildPredictor() throws Exception {
        String dir = System.getProperty("bge.model.dir", DEFAULT_MODEL_DIR);
        Path onnxDir = Paths.get(dir).resolve("onnx");

        Map<String, String> options = new HashMap<>();
        options.put("truncation", "true");
        options.put("maxLength", "512");

        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(onnxDir, options);
        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(onnxDir.resolve("model.onnx"))
                .optEngine("OnnxRuntime")
                .optTranslator(new EmbeddingTranslator(tokenizer))
                .build();
        ZooModel<String, float[]> model = criteria.loadModel();
        return model.newPredictor();
    }

    // ------------------------------------------------------------------

    @Test
    public void milvusDenseOnlyRoundTrip() throws Exception {
        MilvusClientV2 client = new MilvusClientV2(
                ConnectConfig.builder().uri(MILVUS_URI).build());
        try {
            // 清掉上次残留
            if (Boolean.TRUE.equals(client.hasCollection(
                    HasCollectionReq.builder().collectionName(COLLECTION).build()))) {
                client.dropCollection(DropCollectionReq.builder()
                        .collectionName(COLLECTION).build());
            }

            createCollection(client);
            System.out.println("集合创建成功: " + COLLECTION);

            try (Predictor<String, float[]> predictor = buildPredictor()) {
                // 三条真实内容，shop_id 就用那篇博客对应的真实店铺id
                List<SmokeDoc> docs = List.of(
                        new SmokeDoc(26L,
                                "藏在巷子里的杭帮菜小馆，人均60元，招牌是东坡肉和龙井虾仁，宋嫂鱼羹也不错"),
                        new SmokeDoc(5L,
                                "又吃到一家好吃的港式茶餐厅，怀旧tvb港风，人均30元，招牌黯然销魂饭和漏奶华"),
                        new SmokeDoc(4L,
                                "小筑里神秘浪漫花园西餐厅，到处都是花，战斧牛排外焦里嫩，奶油培根意面也好吃"));

                List<JsonObject> rows = new ArrayList<>();
                for (SmokeDoc doc : docs) {
                    float[] vector = predictor.predict(doc.text());
                    JsonObject row = new JsonObject();
                    row.addProperty("chunk_id", doc.shopId() + "::l2::0::0");
                    row.addProperty("shop_id", doc.shopId());
                    row.addProperty("text", doc.text());
                    row.addProperty("chunk_level", 2);
                    row.add("dense", gson.toJsonTree(vector));
                    rows.add(row);
                }

                client.insert(InsertReq.builder()
                        .collectionName(COLLECTION).data(rows).build());
                client.flush(FlushReq.builder()
                        .collectionNames(Collections.singletonList(COLLECTION)).build());
                System.out.println("写入 " + rows.size() + " 条向量完成");

                // 检索：问茶餐厅，应该命中 shop_id=5
                float[] query = predictor.predict("杭州平价的港式茶餐厅推荐");
                SearchResp resp = client.search(SearchReq.builder()
                        .collectionName(COLLECTION)
                        .data(Collections.singletonList(new FloatVec(query)))
                        .annsField("dense")
                        .topK(3)
                        .outputFields(Arrays.asList("shop_id", "text"))
                        .build());

                List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
                assertFalse(results.isEmpty(), "检索没有返回结果");
                List<SearchResp.SearchResult> hits = results.get(0);
                assertEquals(3, hits.size(), "返回条数不对");

                System.out.println("检索「杭州平价的港式茶餐厅推荐」命中：");
                for (SearchResp.SearchResult hit : hits) {
                    System.out.println("  score=" + String.format("%.4f", hit.getScore())
                            + "  shop=" + hit.getEntity().get("shop_id")
                            + "  " + abbreviate(String.valueOf(hit.getEntity().get("text"))));
                }

                // 说明：bge-m3 对这两条主题相近的短文本区分度有限（0.6326 vs 0.6172），
                // Python 侧独立计算得到完全相同的排序，所以这不是 Java 的问题。
                // 这里断言「两条餐饮笔记都明显高于无关的西餐笔记」，而不是断言谁是第一。
                List<Long> topShops = Arrays.asList(
                        asLong(hits.get(0).getEntity().get("shop_id")),
                        asLong(hits.get(1).getEntity().get("shop_id")));
                assertTrue(topShops.contains(26L) && topShops.contains(5L),
                        "前二应该是两条餐饮笔记，实际：" + topShops);
                assertFalse(topShops.contains(4L),
                        "无关的西餐笔记不应该进前二，实际：" + topShops);
                assertTrue(hits.get(2).getScore() < hits.get(1).getScore() - 0.1,
                        "相关与无关之间应该有明显分差");

                // 再问一次牛排，应该换一条命中
                float[] query2 = predictor.predict("战斧牛排 花园西餐厅 环境");
                SearchResp resp2 = client.search(SearchReq.builder()
                        .collectionName(COLLECTION)
                        .data(Collections.singletonList(new FloatVec(query2)))
                        .annsField("dense")
                        .topK(1)
                        .outputFields(Collections.singletonList("shop_id"))
                        .build());
                long topShop2 = asLong(
                        resp2.getSearchResults().get(0).get(0).getEntity().get("shop_id"));
                System.out.println("检索「战斧牛排 花园西餐厅 环境」Top1 = " + topShop2);
                assertEquals(4L, topShop2, "Top1 应该是牛排那条");
            }
        } finally {
            // 清理测试集合，不影响正式数据
            try {
                if (Boolean.TRUE.equals(client.hasCollection(
                        HasCollectionReq.builder().collectionName(COLLECTION).build()))) {
                    client.dropCollection(DropCollectionReq.builder()
                            .collectionName(COLLECTION).build());
                    System.out.println("测试集合已清理");
                }
            } catch (Exception e) {
                System.out.println("清理测试集合失败：" + e.getMessage());
            }
            client.close();
        }
    }

    private void createCollection(MilvusClientV2 client) {
        // 注意：这里必须用 Milvus 的 DataType 全限定名，
        // 因为文件顶部已经 import 了 DJL 的同名类 ai.djl.ndarray.types.DataType
        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id").dataType(io.milvus.v2.common.DataType.VarChar)
                .maxLength(512).isPrimaryKey(true).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("shop_id").dataType(io.milvus.v2.common.DataType.Int64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("text").dataType(io.milvus.v2.common.DataType.VarChar)
                .maxLength(8192).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_level").dataType(io.milvus.v2.common.DataType.Int8).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("dense").dataType(io.milvus.v2.common.DataType.FloatVector)
                .dimension(DIMENSION).build());

        Map<String, Object> extra = new HashMap<>();
        extra.put("M", 16);
        extra.put("efConstruction", 200);

        IndexParam indexParam = IndexParam.builder()
                .fieldName("dense")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.IP)
                .extraParams(extra)
                .build();

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(COLLECTION)
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build());
    }

    private static String abbreviate(String text) {
        return text.length() <= 30 ? text : text.substring(0, 30) + "…";
    }

    private static long asLong(Object value) {
        return value instanceof Number
                ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * 冒烟用的短文档：店铺id + 正文
     */
    private record SmokeDoc(long shopId, String text) {
    }
}
