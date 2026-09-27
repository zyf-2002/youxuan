package com.hmdp.rag.embedding;

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
import com.hmdp.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * bge-m3 本地向量化（DJL + ONNX Runtime）。
 * </p>
 * <p>
 * 替代原来 Python 侧的 sentence-transformers。模型是官方 ONNX 导出，
 * 图里已经包含 CLS 池化和 L2 归一化，所以这里只需要喂 input_ids 和 attention_mask，
 * 然后取 1024 维的 sentence_embedding。
 * </p>
 * <p>
 * 模型加载约 2 秒，启动时预热一次，避免第一个请求卡住。
 * </p>
 */
@Slf4j
@Component
public class BgeM3EmbeddingService implements DisposableBean {

    private static final String WARMUP_TEXT = "预热";

    @Resource
    private RagProperties properties;

    private final Object lock = new Object();

    private ZooModel<String, float[]> model;

    private Predictor<String, float[]> predictor;

    private volatile boolean initialized;

    // ------------------------------------------------------------------

    /**
     * 把一段文本编码成 1024 维、已归一化的向量
     */
    public float[] embed(String text) {
        ensureInitialized();
        // DJL 的 Predictor 不是线程安全的，这里串行化。
        // 单条文本在 CPU 上是百毫秒级，对本项目的并发量足够。
        synchronized (lock) {
            try {
                return predictor.predict(text);
            } catch (Exception e) {
                throw new IllegalStateException("向量化失败：" + e.getMessage(), e);
            }
        }
    }

    /**
     * 批量向量化
     */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    /**
     * 预热：把模型提前装进内存
     */
    public void warmup() {
        long start = System.currentTimeMillis();
        float[] vector = embed(WARMUP_TEXT);
        log.info("向量模型预热完成，维度 {}，耗时 {} ms",
                vector.length, System.currentTimeMillis() - start);
    }

    public int dimension() {
        return properties.getDimension();
    }

    // ------------------------------------------------------------------

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            initialize();
            initialized = true;
        }
    }

    private void initialize() {
        Path onnxDir = Paths.get(properties.getModelDir()).resolve("onnx");
        Path onnxFile = onnxDir.resolve("model.onnx");
        if (!Files.exists(onnxFile)) {
            throw new IllegalStateException(
                    "找不到 ONNX 模型：" + onnxFile + "，请检查 hmdp.rag.model-dir 配置");
        }

        try {
            Map<String, String> options = new HashMap<>();
            options.put("truncation", "true");
            options.put("maxLength", String.valueOf(properties.getMaxTokens()));

            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(onnxDir, options);
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelPath(onnxFile)
                    .optEngine("OnnxRuntime")
                    .optTranslator(new EmbeddingTranslator(
                            tokenizer, properties.getDimension(), properties.getMaxTokens()))
                    .build();
            log.info("向量化 maxTokens = {}", properties.getMaxTokens());

            long start = System.currentTimeMillis();
            model = criteria.loadModel();
            predictor = model.newPredictor();
            log.info("向量模型加载完成：{}，耗时 {} ms",
                    onnxFile, System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new IllegalStateException("加载 bge-m3 ONNX 模型失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            if (predictor != null) {
                predictor.close();
                predictor = null;
            }
            if (model != null) {
                model.close();
                model = null;
            }
            initialized = false;
        }
        log.info("向量模型已释放");
    }

    /**
     * 文本 -> NDList（input_ids / attention_mask），输出句向量
     */
    private static class EmbeddingTranslator implements Translator<String, float[]> {

        private final HuggingFaceTokenizer tokenizer;
        private final int dimension;
        private final int maxTokens;

        EmbeddingTranslator(HuggingFaceTokenizer tokenizer, int dimension, int maxTokens) {
            this.tokenizer = tokenizer;
            this.dimension = dimension;
            this.maxTokens = maxTokens;
        }

        @Override
        public Batchifier getBatchifier() {
            // processInput 里已经带了 batch 维度，不能再让 DJL 叠一层
            return null;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            Encoding encoding = tokenizer.encode(input);
            // 超限会被截断，且是静默的；显式告警，方便发现分块尺寸设置不合理
            if (encoding.getIds().length > maxTokens) {
                log.warn("文本超过 maxTokens({}), 实际 {} token，尾部会被截断",
                        maxTokens, encoding.getIds().length);
            }
            NDManager manager = ctx.getNDManager();

            NDArray inputIds = manager.create(encoding.getIds())
                    .reshape(new Shape(1, encoding.getIds().length))
                    .toType(DataType.INT64, false);
            NDArray attentionMask = manager.create(encoding.getAttentionMask())
                    .reshape(new Shape(1, encoding.getAttentionMask().length))
                    .toType(DataType.INT64, false);

            return new NDList(inputIds, attentionMask);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            // 模型有两个输出：token_embeddings [1,seq,1024] 和 sentence_embedding [1,1024]。
            // 不依赖顺序，取长度为 1024 的那个
            for (NDArray array : list) {
                if (array.getShape().size() == dimension) {
                    return array.toFloatArray();
                }
            }
            StringBuilder shapes = new StringBuilder();
            for (NDArray array : list) {
                shapes.append(array.getShape()).append(' ');
            }
            throw new IllegalStateException("模型输出里没有 " + dimension + " 维句向量：" + shapes);
        }
    }
}
