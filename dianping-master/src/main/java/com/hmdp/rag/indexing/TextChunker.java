package com.hmdp.rag.indexing;

import com.hmdp.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <p>
 * 两级父子分块。
 * </p>
 * <p>
 * 之前照搬 SuperMew 的三级分块（2400/1600/800），但 hmdp 的博客正文上限只有 2048 字，
 * 2400 和 1600 比整篇还大，导致 L1/L2 永远各只切出 1 块，两级完全没起作用。
 * 现在改成两级，尺寸按博客实际长度重新设计，并且可配置：
 * </p>
 * <pre>
 *   L1 父块   1200 字，重叠 100   —— 自动合并后给模型更完整的上下文
 *   L2 叶子块  400 字，重叠  50   —— 向量化与检索都在这一层
 * </pre>
 */
@Component
public class TextChunker {

    /**
     * 中文优先的分隔符序列，从粗到细。最后一个是空串，表示退化为硬切
     */
    private static final String[] SEPARATORS = {
            "\n\n", "\n", "。", "！", "？", "；", "…", "，", "、", " ", ""
    };

    @Resource
    private RagProperties properties;

    /**
     * 切出父块与叶子块
     *
     * @param payload 清洗后的正文
     * @param shopId  店铺id，用来拼分块id
     */
    public List<Chunk> split(String payload, long shopId) {
        String text = payload == null ? "" : payload.trim();
        if (text.isEmpty()) {
            return Collections.emptyList();
        }

        List<Chunk> chunks = new ArrayList<>();
        String prefix = String.valueOf(shopId);

        List<String> parents = applyOverlap(
                splitOnce(text, properties.getParentSize(), SEPARATORS),
                properties.getParentOverlap());

        for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
            String parentText = parents.get(parentIndex);
            String parentId = prefix + "::l1::" + parentIndex;
            chunks.add(build(parentId, parentText, 1, parentIndex, "", parentId));

            List<String> leaves = applyOverlap(
                    splitOnce(parentText, properties.getLeafSize(), SEPARATORS),
                    properties.getLeafOverlap());
            for (int leafIndex = 0; leafIndex < leaves.size(); leafIndex++) {
                String leafId = prefix + "::l2::" + parentIndex + "::" + leafIndex;
                chunks.add(build(leafId, leaves.get(leafIndex), 2, leafIndex,
                        parentId, parentId));
            }
        }
        return chunks;
    }

    // ------------------------------------------------------------------

    private Chunk build(String chunkId, String text, int level, int index,
                        String parentChunkId, String rootChunkId) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setText(text);
        chunk.setLevel(level);
        chunk.setIndex(index);
        chunk.setParentChunkId(parentChunkId);
        chunk.setRootChunkId(rootChunkId);
        chunk.setContentHash(contentHash(text));
        return chunk;
    }

    /**
     * 按分隔符优先级递归切分，尽量每片不超过 size
     */
    private List<String> splitOnce(String text, int size, String[] separators) {
        if (text.length() <= size) {
            return text.trim().isEmpty()
                    ? Collections.emptyList() : Collections.singletonList(text);
        }

        String separator = "";
        int restFrom = separators.length;
        for (int i = 0; i < separators.length; i++) {
            String candidate = separators[i];
            if (candidate.isEmpty()) {
                // 遇到空串说明没有更细的分隔符了，退化为硬切
                break;
            }
            if (text.contains(candidate)) {
                separator = candidate;
                restFrom = i + 1;
                break;
            }
        }

        if (separator.isEmpty()) {
            List<String> pieces = new ArrayList<>();
            int position = 0;
            while (position < text.length()) {
                int end = safeCut(text, Math.min(position + size, text.length()));
                if (end <= position) {
                    // 保底：确保每次至少前进，避免死循环
                    end = Math.min(position + size, text.length());
                }
                String piece = text.substring(position, end);
                if (!piece.trim().isEmpty()) {
                    pieces.add(piece);
                }
                position = end;
            }
            return pieces;
        }

        String[] parts = text.split(Pattern.quote(separator), -1);
        String[] rest = restFrom >= separators.length
                ? new String[0] : Arrays.copyOfRange(separators, restFrom, separators.length);

        List<String> chunks = new ArrayList<>();
        String buffer = "";
        for (String part : parts) {
            String merged = buffer.isEmpty() ? part : buffer + separator + part;
            if (merged.length() <= size) {
                buffer = merged;
                continue;
            }
            if (!buffer.trim().isEmpty()) {
                chunks.add(buffer);
            }
            if (part.length() <= size) {
                buffer = part;
            } else {
                chunks.addAll(splitOnce(part, size, rest));
                buffer = "";
            }
        }
        if (!buffer.trim().isEmpty()) {
            chunks.add(buffer);
        }
        return chunks;
    }

    /**
     * 给相邻分片加尾部重叠，避免语义被硬切断
     */
    private List<String> applyOverlap(List<String> chunks, int overlap) {
        if (overlap <= 0 || chunks.size() < 2) {
            return new ArrayList<>(chunks);
        }
        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1);
            String current = chunks.get(i);
            if (previous.length() <= overlap) {
                result.add(previous.trim().isEmpty() ? current : previous + current);
                continue;
            }
            int cut = safeCut(previous, previous.length() - overlap);
            String tail = previous.substring(cut);
            result.add(tail.trim().isEmpty() ? current : tail + current);
        }
        return result;
    }

    /**
     * 避免把 UTF-16 代理对（emoji 等增补平面字符）从中间切开。
     * Java 的 substring 按码元计数，emoji 占两个码元，切在中间会产生半个字符。
     */
    private static int safeCut(String text, int index) {
        if (index <= 0 || index >= text.length()) {
            return index;
        }
        return Character.isLowSurrogate(text.charAt(index)) ? index - 1 : index;
    }

    private String contentHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算内容哈希失败", e);
        }
    }
}
