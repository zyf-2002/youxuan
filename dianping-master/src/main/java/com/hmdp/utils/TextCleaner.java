package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;

/**
 * <p>
 * 博客正文清洗。
 * </p>
 * <p>
 * 两件事：
 * <ol>
 *     <li>去掉 tb_blog.content 里混的 &lt;br/&gt;、&amp;nbsp; 之类片段</li>
 *     <li>去掉表情符号（Emoji / 颜文字 / 特殊符号）——它们对检索只有干扰，
 *         且在 Java 里按 UTF-16 码元计数时会切出半个字符</li>
 * </ol>
 * </p>
 */
public class TextCleaner {

    private static final String BR_PATTERN = "(?i)<\\s*br\\s*/?\\s*>";
    private static final String BLOCK_END_PATTERN =
            "(?i)</\\s*(p|div|li|tr|td|h[1-6]|section)\\s*>";

    private TextCleaner() {
    }

    /**
     * 清洗为规整纯文本：去 HTML、去表情、保留段落边界
     */
    public static String cleanBlogContent(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        String text = raw.replaceAll(BR_PATTERN, "\n");
        text = text.replaceAll(BLOCK_END_PATTERN, "\n");
        text = HtmlUtil.cleanHtmlTag(text);
        text = HtmlUtil.unescape(text);
        text = text.replace('\u3000', ' ').replace('\u00a0', ' ');
        return normalizeLines(stripEmojiRaw(text));
    }

    /**
     * 只去表情，保留换行结构（用于已经在库里的文本）
     */
    public static String stripEmoji(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        return normalizeLines(stripEmojiRaw(text));
    }

    // ------------------------------------------------------------------

    /**
     * 逐码点过滤表情符号
     */
    private static String stripEmojiRaw(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!isEmoji(cp)) {
                builder.appendCodePoint(cp);
            }
        }
        return builder.toString();
    }

    /**
     * 判断一个码点是否属于表情/装饰符号。
     * <p>
     * 范围参考 Unicode 的 Emoji 区块，并额外覆盖变体选择符、零宽连接符、
     * 键帽组合符和区域指示符（国旗）。
     * </p>
     */
    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)   // 各类 emoji 主区（含麻将/扑克/表情/交通/扩展A）
                || (cp >= 0x2600 && cp <= 0x27BF) // 杂项符号 + 装饰符号（☀✔✨等）
                || (cp >= 0x2B00 && cp <= 0x2BFF) // 杂项符号与箭头（⭐等）
                || (cp >= 0xFE00 && cp <= 0xFE0F) // 变体选择符
                || cp == 0x200D                   // 零宽连接符 ZWJ
                || cp == 0x20E3                   // 组合用包围键帽
                || (cp >= 0x203C && cp <= 0x2049) // ‼ ⁉
                || cp == 0x00A9 || cp == 0x00AE   // © ®
                || cp == 0x2122 || cp == 0x2139   // ™ ℹ
                || cp == 0x24C2                   // Ⓜ
                || (cp >= 0x231A && cp <= 0x23FA) // ⌚⌛⌨⏏⏩…⏺
                || (cp >= 0x25AA && cp <= 0x25FE) // ▪▫▶◀◻…◾
                || (cp >= 0x2934 && cp <= 0x2935) // ⤴ ⤵
                || cp == 0x3030 || cp == 0x303D   // 〰 〽
                || cp == 0x3297 || cp == 0x3299;  // ㊗ ㊙
                // 注意：刻意不删 U+2190–U+21FF 的箭头，像「地铁1号线 → 定安路」
                // 这类是有实际语义的文本，删了会丢信息
    }

    /**
     * 折叠空白、去掉只剩标点/符号的行
     */
    private static String normalizeLines(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean previousBlank = true;
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.replaceAll("[ \\t\\u3000]+", " ").trim();
            // 去掉表情后可能只剩 ·、- 之类的符号行，直接丢弃
            if (!containsText(line)) {
                line = "";
            }
            if (line.isEmpty()) {
                if (!previousBlank && out.length() > 0) {
                    out.append('\n');
                }
                previousBlank = true;
            } else {
                if (out.length() > 0 && !previousBlank) {
                    out.append('\n');
                }
                out.append(line);
                previousBlank = false;
            }
        }
        return out.toString().trim();
    }

    /**
     * 该行是否还有实际内容（字母、数字或汉字）
     */
    private static boolean containsText(String line) {
        return line.codePoints().anyMatch(Character::isLetterOrDigit);
    }
}
