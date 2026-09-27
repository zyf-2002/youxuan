package com.hmdp.rag.store;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.client.MilvusClientV2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * Milvus 集合 schema：稠密向量 + BM25 稀疏向量，标量字段只保留检索需要的。
 * </p>
 * <p>
 * 父子分块分开存储：<b>只有叶子块写进这个集合</b>（{@code chunk_level=2}），父块在
 * MySQL 的 {@code tb_shop_knowledge_chunk} 里，靠 {@code parent_chunk_id} 关联。
 * 身份只有 {@code shop_id}（数字），没有 "shop-" 前缀的 docId。
 * </p>
 * <p>
 * 稀疏向量由 Milvus 自己生成：{@code text} 字段开中文 analyzer，BM25 Function 把它转成
 * {@code sparse} 字段。应用侧只提交原文，不维护词表/IDF。
 * </p>
 */
public final class MilvusSchemaFactory {

    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_SHOP_ID = "shop_id";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_TENANT_ID = "tenant_id";
    public static final String FIELD_CHUNK_LEVEL = "chunk_level";
    public static final String FIELD_CHUNK_IDX = "chunk_idx";
    public static final String FIELD_PARENT_CHUNK_ID = "parent_chunk_id";
    public static final String FIELD_ROOT_CHUNK_ID = "root_chunk_id";
    public static final String FIELD_CONTENT_HASH = "content_hash";
    public static final String FIELD_CREATED_AT = "created_at";
    public static final String FIELD_DENSE = "dense";
    public static final String FIELD_SPARSE = "sparse";

    /**
     * BM25 Function 的名字：输入 {@code text}，输出 {@code sparse}
     */
    public static final String FUNCTION_BM25 = "text_bm25";

    /**
     * 建索引时丢掉每篇文档里权重最低的这部分词项（省空间、加速，代价是长尾词召回变弱）。
     * 这是<b>建库期</b>参数，改了要重建集合；查询侧的裁剪比例放在配置里，可随时调。
     */
    private static final double DROP_RATIO_BUILD = 0.2;

    /**
     * 检索时需要的标量字段
     */
    public static final String[] OUTPUT_FIELDS = {
            FIELD_SHOP_ID,
            FIELD_TITLE,
            FIELD_TEXT,
            FIELD_CHUNK_LEVEL,
            FIELD_PARENT_CHUNK_ID,
            FIELD_ROOT_CHUNK_ID,
            FIELD_CONTENT_HASH,
    };

    private MilvusSchemaFactory() {
    }

    public static CreateCollectionReq.CollectionSchema buildSchema(MilvusClientV2 client,
                                                                  int dimension) {
        CreateCollectionReq.CollectionSchema schema = client.createSchema();

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_ID)
                .dataType(DataType.VarChar)
                .maxLength(512)
                .isPrimaryKey(true)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_SHOP_ID)
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TITLE)
                .dataType(DataType.VarChar)
                .maxLength(2048)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TEXT)
                .dataType(DataType.VarChar)
                .maxLength(65535)
                // 分词、词表、IDF 统计全交给 Milvus：中文 analyzer 在这里声明，
                // 写入和查询共用同一套分词，BM25 的词项才能对上
                .enableAnalyzer(true)
                .analyzerParams(Map.of("type", "chinese"))
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TENANT_ID)
                .dataType(DataType.VarChar)
                .maxLength(64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_LEVEL)
                .dataType(DataType.Int8)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_IDX)
                .dataType(DataType.Int32)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_PARENT_CHUNK_ID)
                .dataType(DataType.VarChar)
                .maxLength(512)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_ROOT_CHUNK_ID)
                .dataType(DataType.VarChar)
                .maxLength(512)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT_HASH)
                .dataType(DataType.VarChar)
                .maxLength(64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CREATED_AT)
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_DENSE)
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_SPARSE)
                .dataType(DataType.SparseFloatVector)
                .build());

        // 稀疏向量不由应用侧计算：声明成 BM25 函数的输出，写入时只给 text
        schema.addFunction(CreateCollectionReq.Function.builder()
                .name(FUNCTION_BM25)
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of(FIELD_TEXT))
                .outputFieldNames(List.of(FIELD_SPARSE))
                .build());

        return schema;
    }

    public static IndexParam buildDenseIndex() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("M", 16);
        extra.put("efConstruction", 200);

        return IndexParam.builder()
                .fieldName(FIELD_DENSE)
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.IP)
                .extraParams(extra)
                .build();
    }

    public static IndexParam buildSparseIndex() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("drop_ratio_build", DROP_RATIO_BUILD);

        return IndexParam.builder()
                .fieldName(FIELD_SPARSE)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .extraParams(extra)
                .build();
    }

    public static List<IndexParam> buildIndexParams() {
        List<IndexParam> indexes = new ArrayList<>(2);
        indexes.add(buildDenseIndex());
        indexes.add(buildSparseIndex());
        return indexes;
    }
}
