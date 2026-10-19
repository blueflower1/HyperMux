package com.hypermux.server.common;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * 片名拼音检索工具：为影片预生成「全拼 / 首字母」两列，让只能输入拉丁字母的客户端
 * （TV 遥控器自绘键盘）也能搜中文片名，例如 glgs → 灌篮高手、gaoshou → 全拼子串命中。
 * 多音字取 pinyin4j 首选（常用）读音；拉丁字母/数字原样保留（小写），其余字符丢弃。
 */
public final class PinyinUtils {

    /** 与 schema.sql 中 title_pinyin varchar(512) / title_initials varchar(128) 对齐 */
    private static final int FULL_MAX = 512;
    private static final int INITIALS_MAX = 128;

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinUtils() {
    }

    /** 全拼（小写、无分隔拼接；如 灌篮高手 → guanlangaoshou）。无可用字符时返回空串。 */
    public static String fullPinyin(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (sb.length() >= FULL_MAX) {
                break;
            }
            String py = pinyinOf(c);
            if (py != null) {
                sb.append(py);
            } else if (isAsciiLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 首字母（小写；汉字取拼音首字母，拉丁字母/数字原样；如 灌篮高手 → glgs）。无可用字符时返回空串。 */
    public static String initials(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (sb.length() >= INITIALS_MAX) {
                break;
            }
            String py = pinyinOf(c);
            if (py != null) {
                sb.append(py.charAt(0));
            } else if (isAsciiLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 单字拼音（小写、无声调）；非汉字返回 null。 */
    private static String pinyinOf(char c) {
        try {
            String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
            return arr == null || arr.length == 0 ? null : arr[0];
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            // FORMAT 是常量组合，理论上不会发生；防御性兜底
            return null;
        }
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }
}
