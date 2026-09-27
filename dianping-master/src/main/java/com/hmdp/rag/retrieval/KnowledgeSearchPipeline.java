package com.hmdp.rag.retrieval;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AgentProperties;
import com.hmdp.config.LlmAvailability;
import com.hmdp.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * 手写检索管线，替代原来 Python 侧的 LangGraph。
 * </p>
 * <p>
 * 流程与原图一致，只是从图的节点变成直线代码：
 * </p>
 * <pre>
 *   classify_complexity    判断问题复杂度
 *   prepare_sub_questions  拆子问题
 *   retrieve_initial       首轮检索
 *   grade_documents        相关性评分
 *   rewrite_question       问题重写（证据不足时）：Step-back / HyDE 由模型二选一
 *   retrieve_rewritten     用改写后的文本再检索一次，然后重新评分
 *   synthesis              合并去重 + 精排 + 证据打包
 * </pre>
 * <p>
 * 模型不可用时**显式标记 gradeSkipped** 并跳过评分，不会把「没评分」伪装成「评分结果为空」。
 * </p>
 */
@Slf4j
@Component
public class KnowledgeSearchPipeline {

    private static final int MAX_SUB_QUESTIONS = 3;
    private static final int SNIPPET_CHARS = 240;

    /**
     * 改写后用来检索的文本上限。HyDE 会产出一段假想文档，比一句查询长得多，
     * 所以这里给得比原来宽松，只用来挡住模型跑飞的情况。
     */
    private static final int REWRITE_MAX_CHARS = 500;

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private static final String CLASSIFY_PROMPT =
            "判断用户问题属于哪种检索场景，只输出 JSON，不要解释。\n"
                    + "simple  = 单一事实型问题，一次检索就能回答\n"
                    + "complex = 需要多个方面/多次检索才能回答\n"
                    + "只输出：{\"route\": \"simple\" 或 \"complex\"}\n\n"
                    + "用户问题：%s";

    private static final String SUB_QUESTION_PROMPT =
            "把用户问题拆成最多 %d 个可以独立检索的子问题，只输出 JSON。\n"
                    + "如果问题本身已经很具体，就原样返回它。\n"
                    + "只输出：{\"questions\": [\"子问题1\", \"子问题2\"]}\n\n"
                    + "用户问题：%s";

    private static final String GRADE_PROMPT =
            "你在为检索结果做相关性评分。只输出 JSON，不要解释。\n"
                    + "relevant  = 与问题直接相关、能帮助回答的片段序号（从 1 开始）\n"
                    + "sufficient = 仅凭这些片段是否足以回答问题\n"
                    + "只输出：{\"relevant\": [1, 3], \"sufficient\": true}\n\n"
                    + "用户问题：%s\n\n候选片段：\n%s";

    /**
     * 改写提示词：让模型在 Step-back 与 HyDE 之间**二选一**，而不是泛泛地「换个说法」。
     * 两种策略针对的失败原因不同，混在一起反而都不像。
     */
    private static final String REWRITE_PROMPT =
            "下面这个问题用原始问法检索效果不好。请选择一种更可能命中的改写方式，"
                    + "在两种里**只选一种**，只输出 JSON，不要解释。\n\n"
                    + "两种方式：\n"
                    + "1. step_back（退一步）：把过细的问题抽象成更上位、更概括的问题，"
                    + "用来检索更宽的相关内容。\n"
                    + "   例：「103茶餐厅的东坡肉多少钱」→「103茶餐厅的推荐菜和价格」\n"
                    + "2. hyde（假想答案）：先写一段「假如知识库里真有答案，它大概会怎么写」的假想内容，"
                    + "用这段内容去检索。因为答案和文档的措辞比问题更像，命中率通常更高。\n"
                    + "   例：「杭州人均30的港式茶餐厅」→ " 
                    + "「这家港式茶餐厅人均30元左右，主打烧腊和港式奶茶，招牌是黯然销魂饭和漏奶华，"
                    + "开在商场负一层，环境是怀旧港风。」\n"
                    + "   例：「适合聚会的KTV」→ "
                    + "「这家KTV包厢宽敞，音响设备新，曲库更新快，下午场价格实惠，适合朋友聚会。」\n\n"
                    + "只填你选中的那个字段，另一个留空字符串。输出格式（严格照此）：\n"
                    + "{\"rewrite_method\":\"step_back\",\"step_back_question\":\"改写成的问题\",\"hyde_document\":\"\"}\n"
                    + "或\n"
                    + "{\"rewrite_method\":\"hyde\",\"step_back_question\":\"\",\"hyde_document\":\"假想内容\"}\n\n"
                    + "原问题：%s";

