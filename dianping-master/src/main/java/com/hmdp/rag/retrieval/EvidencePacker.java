package com.hmdp.rag.retrieval;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 把命中的分块打包成带 [n] 编号的证据文本。
 * </p>
 * <p>
 * 编号是<b>按店铺</b>给的，不是按分块：一次检索可能命中同一家店的多个分块
 * （父块加叶子块，或者自动合并后剩下的几块），如果按分块编号就会出现
 * 「[1] 店A」「[3] 店A」两个编号指向同一家店，模型自然会标两次。
 * 这里把同一家店的所有分块并到一个编号下，于是「一家店一个编号」是结构上成立的。
 * </p>
 */
@Component
public class EvidencePacker {

    /**
     * @param hits  按名次排好序的命中
     * @param budget 证据文本的字符预算
     * @return 打包结果
     */
    public Packed pack(List<RetrievedChunk> hits, int budget) {
        // 先按店铺分组，组的顺序 = 该店最好那块的先后
        Map<Long, List<RetrievedChunk>> groups = new LinkedHashMap<>();
        for (RetrievedChunk hit : hits) {
            groups.computeIfAbsent(hit.getShopId(),
                    key -> new ArrayList<>()).add(hit);
        }

        List<String> blocks = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        int used = 0;

        for (List<RetrievedChunk> group : groups.values()) {
            // hits 已按名次排好，组内第一条就是这家店名次最好的那块
            RetrievedChunk best = group.get(0);
            int index = citations.size() + 1;

            StringBuilder body = new StringBuilder();
            body.append("[").append(index).append("] ")
                    .append(StrUtil.blankToDefault(best.getTitle(), String.valueOf(best.getShopId())));
            for (RetrievedChunk hit : group) {
                body.append('\n').append(StrUtil.blankToDefault(hit.getText(), "").trim());
            }
            String block = body.toString();

            // 预算已经装不下、且至少已有一家时停止
            if (used + block.length() > budget && !citations.isEmpty()) {
                break;
            }
            blocks.add(block);
            used += block.length();

            String text = StrUtil.blankToDefault(best.getText(), "");
            Citation citation = new Citation();
            citation.setIndex(index);
            citation.setShopId(best.getShopId());
            citation.setTitle(best.getTitle());
            citation.setChunkId(best.getChunkId());
            citation.setChunkLevel(best.getChunkLevel());
            citation.setScore(best.getScore());
            citation.setRerankScore(best.getRerankScore());
            citation.setRank(best.getRank());
            citation.setContentHash(best.getContentHash());
            citation.setSnippet(text.length() <= 200 ? text : text.substring(0, 200));
            citations.add(citation);
        }

        return new Packed(String.join("\n\n", blocks), citations);
    }

    /**
     * 打包结果
     */
    public static class Packed {
        private final String evidence;
        private final List<Citation> citations;

        public Packed(String evidence, List<Citation> citations) {
            this.evidence = evidence;
            this.citations = citations;
        }

        public String getEvidence() {
            return evidence;
        }

        public List<Citation> getCitations() {
            return citations;
        }
    }
}
