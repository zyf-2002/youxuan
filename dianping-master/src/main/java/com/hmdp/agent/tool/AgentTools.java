package com.hmdp.agent.tool;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.agent.AgentToolContext;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.rag.retrieval.Citation;
import com.hmdp.rag.retrieval.KnowledgeSearchPipeline;
import com.hmdp.rag.retrieval.RetrievalResult;
import com.hmdp.rag.retrieval.RetrievalStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 暴露给大模型的工具，用 Spring AI 的 {@link Tool} 注解声明。
 * Spring AI 反射方法名和 {@code @ToolParam} 描述自动生成参数 Schema。
 * 需要当前用户身份的工具多声明一个 {@link ToolContext} 参数，它不进 Schema。
 */
@Slf4j
@Component
public class AgentTools {

    // ------------------------------------------------------------------
    // 工具名常量：与 AgentChatServiceImpl 里 TOOL_LABELS 的 key 一一对应，
    // 多一个少一个都会有工具没有进度提示。增删工具记得两边一起改。
    // ------------------------------------------------------------------

    public static final String SEARCH_KNOWLEDGE_BASE = "search_knowledge_base";
    public static final String SEARCH_SHOPS = "search_shops";
    public static final String GET_SHOP_DETAIL = "get_shop_detail";
    public static final String LIST_HOT_BLOGS = "list_hot_blogs";

    private static final int SHOP_LIMIT = 5;
    private static final int HOT_BLOG_LIMIT = 5;
    private static final int BLOG_CONTENT_PREVIEW = 60;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Resource
    private KnowledgeSearchPipeline knowledgeSearchPipeline;

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private VoucherMapper voucherMapper;

    @Resource
    private BlogMapper blogMapper;

    // ------------------------------------------------------------------
    // 知识库检索
    // ------------------------------------------------------------------

