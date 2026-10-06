package com.kncatl.ohmyworld.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kncatl.ohmyworld.expr.BlockResolver;
import com.kncatl.ohmyworld.expr.ExprEvaluator;

/**
 * 公式语法着色：把整段公式扫描成互不重叠的带色片段（{@link Span}）。
 *
 * <p>这是一个「够用就好」的启发式扫描器：输入允许是不完整或带语法错误的公式，
 * 扫描过程不抛异常，无法识别的部分留作默认色。结果按整串公式缓存，文本未变时
 * （同一帧或连续帧）零开销复用。
 */
public final class FormulaHighlighter {

    /** 普通文本。 */
    public static final int COLOR_DEFAULT = 0xFFDCDCDC;
    /** 关键字：let、维度名、指令名与指令模式词。 */
    public static final int COLOR_KEYWORD = 0xFF569CD6;
    /** 数字。 */
    public static final int COLOR_NUMBER = 0xFFB5CEA8;
    /** 方块 / 结构 / 群系 id。 */
    public static final int COLOR_ID = 0xFF4EC9B0;
    /** 变量：y / x / z / ly / seed，以及 let 绑定的名字。 */
    public static final int COLOR_VARIABLE = 0xFF9CDCFE;
    /** 函数名。 */
    public static final int COLOR_FUNCTION = 0xFFDCDCAA;
    /** 运算符与括号。 */
    public static final int COLOR_OPERATOR = 0xFF808A96;

    /** 一段带颜色的文本区间，[start, end)。 */
    public record Span(int start, int end, int color) {}

    private static final Set<String> VARIABLES = Set.of("x", "z", "ly", "seed");
    private static final Set<String> DIRECTIVE_NAMES = Set.of("structure", "biome", "features");
    private static final Set<String> DIRECTIVE_MODES = Set.of("all", "none", "only", "except", "vanilla");
    private static final Set<String> DIMENSIONS = Set.of("overworld", "nether", "the_nether", "end", "the_end");

    /** 裸名字是否可解析为方块；含否定结果，避免每次按键都查注册表。 */
    private static final Map<String, Boolean> BLOCK_NAME_CACHE = new HashMap<>();

    private static String cachedText;
    private static List<Span> cachedSpans = List.of();

    private FormulaHighlighter() {}

    /** 取得整段公式的着色片段：按 start 升序、互不重叠，未覆盖部分用默认色。 */
    public static List<Span> spans(String text) {
        if (text == null || text.isEmpty()) return List.of();
        if (text.equals(cachedText)) return cachedSpans;
        List<Span> result = scan(text);
        cachedText = text;
        cachedSpans = result;
        return result;
    }

    private static List<Span> scan(String s) {
        List<Span> out = new ArrayList<>();
        Set<String> letNames = new HashSet<>();
        int n = s.length();
        int i = 0;
        boolean inDirective = false;

        while (i < n) {
            char c = s.charAt(i);

            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < n && isIdentPart(s.charAt(j))) j++;

                // 命名空间 id（namespace:path）。指令括号内拆开处理，
                // 这样 [biome:plains] 的 "biome" 能按指令名着色。
                if (!inDirective && j < n && s.charAt(j) == ':' && j + 1 < n && isPathChar(s.charAt(j + 1))) {
                    int k = j + 2;
                    while (k < n && isPathChar(s.charAt(k))) k++;
                    out.add(new Span(i, k, COLOR_ID));
                    i = k;
                    continue;
                }

                String word = s.substring(i, j);
                if (word.equals("let") && !inDirective) {
                    out.add(new Span(i, j, COLOR_KEYWORD));
                    int k = skipSpaces(s, j);
                    if (k < n && isIdentStart(s.charAt(k))) {
                        int m = k + 1;
                        while (m < n && isIdentPart(s.charAt(m))) m++;
                        letNames.add(s.substring(k, m));
                        out.add(new Span(k, m, COLOR_VARIABLE));
                        i = m;
                        continue;
                    }
                    i = j;
                    continue;
                }
                out.add(new Span(i, j, classify(s, j, word, letNames, inDirective)));
                i = j;
                continue;
            }

            if (isDigit(c)) {
                int j = i + 1;
                while (j < n && isDigit(s.charAt(j))) j++;
                if (j + 1 < n && s.charAt(j) == '.' && isDigit(s.charAt(j + 1))) {
                    j += 2;
                    while (j < n && isDigit(s.charAt(j))) j++;
                }
                out.add(new Span(i, j, COLOR_NUMBER));
                i = j;
                continue;
            }

            if (c == '[') {
                int prev = previousNonSpace(s, i);
                if (prev < 0 || s.charAt(prev) != '*') inDirective = true; // N*[...] 是环层语法
                out.add(new Span(i, i + 1, COLOR_OPERATOR));
                i++;
                continue;
            }
            if (c == ']') {
                inDirective = false;
                out.add(new Span(i, i + 1, COLOR_OPERATOR));
                i++;
                continue;
            }
            if (c == '.' && i + 1 < n && s.charAt(i + 1) == '.') {
                out.add(new Span(i, i + 2, COLOR_OPERATOR));
                i += 2;
                continue;
            }
            int opLen = operatorLength(s, i);
            if (opLen > 0) {
                out.add(new Span(i, i + opLen, COLOR_OPERATOR));
                i += opLen;
                continue;
            }
            i++;
        }
        return out;
    }

    private static int classify(String s, int end, String word, Set<String> letNames, boolean inDirective) {
        if (inDirective) {
            if (DIRECTIVE_NAMES.contains(word) || DIRECTIVE_MODES.contains(word)) return COLOR_KEYWORD;
            return COLOR_ID;
        }
        int k = skipSpaces(s, end);
        if (k < s.length() && s.charAt(k) == '(') {
            return ExprEvaluator.isFunctionName(word) ? COLOR_FUNCTION : COLOR_DEFAULT;
        }
        if (word.equals("y") || VARIABLES.contains(word) || letNames.contains(word)) return COLOR_VARIABLE;
        if (DIMENSIONS.contains(word)) return COLOR_KEYWORD;
        if (isKnownBlockWord(word)) return COLOR_ID;
        return COLOR_DEFAULT;
    }

    /** 小写 [a-z0-9_] 形式的裸名字才查方块表（带缓存的注册表查询）。 */
    private static boolean isKnownBlockWord(String word) {
        char first = word.charAt(0);
        if (first < 'a' || first > 'z') return false;
        for (int i = 1; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!((c >= 'a' && c <= 'z') || isDigit(c) || c == '_')) return false;
        }
        return BLOCK_NAME_CACHE.computeIfAbsent(word, w -> BlockResolver.exists("minecraft:" + w));
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    private static boolean isPathChar(char c) {
        return isIdentPart(c) || c == '/' || c == '.' || c == '-';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int skipSpaces(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int previousNonSpace(String s, int from) {
        int i = from - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i;
    }

    /** 该位置开始的运算符长度（0 表示不是运算符）。 */
    private static int operatorLength(String s, int i) {
        char c = s.charAt(i);
        if (i + 1 < s.length()) {
            char d = s.charAt(i + 1);
            if ((c == '=' || c == '!' || c == '<' || c == '>') && d == '=') return 2;
            if ((c == '&' && d == '&') || (c == '|' && d == '|')) return 2;
        }
        if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%'
                || c == '<' || c == '>' || c == '=' || c == '!' || c == '?'
                || c == ':' || c == ';' || c == ',' || c == '(' || c == ')'
                || c == '{' || c == '}') return 1;
        return 0;
    }
}
