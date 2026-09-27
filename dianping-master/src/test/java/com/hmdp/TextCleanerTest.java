package com.hmdp;

import com.hmdp.utils.TextCleaner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>
 * 正文清洗的单元测试：去 HTML、去表情、保留有语义的文本符号。
 * </p>
 */
public class TextCleanerTest {

    /**
     * 表情符号要全部清掉
     */
    @Test
    public void stripsEmoji() {
        String raw = "人均30💰杭州这家港式茶餐厅我疯狂打call‼️"
                + "🍴环境是怀旧tvb港风📺边吃边拍照片📷"
                + "✔️黯然销魂饭（38💰）这碗饭我吹爆🍳"
                + "奶盖流心像瀑布一样流出来🍫满足✨⭐❤️";
        String cleaned = TextCleaner.stripEmoji(raw);

        System.out.println("清洗后: " + cleaned);

        assertFalse(cleaned.contains("💰"), "💰 应被清除");
        assertFalse(cleaned.contains("🍴"), "🍴 应被清除");
        assertFalse(cleaned.contains("‼"), "‼(U+203C) 应被清除");
        assertFalse(cleaned.contains("\uFE0F"), "变体选择符应被清除");
        assertFalse(cleaned.contains("✨"), "✨ 应被清除");
        assertFalse(cleaned.contains("⭐"), "⭐ 应被清除");
        assertTrue(cleaned.contains("杭州这家港式茶餐厅"), "正文必须保留");
        assertTrue(cleaned.contains("黯然销魂饭"), "正文必须保留");
    }

    /**
     * 有实际语义的文本符号不能被误删
     */
    @Test
    public void keepsMeaningfulSymbols() {
        String raw = "交通：地铁1号线 → 定安路B口，出站右转50米。"
                + "人均：30元（约20~40元）"
                + "营业时间 11:00-21:00";
        String cleaned = TextCleaner.stripEmoji(raw);

        System.out.println("清洗后: " + cleaned);
        assertTrue(cleaned.contains("→"), "文本箭头有语义，不能删");
        assertTrue(cleaned.contains("30元"), "数字要保留");
    }

    /**
     * HTML 片段要清掉，段落结构要保留
     */
    @Test
    public void cleansHtmlAndKeepsParagraphs() {
        String raw = "店名：九记冰厅<br/>地址：丽水路远洋乐堤港<br/>&nbsp;"
                + "环境是怀旧TVB港风<br/>人均30元";
        String cleaned = TextCleaner.cleanBlogContent(raw);

        System.out.println("清洗后: " + cleaned);
        assertFalse(cleaned.contains("<br"), "HTML 标签应被清除");
        assertFalse(cleaned.contains("&nbsp;"), "HTML 实体应被清除");
        assertTrue(cleaned.contains("九记冰厅"));
        assertTrue(cleaned.contains("人均30元"));
        assertTrue(cleaned.contains("\n"), "段落边界应保留");
    }

    /**
     * 只剩符号的「空行」要被丢掉，不能留下孤零零的 · 或 ·
     */
    @Test
    public void dropsSymbolOnlyLines() {
        String raw = "✔️黯然销魂饭（38）<br>·<br>·<br>这碗饭我吹爆";
        String cleaned = TextCleaner.cleanBlogContent(raw);

        System.out.println("清洗后: " + cleaned);
        for (String line : cleaned.split("\n")) {
            assertTrue(line.trim().isEmpty() || line.codePoints()
                            .anyMatch(Character::isLetterOrDigit),
                    "不应留下纯符号行：" + line);
        }
    }

    /**
     * 空输入不应抛异常
     */
    @Test
    public void handlesBlankInput() {
        assertEquals("", TextCleaner.cleanBlogContent(null));
        assertEquals("", TextCleaner.cleanBlogContent(""));
        assertEquals("", TextCleaner.cleanBlogContent("   "));
        assertEquals("", TextCleaner.stripEmoji(null));
    }
}