    @Tool(name = SEARCH_KNOWLEDGE_BASE,
            description = "按语义检索站内商户知识库。索引对象是平台上的商户，每家商户一份文档，"
                    + "包含店铺介绍、特色亮点、消费建议、推荐项目等。"
                    + "覆盖美食、KTV、美发美甲、按摩足疗、美容SPA、亲子游乐、酒吧、轰趴、健身等各类商户。"
                    + "当用户给的是体验、氛围、特色、口碑这类模糊条件时使用，"
                    + "例如「环境好的KTV」「适合约会的餐厅」「哪里适合带孩子玩」「有没有要避雷的」。")
    public String searchKnowledgeBase(
            @ToolParam(description = "检索用的自然语言问题，例如「杭州平价的港式茶餐厅」「环境好的KTV」") String query,
            ToolContext toolContext) {

        String normalized = StrUtil.blankToDefault(query, "").trim();
        if (normalized.isEmpty()) {
            return "工具执行失败：query 参数不能为空";
        }

        RetrievalResult result = knowledgeSearchPipeline.retrieve(normalized);
        List<Citation> citations = result.getCitations();
        String evidence = result.getEvidence();
        RetrievalStatus status = result.getRetrievalStatus();

        if (status == RetrievalStatus.NO_KNOWLEDGE
                || citations == null || citations.isEmpty() || StrUtil.isBlank(evidence)) {
            return "知识库里没有检索到与「" + normalized + "」相关的商户。"
                    + "请如实告诉用户站内没有相关内容，不要凭常识或猜测作答；"
                    + "如果换个说法可能命中，可以再检索一次。";
        }

        // 把引用带回给上层，前端要拿它渲染可点击的来源
        AgentToolContext context = AgentToolContext.from(toolContext);
        if (context != null) {
            context.addCitations(citations);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("知识库检索结果（route=").append(result.getRoute())
                .append("，共 ").append(citations.size()).append(" 家商户，")
                .append(status.getLabel()).append("）：\n");
        if (status == RetrievalStatus.INSUFFICIENT) {
            builder.append("注意：以下片段与问题相关，但信息不足以完整回答。"
                            + "只根据片段里明确写到的内容作答，缺失的部分如实说明「资料里没有提到」，"
                            + "不要推测或编造。\n");
        }
        // 证据块本身已经带了 [n] 编号和来源名；编号只给模型判断每条出自哪家店，
        // 不要在回答里输出（前端是按正文里的店名加链接的）
        builder.append(evidence);
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 商户检索与详情
    // ------------------------------------------------------------------

    @Tool(name = SEARCH_SHOPS,
            description = "按名称或商圈关键词精确搜索平台上的商户，返回商户id、名称、地址、人均消费、评分、"
                    + "营业时间和销量。适合用户已经提到具体店名或商圈的情况，例如「海底捞」「运河上街有什么」。"
                    + "如果用户给的是体验、氛围、特色这类模糊条件（例如「环境好的KTV」），"
                    + "应该用 search_knowledge_base。")
    public String searchShops(
            @ToolParam(description = "店铺名称或商圈关键词，例如「火锅」「西湖」「海底捞」") String keyword) {

        String normalized = StrUtil.blankToDefault(keyword, "").trim();
        if (normalized.isEmpty()) {
            return "工具执行失败：keyword 参数不能为空";
        }

        List<Shop> shops = shopMapper.selectList(new QueryWrapper<Shop>()
                .like("name", normalized).or().like("area", normalized)
                .orderByDesc("sold")
                .last("LIMIT " + SHOP_LIMIT));

        if (shops == null || shops.isEmpty()) {
            return "没有搜到与「" + normalized + "」相关的商户。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("搜索到 ").append(shops.size()).append(" 家商户：\n");
        for (Shop shop : shops) {
            builder.append("- id=").append(shop.getId())
                    .append("，").append(StrUtil.blankToDefault(shop.getName(), "未命名"))
                    .append("，地址：").append(StrUtil.blankToDefault(shop.getAddress(), "未提供"))
                    .append("，人均：").append(formatPrice(shop.getAvgPrice()))
                    .append("，评分：").append(shop.getScore() == null ? "暂无" : shop.getScore() / 10.0)
                    .append("，营业时间：").append(StrUtil.blankToDefault(shop.getOpenHours(), "未提供"))
                    .append("，已售：").append(shop.getSold() == null ? 0 : shop.getSold())
                    .append('\n');
        }
        return builder.toString();
    }

    @Tool(name = GET_SHOP_DETAIL,
            description = "根据商户id查询商户的完整信息：店铺介绍、特色亮点、消费建议、地址、商圈、人均、"
                    + "评分、营业时间、销量，以及该店在售的优惠券（券名、售价、面额、库存、使用规则）。"
                    + "当用户已经锁定某一家店，问「这家店怎么样」「有什么特色」「在哪」「有什么优惠券」"
                    + "「团购多少钱」时使用。商户id 来自 search_shops 或 search_knowledge_base 的结果。")
    public String getShopDetail(
            @ToolParam(description = "商户id，来自 search_shops 的返回结果") Long shopId) {

        if (shopId == null) {
            return "工具执行失败：shop_id 必须是数字";
        }

        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            return "没有找到 id=" + shopId + " 的商户，可能是 id 不正确。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("商户详情：\n")
                .append("- 名称：").append(StrUtil.blankToDefault(shop.getName(), "未命名")).append('\n')
                .append("- 地址：").append(StrUtil.blankToDefault(shop.getAddress(), "未提供")).append('\n')
                .append("- 商圈：").append(StrUtil.blankToDefault(shop.getArea(), "未提供")).append('\n')
                .append("- 人均：").append(formatPrice(shop.getAvgPrice())).append('\n')
                .append("- 评分：").append(shop.getScore() == null ? "暂无" : shop.getScore() / 10.0).append('\n')
                .append("- 营业时间：").append(StrUtil.blankToDefault(shop.getOpenHours(), "未提供")).append('\n')
                .append("- 累计销量：").append(shop.getSold() == null ? 0 : shop.getSold()).append('\n');

        appendSection(builder, "店铺介绍", shop.getDescription());
        appendSection(builder, "特色亮点", shop.getHighlights());
        appendSection(builder, "消费建议", shop.getTips());

        List<Voucher> vouchers = voucherMapper.selectList(new QueryWrapper<Voucher>()
                .eq("shop_id", shopId)
                .eq("status", 1));
        if (vouchers == null || vouchers.isEmpty()) {
            builder.append("\n该商户当前没有在售优惠券。");
        } else {
            builder.append("\n在售优惠券 ").append(vouchers.size()).append(" 张：\n");
            for (Voucher voucher : vouchers) {
                builder.append("- ").append(StrUtil.blankToDefault(voucher.getTitle(), "无标题"))
                        .append("，售价：").append(formatPrice(voucher.getPayValue()))
                        .append("，面额：").append(formatPrice(voucher.getActualValue()))
                        .append("，库存：").append(voucher.getStock() == null ? 0 : voucher.getStock())
                        .append('\n');
                if (StrUtil.isNotBlank(voucher.getRules())) {
                    builder.append("  使用规则：")
                            .append(StrUtil.maxLength(voucher.getRules().replaceAll("\\s+", " "), 120))
                            .append('\n');
                }
            }
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 热门笔记
    // ------------------------------------------------------------------

    @Tool(name = LIST_HOT_BLOGS,
            description = "列出当前平台上最热门的探店笔记（按点赞数排序）。"
                    + "当用户想「随便看看有什么推荐」「最近有什么热门的」而没有一个具体问题时使用。")
    public String listHotBlogs(
            @ToolParam(required = false, description = "返回条数，1-10，默认 5") Integer limit) {

        int size = limit == null ? HOT_BLOG_LIMIT : Math.max(1, Math.min(10, limit));

        List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>()
                .orderByDesc("liked")
                .last("LIMIT " + size));

        if (blogs == null || blogs.isEmpty()) {
            return "平台上暂时还没有探店笔记。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("热门笔记 ").append(blogs.size()).append(" 条：\n");
        for (Blog blog : blogs) {
            builder.append("- 笔记id=").append(blog.getId())
                    .append("，商户id=").append(blog.getShopId() == null ? "未知" : blog.getShopId())
                    .append("，标题：").append(StrUtil.blankToDefault(blog.getTitle(), "无标题"))
                    .append("，点赞：").append(blog.getLiked() == null ? 0 : blog.getLiked())
                    .append("，摘要：").append(preview(blog.getContent()))
                    .append('\n');
        }
        builder.append("\n如果想了解某条笔记对应的店铺，用 get_shop_detail 查询它的商户id。");
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private static String formatPrice(Long cents) {
        if (cents == null) {
            return "未提供";
        }
        return (cents / 100) + "元";
    }

    /**
     * 追加一段店铺画像文本，内容为空就整段跳过
     */
    private static void appendSection(StringBuilder builder, String title, String body) {
        if (StrUtil.isNotBlank(body)) {
            builder.append('\n').append(title).append("：\n").append(body).append('\n');
        }
    }

    private static String preview(String content) {
        if (StrUtil.isBlank(content)) {
            return "（无正文）";
        }
        String text = content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return StrUtil.maxLength(text, BLOG_CONTENT_PREVIEW);
    }
}
