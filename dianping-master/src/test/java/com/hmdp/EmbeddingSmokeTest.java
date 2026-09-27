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
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技术验证：用 DJL + ONNX Runtime 在 JVM 内跑 bge-m3，产出 1024 维向量。
 * <p>
 * 这个测试不启动 Spring，纯粹验证「Java 能不能替代 Python 做向量化」。
 * 模型路径通过 -Dbge.model.dir 指定，默认取工作区下的 .tools/models/bge-m3-onnx。
 * </p>
 * <p>
 * 参考值来自 Python onnxruntime 跑同一句话的结果，用来确认 Java 侧没有走偏。
 * </p>
 */
public class EmbeddingSmokeTest {

    private static final String DEFAULT_MODEL_DIR =
            "/home/zyf/springboot/hmdp/.tools/models/bge-m3-onnx";

    /**
     * Python 侧对同一句话的参考向量前 5 位
     */
    private static final float[] REFERENCE_HEAD = {
            -0.02738800086081028f,
            0.009430999867618084f,
            -0.017544999718666077f,
            -0.015876999124884605f,
            0.010199000127613544f,
    };

    private static final float TOLERANCE = 1e-4f;

    private static Path modelDir() {
        String configured = System.getProperty("bge.model.dir", DEFAULT_MODEL_DIR);
        return Paths.get(configured);
    }

    /**
     * 把一段文本编码成 NDList（input_ids + attention_mask）
     */
    private static class EmbeddingTranslator implements Translator<String, float[]> {

        private final HuggingFaceTokenizer tokenizer;

        EmbeddingTranslator(HuggingFaceTokenizer tokenizer) {
            this.tokenizer = tokenizer;
        }

        @Override
        public Batchifier getBatchifier() {
            // 我们自己在 processInput 里已经带了 batch 维度（1, seq），
            // 不覆盖的话 DJL 会再叠一层变成 (1, 1, seq)，ONNX 直接报 rank 不对
            return null;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            Encoding encoding = tokenizer.encode(input);
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
            // 模型有两个输出：token_embeddings [1, seq, 1024] 和 sentence_embedding [1, 1024]。
            // 不依赖顺序，直接挑长度为 1024 的那个（句向量已经在图里做过 CLS 池化和归一化）
            for (NDArray array : list) {
                if (array.getShape().size() == 1024) {
                    return array.toFloatArray();
                }
            }
            throw new IllegalStateException("模型输出里没有找到 1024 维的句向量，实际形状："
                    + list.stream().map(a -> a.getShape().toString()).reduce("", (a, b) -> a + " " + b));
        }
    }

    private static HuggingFaceTokenizer buildTokenizer(Path onnxDir) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("truncation", "true");
        options.put("maxLength", "512");
        return HuggingFaceTokenizer.newInstance(onnxDir, options);
    }

    @Test
    public void bgeM3ProducesCorrectVector() throws Exception {
        Path dir = modelDir();
        Path onnxFile = dir.resolve("onnx/model.onnx");
        Path onnxDir = dir.resolve("onnx");

        assertTrue(Files.exists(onnxFile), "找不到 ONNX 模型：" + onnxFile);
        assertTrue(Files.exists(onnxDir.resolve("tokenizer.json")), "找不到 tokenizer.json");

        System.out.println("模型目录: " + dir);

        HuggingFaceTokenizer tokenizer = buildTokenizer(onnxDir);
        EmbeddingTranslator translator = new EmbeddingTranslator(tokenizer);

        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(onnxFile)
                .optEngine("OnnxRuntime")
                .optTranslator(translator)
                .build();

        long start = System.currentTimeMillis();
        try (ZooModel<String, float[]> model = criteria.loadModel();
             Predictor<String, float[]> predictor = model.newPredictor()) {

            System.out.printf("模型加载耗时 %.1fs%n", (System.currentTimeMillis() - start) / 1000.0);

            String sentence = "杭州有什么好吃的茶餐厅";
            float[] vector = predictor.predict(sentence);

            // 1. 维度必须是 1024
            assertEquals(1024, vector.length, "向量维度不对");

            // 2. 必须已经归一化
            double norm = 0;
            for (float v : vector) {
                norm += (double) v * v;
            }
            norm = Math.sqrt(norm);
            System.out.printf("L2 范数 = %.6f%n", norm);
            assertEquals(1.0, norm, 1e-3, "向量没有被归一化");

            // 3. 和 Python 侧的参考向量一致
            System.out.print("前5位 = [");
            for (int i = 0; i < 5; i++) {
                System.out.printf("%.6f%s", vector[i], i < 4 ? ", " : "");
            }
            System.out.println("]");
            for (int i = 0; i < REFERENCE_HEAD.length; i++) {
                assertEquals(REFERENCE_HEAD[i], vector[i], TOLERANCE,
                        "第 " + i + " 位与 Python 参考值不一致");
            }

            // 4. 语义自检：相似句余弦明显高于无关句
            float[] similar = predictor.predict("杭州哪里能吃到港式茶餐厅");
            float[] unrelated = predictor.predict("如何用 Rust 写操作系统内核");

            double cosSimilar = cosine(vector, similar);
            double cosUnrelated = cosine(vector, unrelated);
            System.out.printf("相似句余弦 = %.4f%n", cosSimilar);
            System.out.printf("无关句余弦 = %.4f%n", cosUnrelated);

            assertTrue(cosSimilar > 0.8, "相似句余弦过低：" + cosSimilar);
            assertTrue(cosUnrelated < 0.5, "无关句余弦过高：" + cosUnrelated);
            assertTrue(cosSimilar > cosUnrelated + 0.3, "相似句和无关句区分度不足");
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        // 两个向量都已归一化，点积即余弦
        return dot;
    }
}