    @Resource
    private RagProperties ragProperties;

    @Resource
    private AgentProperties agentProperties;

    @Resource
    private RetrievalService retrievalService;

    @Resource
    private RerankService rerankService;

    @Resource
    private ChatClient.Builder chatClientBuilder;

    @Resource
    private LlmAvailability llmAvailability;

    private ChatClient chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void initChatClient() {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 走完整管线检索
     */
    public RetrievalResult retrieve(String question) {
        long start = System.currentTimeMillis();
        String query = RetrievalService.normalize(question);

        if (!ragProperties.isUsePipeline()) {
            return retrievalService.singlePass(query);
        }

        RetrievalResult result = new RetrievalResult();
        result.setQuery(query);
        Map<String, Object> trace = new LinkedHashMap<>();
        result.setTrace(trace);

        boolean modelAvailable = llmAvailable();

        // 1. 复杂度分类
        String route = modelAvailable ? classify(query, trace) : "skipped";
        result.setRoute(route);

        // 2. 子问题
        List<String> subQuestions = prepareSubQuestions(query, route, modelAvailable, trace);
        result.setSubQuestions(subQuestions);

        // 3. 首轮检索：召回 → auto-merge → 精排
        //    精排必须在这里做，不能放到评分之后：原 Python 侧 retrieve_documents 的流程是
        //    recall_merge_rerank，精排属于「检索」这一步，Grader 拿到的是已经排好序的候选。
        RetrievalService.Recall recall = retrieveAll(subQuestions);
        List<RetrievedChunk> hits = rerank(query, recall.hits(), result);
        result.setRetrievalMode(recall.mode());
        trace.put("initialHits", hits.size());

        // 4. 评分
        GradeOutcome graded = grade(query, hits, modelAvailable);
        result.setGradeSkipped(graded.skipped);
        trace.put("grade", graded.trace);

        // 5. 证据不足时重写再检索：Step-back / HyDE 由模型二选一，最多一次
        int rewrites = 0;
        while (graded.hits.isEmpty()
                && rewrites < Math.max(0, ragProperties.getMaxRewrites())
                && !hits.isEmpty()
                && modelAvailable) {
            RewriteOutcome outcome = rewrite(query, trace);
            if (outcome == null) {
                // 模型没能在两种策略里做出有效选择，直接放弃重写
                break;
            }
            if (outcome.query.equals(query)) {
                trace.put("rewriteNoChange", true);
                break;
            }
            rewrites++;
            List<RetrievedChunk> second = rerank(query, retrievalService.search(outcome.query), result);
            trace.put("rewrite_" + rewrites + "_hits", second.size());
            hits = dedupe(concat(hits, second));
            graded = grade(query, hits, modelAvailable);
            result.setGradeSkipped(graded.skipped);
        }
        result.setRewriteCount(rewrites);

        // 6. 收口：把评分结论落成明确状态，并据此决定给模型看哪些证据
        List<RetrievedChunk> finalHits;
        if (graded.skipped) {
            // 没做评分（模型未配置 / 评分失败 / 压根没召回）：保留召回结果，如实标记「没评过」
            finalHits = hits;
            result.setRetrievalStatus(hits.isEmpty()
                    ? RetrievalStatus.NO_KNOWLEDGE : RetrievalStatus.GRADE_SKIPPED);
        } else if (graded.hits.isEmpty()) {
            // 评分跑了、一条相关都没挑出来 —— 这就是「没有相关内容」。
            // 以前这里会回退成「那就把全部召回都给它」，等于把评分的结论反过来用，
            // 模型于是拿着一堆判定为不相关的片段硬编答案。
            finalHits = new ArrayList<>();
            result.setRetrievalStatus(RetrievalStatus.NO_KNOWLEDGE);
        } else {
            finalHits = graded.hits;
            result.setRetrievalStatus(graded.sufficient
                    ? RetrievalStatus.ANSWERABLE : RetrievalStatus.INSUFFICIENT);
        }
        trace.put("retrievalStatus", result.getRetrievalStatus().name());

        retrievalService.applyEvidence(result, finalHits);
        trace.put("elapsedMs", System.currentTimeMillis() - start);
        trace.put("finalHits", result.getCandidateCount());

        log.info("检索结论 {}：召回 {} 条 → 采用 {} 条（route={}，改写 {} 次，耗时 {}ms）",
                result.getRetrievalStatus().name(), hits.size(), result.getCandidateCount(),
                result.getRoute(), result.getRewriteCount(), System.currentTimeMillis() - start);

        return result;
    }

    // ------------------------------------------------------------------
    // 各节点
    // ------------------------------------------------------------------

    private String classify(String query, Map<String, Object> trace) {
        try {
            JsonNode node = completeJson(String.format(CLASSIFY_PROMPT, query));
            String route = node.path("route").asText("").trim().toLowerCase();
            if (!"simple".equals(route) && !"complex".equals(route)) {
                route = "simple";
            }
            trace.put("classify", route);
            return route;
        } catch (Exception e) {
            log.warn("复杂度分类失败，按 simple 处理：{}", e.getMessage());
            trace.put("classify", "failed");
            trace.put("classifyError", e.getMessage());
            return "skipped";
        }
    }

    private List<String> prepareSubQuestions(String query, String route, boolean modelAvailable,
                                             Map<String, Object> trace) {
        List<String> questions = new ArrayList<>();
        if ("complex".equals(route) && modelAvailable) {
            try {
                JsonNode node = completeJson(String.format(
                        SUB_QUESTION_PROMPT, MAX_SUB_QUESTIONS, query));
                JsonNode array = node.path("questions");
                if (array.isArray()) {
                    for (JsonNode item : array) {
                        String text = item.asText("").trim().replaceAll("\\s+", " ");
                        if (!text.isEmpty() && questions.size() < MAX_SUB_QUESTIONS) {
                            questions.add(text);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("子问题拆分失败，退回原问题：{}", e.getMessage());
            }
        }
        if (questions.isEmpty()) {
            questions.add(query);
        }
        trace.put("subQuestions", questions);
        return questions;
    }

    private RetrievalService.Recall retrieveAll(List<String> questions) {
        List<RetrievedChunk> collected = new ArrayList<>();
        String mode = RetrievalService.MODE_DENSE;
        for (String question : questions) {
            RetrievalService.Recall recall = retrievalService.recall(question);
            List<RetrievedChunk> hits = recall.hits();
            // 混合召回失败会自动退化成稠密，只要有一次走通了就说明这条路可用
            if (RetrievalService.MODE_HYBRID.equals(recall.mode())) {
                mode = RetrievalService.MODE_HYBRID;
            }
            // 每个子问题内部重新排名，避免不同问题的名次互相覆盖
            for (int i = 0; i < hits.size(); i++) {
                hits.get(i).setRank(i + 1);
            }
            collected.addAll(hits);
        }
        return new RetrievalService.Recall(dedupe(collected), mode);
    }

    /**
     * 精排：对完整召回集重排序，必须在 Grader 之前调用。
     * <p>
     * 与原 Python 侧 {@code retrieve_documents} 的 recall_merge_rerank 一致——精排属于
     * 「检索」这一步。先让 cross-encoder 把候选按相关性排好，Grader 看到的才是最有希望的一批；
     * 反过来先评分再精排，精排就只能在评分挑剩的几条里打转，等于白开。
     * </p>
     */
    private List<RetrievedChunk> rerank(String query, List<RetrievedChunk> hits,
                                        RetrievalResult result) {
        if (!rerankService.enabled() || hits == null || hits.isEmpty()) {
            return hits;
        }
        RerankService.Outcome outcome = rerankService.apply(query, hits);
        result.setRerankError(outcome.getError());
        result.setRerankApplied(outcome.getError() == null);
        if (outcome.getError() != null) {
            log.warn("精排未生效（{}），沿用召回顺序", outcome.getError());
        } else {
            log.info("精排完成：{} 条候选已重排序", outcome.getHits().size());
        }
        return outcome.getHits();
    }

    private static class GradeOutcome {
        private final List<RetrievedChunk> hits;
        private final boolean skipped;
        /**
         * 仅当 skipped=false 时有意义：模型判断这些片段够不够回答问题
         */
        private final boolean sufficient;
        private final Map<String, Object> trace;

        GradeOutcome(List<RetrievedChunk> hits, boolean skipped, boolean sufficient,
                     Map<String, Object> trace) {
            this.hits = hits;
            this.skipped = skipped;
            this.sufficient = sufficient;
            this.trace = trace;
        }
    }

    private GradeOutcome grade(String query, List<RetrievedChunk> hits, boolean modelAvailable) {
        Map<String, Object> trace = new LinkedHashMap<>();
        if (hits.isEmpty()) {
            trace.put("relevant", 0);
            trace.put("candidates", 0);
            return new GradeOutcome(new ArrayList<>(), true, false, trace);
        }
        if (!modelAvailable) {
            trace.put("skipped", "no_model");
            return new GradeOutcome(new ArrayList<>(hits), true, false, trace);
        }

        StringBuilder snippets = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            String text = StrUtil.blankToDefault(hits.get(i).getText(), "");
            snippets.append(i + 1).append(". ")
                    .append(text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS))
                    .append('\n');
        }

        try {
            JsonNode node = completeJson(String.format(GRADE_PROMPT, query, snippets));
            List<RetrievedChunk> selected = new ArrayList<>();
            JsonNode relevant = node.path("relevant");
            if (relevant.isArray()) {
                for (JsonNode item : relevant) {
                    int position = item.asInt(-1);
                    if (position >= 1 && position <= hits.size()) {
                        selected.add(hits.get(position - 1));
                    }
                }
            }
            boolean sufficient = node.path("sufficient").asBoolean(false);
            if (selected.isEmpty() && sufficient) {
                // 模型自相矛盾：声称充分却没给片段，按不充分处理
                sufficient = false;
            }
            trace.put("relevant", selected.size());
            trace.put("candidates", hits.size());
            trace.put("sufficient", sufficient);
            log.info("相关性评分：候选 {} 条 → 选中 {} 条，sufficient={}",
                    hits.size(), selected.size(), sufficient);
            return new GradeOutcome(selected, false, sufficient, trace);
        } catch (Exception e) {
            log.warn("相关性评分失败，保留全部召回：{}", e.getMessage());
            trace.put("failed", e.getMessage());
            return new GradeOutcome(new ArrayList<>(hits), true, false, trace);
        }
    }

    /**
     * 一次查询改写的产物：策略 + 实际拿去检索的文本
     */
    private static class RewriteOutcome {
        private final String method;
        private final String query;
        private final String stepBackQuestion;
        private final String hydeDocument;

        RewriteOutcome(String method, String query, String stepBackQuestion, String hydeDocument) {
            this.method = method;
            this.query = query;
            this.stepBackQuestion = stepBackQuestion;
            this.hydeDocument = hydeDocument;
        }
    }

    /**
     * 让模型在 Step-back 与 HyDE 之间二选一，返回实际用于检索的文本。
     * <p>
     * 返回 null 表示这次改写不可用（模型没做选择、或选中了却没给内容）——
     * 此时直接放弃重写，而不是拿一个半成品去检索。
     * </p>
     */
    private RewriteOutcome rewrite(String query, Map<String, Object> trace) {
        try {
            JsonNode node = completeJson(String.format(REWRITE_PROMPT, query));

            String method = node.path("rewrite_method").asText("").trim().toLowerCase();
            String stepBack = normalizeText(node.path("step_back_question").asText(""));
            String hyde = normalizeText(node.path("hyde_document").asText(""));

            if (!"step_back".equals(method) && !"hyde".equals(method)) {
                throw new IllegalStateException(
                        "模型没有在 step_back / hyde 之间做出选择：" + StrUtil.maxLength(method, 40));
            }
            // 选中哪个就用哪个字段，另一个即使被填了也不采用——保证「只执行一种重写」
            String searchText = "step_back".equals(method) ? stepBack : hyde;
            if (StrUtil.isBlank(searchText)) {
                throw new IllegalStateException("模型选了 " + method + " 却没给出对应内容");
            }
            String unused = "step_back".equals(method) ? hyde : stepBack;
            if (StrUtil.isNotBlank(unused)) {
                trace.put("rewriteUnusedField",
                        "step_back".equals(method) ? "hyde_document" : "step_back_question");
            }
            if (searchText.length() > REWRITE_MAX_CHARS) {
                searchText = searchText.substring(0, REWRITE_MAX_CHARS);
            }

            trace.put("rewriteMethod", method);
            trace.put("rewrittenQuery", searchText);
            if ("step_back".equals(method)) {
                trace.put("stepBackQuestion", stepBack);
            } else {
                trace.put("hydeDocument", hyde);
            }
            log.info("问题改写：模型选择 {}（原问题 {} 字 → 检索文本 {} 字）：{}",
                    "step_back".equals(method) ? "Step-back（退一步）" : "HyDE（假想答案）",
                    query.length(), searchText.length(), StrUtil.maxLength(searchText, 120));
            return new RewriteOutcome(method, searchText, stepBack, hyde);
        } catch (Exception e) {
            log.warn("问题改写失败，放弃本次重写：{}", e.getMessage());
            trace.put("rewriteFailed", e.getMessage());
            return null;
        }
    }

    private static String normalizeText(String value) {
        return StrUtil.blankToDefault(value, "").trim().replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------------

    private boolean llmAvailable() {
        return llmAvailability.configured();
    }

    /**
     * 用「便宜快速」的模型做一次无系统提示的单轮补全，供分类/评分/重写这类小任务使用
     */
    private String fastChat(String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder()
                        .model(agentProperties.getLlm().getFastModel())
                        .build())
                .call()
                .content();
    }

    private JsonNode completeJson(String prompt) throws Exception {
        String raw = fastChat(prompt);
        Matcher matcher = JSON_BLOCK.matcher(StrUtil.blankToDefault(raw, ""));
        if (!matcher.find()) {
            throw new IllegalStateException("模型未返回 JSON：" + StrUtil.maxLength(raw, 120));
        }
        return objectMapper.readTree(matcher.group());
    }

    private List<RetrievedChunk> dedupe(List<RetrievedChunk> hits) {
        Map<String, RetrievedChunk> best = new LinkedHashMap<>();
        for (RetrievedChunk hit : hits) {
            if (StrUtil.isBlank(hit.getChunkId())) {
                continue;
            }
            RetrievedChunk current = best.get(hit.getChunkId());
            if (current == null || hit.getRank() < current.getRank()) {
                best.put(hit.getChunkId(), hit);
            }
        }
        List<RetrievedChunk> result = new ArrayList<>(best.values());
        result.sort((a, b) -> Integer.compare(a.getRank(), b.getRank()));
        return result;
    }

    private static List<RetrievedChunk> concat(List<RetrievedChunk> a, List<RetrievedChunk> b) {
        List<RetrievedChunk> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }
}
