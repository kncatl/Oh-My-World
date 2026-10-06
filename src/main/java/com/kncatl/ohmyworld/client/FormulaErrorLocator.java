package com.kncatl.ohmyworld.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 错误定位：把 {@link com.kncatl.ohmyworld.FormulaParser} 给出的错误文本启发式地
 * 映射回原文区间，供编辑器标注「可能出错的位置」。
 *
 * <p>解析器只产出具可读性的错误字符串，这里按消息结构近似反推位置：
 * 维度前缀 → 对应节；{@code Layer N:} → 第 N 段；能从消息里抠出
 * 名字 / 引文 / {@code at position N} 时再缩小到具体片段；拿不准就退回
 * 上一级区间；完全无法定位的错误（如整体为空）不产生标注。
 * 整个过程不抛异常——定位只是提示，任何意外都不应影响编辑。
 */
public final class FormulaErrorLocator {

    private static final String[] DIMENSIONS = {"overworld", "the_nether", "the_end"};

    private static final Pattern AT_POSITION = Pattern.compile("at position (\\d+)");
    private static final Pattern UNKNOWN_VARIABLE = Pattern.compile("Unknown variable: (\\S+)");
    private static final Pattern UNKNOWN_BLOCK = Pattern.compile("Unknown block: (\\S+)");
    private static final Pattern UNKNOWN_FUNCTION = Pattern.compile("Unknown function: (\\S+)");
    private static final Pattern UNKNOWN_DIRECTIVE = Pattern.compile("unknown directive \\[([^\\]]{1,60})\\]");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"([^\"]{1,60})\"");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']{2,60})'");

    private FormulaErrorLocator() {}

    /** 返回所有能定位到的错误区间（原文坐标，[start, end)，可能重叠或为空）。 */
    public static List<int[]> locate(String input, List<String> errors) {
        List<int[]> found = new ArrayList<>();
        if (input == null || input.isEmpty() || errors == null || errors.isEmpty()) return found;
        try {
            Cleaned cleaned = Cleaned.of(input);
            List<Section> sections = scanSections(cleaned.text());
            for (String error : errors) {
                if (error == null) continue;
                int[] span = locateOne(cleaned.text(), sections, error.trim());
                if (span != null && span[1] > span[0]) found.add(cleaned.toOriginal(span[0], span[1]));
            }
        } catch (Throwable ignored) {
            // 定位失败不影响任何正常功能
        }
        return found;
    }

    // ---------------------------------------------------------------- 单条错误

    private static int[] locateOne(String text, List<Section> sections, String error) {
        String rest = error;

        // 1) 维度节前缀：节内错误统一带 "name: " 前缀（name 是规范名）
        Section scope = null;
        for (String dim : DIMENSIONS) {
            if (rest.startsWith(dim + ": ")) {
                scope = findSection(sections, dim, rest.contains("duplicate"));
                rest = rest.substring(dim.length() + 2).trim();
                break;
            }
        }

        // 2) 层前缀："Layer N: ..."，N 是所在节（或整段）里的层序号
        int[] segment = null;
        if (rest.startsWith("Layer ")) {
            int colon = rest.indexOf(':');
            int number = colon > 6 ? parseNumber(rest.substring(6, colon)) : -1;
            if (number > 0) {
                segment = scope != null
                        ? sectionLayerSegment(text, scope, number - 1)
                        : segmentAt(text, 0, text.length(), number - 1);
                if (segment != null) rest = rest.substring(colon + 1).trim();
            }
        }

        int[] base = segment != null ? segment
                : scope != null ? new int[] {scope.open() + 1, scope.close()}
                : null;
        if (base != null) {
            int[] narrowed = narrow(text, base, rest, segment != null);
            return narrowed != null ? narrowed : base;
        }

        // 3) 没有作用域前缀的结构性错误
        return locateStructural(text, sections, rest);
    }

    /** 在基础区间内尝试缩小：位置 → 名字 → 引文。 */
    private static int[] narrow(String text, int[] base, String message, boolean layer) {
        // (a) 词法/语法错误自带 "at position N"（相对表达式部分；环层表达式除外）
        if (layer && !message.startsWith("Cyclic layer:")) {
            Matcher m = AT_POSITION.matcher(message);
            if (m.find()) {
                int position = parseNumber(m.group(1));
                if (position >= 0) {
                    int exprStart = expressionStart(text, base);
                    int at = exprStart + position;
                    if (at >= base[0] && at < base[1]) return tokenRange(text, at, base[1]);
                }
            }
        }

        // (b) 名字类消息
        int[] named = wordRange(text, base, UNKNOWN_VARIABLE.matcher(message));
        if (named == null) named = wordRange(text, base, UNKNOWN_BLOCK.matcher(message));
        if (named == null) named = wordRange(text, base, UNKNOWN_FUNCTION.matcher(message));
        if (named == null) named = wordRange(text, base, UNKNOWN_DIRECTIVE.matcher(message));
        if (named != null) return named;

        // (c) 引文（先单引号：多为函数/标记名；单引号里的一两个字符多为运算符，已由长度过滤跳过）
        String quoted = firstGroup(message, SINGLE_QUOTED);
        if (quoted == null) quoted = firstGroup(message, DOUBLE_QUOTED);
        if (quoted != null) {
            if (quoted.endsWith("...")) quoted = quoted.substring(0, quoted.length() - 3);
            quoted = quoted.trim();
            if (!quoted.isEmpty()) {
                int idx = text.indexOf(quoted, base[0]);
                if (idx >= base[0] && idx + quoted.length() <= base[1]) {
                    return new int[] {idx, idx + quoted.length()};
                }
            }
        }
        return null;
    }

    private static int[] locateStructural(String text, List<Section> sections, String message) {
        if (message.startsWith("Formula is empty") || message.startsWith("Formula contains no")
                || message.startsWith("Formula exceeds")) {
            return null; // 整体性问题没有可指的局部
        }
        if (message.startsWith("Unexpected text outside dimension sections")) {
            String quoted = firstGroup(message, DOUBLE_QUOTED);
            if (quoted != null) {
                String needle = quoted.endsWith("...") ? quoted.substring(0, quoted.length() - 3) : quoted;
                int idx = text.indexOf(needle);
                if (idx >= 0) return new int[] {idx, idx + needle.length()};
            }
            return null;
        }
        if (message.startsWith("Unbalanced '{'")) {
            int depth = 0;
            int open = -1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    if (depth == 0) open = i;
                    depth++;
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                }
            }
            return depth > 0 && open >= 0 ? new int[] {open, text.length()} : null;
        }
        if (message.startsWith("Unknown dimension")) {
            String quoted = firstGroup(message, DOUBLE_QUOTED);
            if (quoted != null) {
                for (Section s : sections) {
                    if (s.name().equals(quoted.trim())) {
                        int idx = text.indexOf(quoted, s.open() + 1);
                        if (idx >= 0 && (s.eq() < 0 || idx < s.eq())) return new int[] {idx, idx + quoted.length()};
                        return new int[] {s.open() + 1, s.close()};
                    }
                }
                int idx = text.indexOf(quoted);
                if (idx >= 0) return new int[] {idx, idx + quoted.length()};
            }
            return null;
        }
        if (message.startsWith("Dimension section is missing '='")) {
            for (Section s : sections) {
                if (s.eq() < 0) return new int[] {s.open() + 1, s.close()};
            }
            return null;
        }
        if (message.startsWith("Dimension section is missing a name")) {
            for (Section s : sections) {
                if (s.eq() >= 0 && s.name().isEmpty()) {
                    return new int[] {s.open(), Math.max(s.open() + 1, s.eq() + 1)};
                }
            }
            return null;
        }
        return null;
    }

    // ---------------------------------------------------------------- 片段查找

    private static int[] wordRange(String text, int[] base, Matcher matcher) {
        if (!matcher.find()) return null;
        String needle = matcher.group(1);
        if (needle == null || needle.isEmpty()) return null;
        int idx = text.indexOf(needle, base[0]);
        if (idx < base[0] || idx + needle.length() > base[1]) return null;
        return new int[] {idx, idx + needle.length()};
    }

    /** 从 at 开始，按字符类别扩展到一个小片段（词最多 32 字符，符号取 1 个）。 */
    private static int[] tokenRange(String text, int at, int limit) {
        int end = at + 1;
        if (isWordChar(text.charAt(at))) {
            while (end < limit && isWordChar(text.charAt(end)) && end - at < 32) end++;
        }
        return new int[] {at, end};
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 层错误里的 "at position N" 相对表达式部分，这里按解析器的规则找表达式的起点。 */
    private static int expressionStart(String text, int[] segment) {
        int i = segment[0];
        while (i < segment[1] && Character.isWhitespace(text.charAt(i))) i++;
        int colon = findColon(text, i, segment[1]);
        if (colon < 0) return i;
        int j = colon + 1;
        while (j < segment[1] && Character.isWhitespace(text.charAt(j))) j++;
        return j;
    }

    /** 与 FormulaParser.findColon 同规则：跳过括号内的冒号，三元表达式的 '?' 之后不再当分隔符。 */
    private static int findColon(String text, int from, int to) {
        int balance = 0;
        boolean inTernary = false;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '(') balance++;
            else if (c == ')') balance--;
            else if (c == '?' && balance == 0) inTernary = true;
            else if (c == ':' && balance == 0 && !inTernary) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 节与层扫描

    private record Section(int open, int close, int eq, String name) {}

    private static List<Section> scanSections(String text) {
        List<Section> out = new ArrayList<>();
        int depth = 0;
        int open = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                if (depth == 0) open = i;
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && open >= 0) out.add(buildSection(text, open, i));
                }
            }
        }
        return out;
    }

    private static Section buildSection(String text, int open, int close) {
        int eq = text.indexOf('=', open + 1);
        if (eq < 0 || eq >= close) eq = -1;
        int nameStart = open + 1;
        int nameEnd = eq >= 0 ? eq : close;
        while (nameStart < nameEnd && Character.isWhitespace(text.charAt(nameStart))) nameStart++;
        while (nameEnd > nameStart && Character.isWhitespace(text.charAt(nameEnd - 1))) nameEnd--;
        return new Section(open, close, eq, text.substring(nameStart, nameEnd));
    }

    private static Section findSection(List<Section> sections, String canonical, boolean preferLast) {
        Section found = null;
        for (Section s : sections) {
            if (canonical.equals(canonicalDimension(s.name()))) {
                if (!preferLast) return s;
                found = s;
            }
        }
        return found;
    }

    private static String canonicalDimension(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.startsWith("minecraft:")) n = n.substring("minecraft:".length());
        return switch (n) {
            case "overworld" -> "overworld";
            case "nether", "the_nether" -> "the_nether";
            case "end", "the_end" -> "the_end";
            default -> null;
        };
    }

    /** 节内第 index 段（0 基）：先跳过节内容开头的指令，再按 ';'（大括号深度 0）分段。 */
    private static int[] sectionLayerSegment(String text, Section section, int index) {
        if (section.eq() < 0) return null;
        int pos = section.eq() + 1;
        int end = section.close();
        while (true) {
            while (pos < end && Character.isWhitespace(text.charAt(pos))) pos++;
            if (pos >= end || text.charAt(pos) != '[') break;
            int closeBracket = text.indexOf(']', pos + 1);
            if (closeBracket < 0 || closeBracket > end) return null;
            pos = closeBracket + 1;
        }
        return segmentAt(text, pos, end, index);
    }

    private static int[] segmentAt(String text, int from, int to, int index) {
        if (index < 0 || from >= to) return null;
        int start = from;
        int depth = 0;
        int seen = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ';' && depth == 0) {
                if (seen == index) return new int[] {start, i};
                seen++;
                start = i + 1;
            }
        }
        return seen == index ? new int[] {start, to} : null;
    }

    // ---------------------------------------------------------------- 小工具

    private static String firstGroup(String message, Pattern pattern) {
        Matcher m = pattern.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    private static int parseNumber(String value) {
        if (value == null) return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 去掉 \r\n 的文本 + 下标回映表（与解析器预处理一致）。 */
    private record Cleaned(String text, int[] map) {
        static Cleaned of(String input) {
            StringBuilder sb = new StringBuilder(input.length());
            int[] map = new int[input.length() + 1];
            int c = 0;
            for (int i = 0; i < input.length(); i++) {
                char ch = input.charAt(i);
                if (ch == '\r' || ch == '\n') continue;
                map[c++] = i;
                sb.append(ch);
            }
            map[c] = input.length();
            return new Cleaned(sb.toString(), java.util.Arrays.copyOf(map, c + 1));
        }

        int[] toOriginal(int start, int end) {
            int s = this.map[Math.max(0, Math.min(start, this.map.length - 1))];
            int e = this.map[Math.max(0, Math.min(end, this.map.length - 1))];
            return new int[] {s, Math.max(s, e)};
        }
    }
}
