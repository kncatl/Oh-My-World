package com.kncatl.ohmyworld.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;

/**
 * 公式编辑器的文本模型：文本、光标、选区，以及按像素宽度换行的可视行布局。
 *
 * <p>纯逻辑实现，不依赖原版编辑框的任何内部结构：原版 {@code MultilineTextField}
 * 的换行数据（{@code StringView}）是 protected 嵌套类型，NeoForge 的编译类路径
 * 下无法访问，因此这里自带一套等价模型，顺带保证 14 个构建节点的行为完全一致。
 */
final class FormulaTextModel {

    /** 一个可视行在文本里的下标区间 [start, end)，不含行尾换行符。 */
    record Line(int start, int end) {}

    private final Font font;
    private final int wrapWidth;
    private final int[] asciiWidths = new int[128];
    private boolean asciiWidthsReady;

    private String value = "";
    private int cursor;
    private int anchor;
    private int characterLimit = Integer.MAX_VALUE;
    private Consumer<String> valueListener;

    private List<Line> lines = List.of(new Line(0, 0));

    FormulaTextModel(Font font, int wrapWidth) {
        this.font = font;
        this.wrapWidth = Math.max(1, wrapWidth);
    }

    // ---------------------------------------------------------------- 查询

    String value() {
        return this.value;
    }

    int cursor() {
        return this.cursor;
    }

    boolean hasSelection() {
        return this.cursor != this.anchor;
    }

    int selectionStart() {
        return Math.min(this.cursor, this.anchor);
    }

    int selectionEnd() {
        return Math.max(this.cursor, this.anchor);
    }

    /** 可视行列表（永不为空，至少一行）。 */
    List<Line> lines() {
        return this.lines;
    }

    /** 光标所在的可视行号。 */
    int cursorLine() {
        return lineOf(this.cursor);
    }

    /** 下标所在的可视行号：命中首个满足 start &lt;= index &lt;= end 的行。 */
    int lineOf(int index) {
        List<Line> ls = this.lines;
        for (int i = 0; i < ls.size(); i++) {
            Line line = ls.get(i);
            if (index >= line.start() && index <= line.end()) return i;
        }
        return ls.size() - 1;
    }

    /** 光标在自身行内距行首的像素宽度。 */
    int cursorX() {
        return xInLine(cursorLine(), this.cursor);
    }

    /** 第 lineIdx 行内，下标 index 距行首的像素宽度（超出按行尾计）。 */
    int xInLine(int lineIdx, int index) {
        Line line = this.lines.get(lineIdx);
        int x = 0;
        int end = Math.min(index, line.end());
        for (int i = line.start(); i < end; i++) x += charWidth(this.value.charAt(i));
        return x;
    }

    /** 第 lineIdx 行内，像素 x 处最接近的字符下标。 */
    int indexAt(int lineIdx, double x) {
        Line line = this.lines.get(lineIdx);
        int index = line.start();
        double acc = 0;
        while (index < line.end()) {
            int cw = charWidth(this.value.charAt(index));
            if (x < acc + cw / 2.0) return index;
            acc += cw;
            index++;
        }
        return line.end();
    }

    // ---------------------------------------------------------------- 修改

    void setCharacterLimit(int limit) {
        if (limit > 0) this.characterLimit = limit;
    }

    void setValueListener(Consumer<String> listener) {
        this.valueListener = listener;
    }

    void setValue(String newValue) {
        String v = newValue == null ? "" : newValue;
        if (v.length() > this.characterLimit) v = v.substring(0, this.characterLimit);
        this.value = v;
        this.cursor = v.length();
        this.anchor = this.cursor;
        rebuildLines();
        notifyValueChanged();
    }

    void insertText(String text) {
        if (text == null || text.isEmpty()) return;
        replaceSelection();
        int room = this.characterLimit - this.value.length();
        if (room <= 0) return;
        String inserted = text.length() > room ? text.substring(0, room) : text;
        this.value = this.value.substring(0, this.cursor) + inserted + this.value.substring(this.cursor);
        this.cursor += inserted.length();
        this.anchor = this.cursor;
        rebuildLines();
        notifyValueChanged();
    }

    /** 删除一格：direction &lt; 0 为退格，否则为 Delete；有选区时删除选区。 */
    void deleteText(int direction) {
        if (hasSelection()) {
            replaceSelection();
            rebuildLines();
            notifyValueChanged();
            return;
        }
        if (direction < 0) {
            if (this.cursor == 0) return;
            this.value = this.value.substring(0, this.cursor - 1) + this.value.substring(this.cursor);
            this.cursor--;
        } else {
            if (this.cursor >= this.value.length()) return;
            this.value = this.value.substring(0, this.cursor) + this.value.substring(this.cursor + 1);
        }
        this.anchor = this.cursor;
        rebuildLines();
        notifyValueChanged();
    }

    /** 按词删除：direction &lt; 0 为 Ctrl+退格，否则为 Ctrl+Delete。 */
    void deleteWord(int direction) {
        if (hasSelection()) {
            replaceSelection();
            rebuildLines();
            notifyValueChanged();
            return;
        }
        int from = direction < 0 ? previousWordStart(this.cursor) : this.cursor;
        int to = direction < 0 ? this.cursor : nextWordEnd(this.cursor);
        if (from >= to) return;
        this.value = this.value.substring(0, from) + this.value.substring(to);
        this.cursor = from;
        this.anchor = this.cursor;
        rebuildLines();
        notifyValueChanged();
    }

    void selectAll() {
        this.anchor = 0;
        this.cursor = this.value.length();
    }

    void select(int from, int to) {
        int n = this.value.length();
        this.anchor = Math.max(0, Math.min(from, n));
        this.cursor = Math.max(0, Math.min(to, n));
    }

    void moveTo(int index, boolean select) {
        this.cursor = Math.max(0, Math.min(index, this.value.length()));
        if (!select) this.anchor = this.cursor;
    }

    /** 水平移动：step 为 ±1；byWord 时按词跳转。 */
    void moveHorizontal(int step, boolean byWord, boolean select) {
        int target;
        if (byWord) {
            target = step < 0 ? previousWordStart(this.cursor) : nextWordEnd(this.cursor);
        } else {
            target = this.cursor + step;
        }
        moveTo(target, select);
    }

    /** 垂直移动若干可视行，尽量保持光标像素列。 */
    void moveVertical(int deltaLines, boolean select) {
        int lineIdx = cursorLine();
        int target = Math.max(0, Math.min(this.lines.size() - 1, lineIdx + deltaLines));
        int x = cursorX();
        moveTo(indexAt(target, x), select);
    }

    /** 行首/行尾；document 为整段文本的首尾。 */
    void moveToEdge(boolean end, boolean document, boolean select) {
        if (document) {
            moveTo(end ? this.value.length() : 0, select);
            return;
        }
        Line line = this.lines.get(cursorLine());
        moveTo(end ? line.end() : line.start(), select);
    }

    /** 返回 index 处单词的 [start, end)；不在词上时返回空区间。 */
    int[] wordAt(int index) {
        int n = this.value.length();
        if (index > 0 && (index >= n || !isWordChar(this.value.charAt(index)))) index--;
        if (index >= n || !isWordChar(this.value.charAt(index))) return new int[] {index, index};
        int start = index;
        int end = index + 1;
        while (start > 0 && isWordChar(this.value.charAt(start - 1))) start--;
        while (end < n && isWordChar(this.value.charAt(end))) end++;
        return new int[] {start, end};
    }

    // ---------------------------------------------------------------- 内部

    private void replaceSelection() {
        if (!hasSelection()) return;
        int from = selectionStart();
        int to = selectionEnd();
        this.value = this.value.substring(0, from) + this.value.substring(to);
        this.cursor = from;
        this.anchor = from;
    }

    private int previousWordStart(int index) {
        int i = index;
        while (i > 0 && !isWordChar(this.value.charAt(i - 1))) i--;
        while (i > 0 && isWordChar(this.value.charAt(i - 1))) i--;
        return i;
    }

    private int nextWordEnd(int index) {
        int i = index;
        int n = this.value.length();
        while (i < n && !isWordChar(this.value.charAt(i))) i++;
        while (i < n && isWordChar(this.value.charAt(i))) i++;
        return i;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void notifyValueChanged() {
        if (this.valueListener != null) this.valueListener.accept(this.value);
    }

    private int charWidth(char c) {
        if (c < 128) {
            if (!this.asciiWidthsReady) {
                for (int i = 0; i < 128; i++) this.asciiWidths[i] = this.font.width(String.valueOf((char) i));
                this.asciiWidthsReady = true;
            }
            return this.asciiWidths[c];
        }
        return this.font.width(String.valueOf(c));
    }

    /** 按宽度重建可视行：优先在最近空格处断行，超长无空格时硬断。 */
    private void rebuildLines() {
        List<Line> out = new ArrayList<>();
        int n = this.value.length();
        int hardStart = 0;
        while (true) {
            int hardEnd = this.value.indexOf('\n', hardStart);
            if (hardEnd < 0) hardEnd = n;
            wrapHardLine(out, hardStart, hardEnd);
            if (hardEnd >= n) break;
            hardStart = hardEnd + 1;
        }
        this.lines = out;
    }

    private void wrapHardLine(List<Line> out, int start, int end) {
        int lineStart = start;
        while (lineStart < end) {
            int i = lineStart;
            int width = 0;
            int lastSpace = -1;
            while (i < end) {
                char c = this.value.charAt(i);
                int cw = charWidth(c);
                if (width + cw > this.wrapWidth && i > lineStart) break;
                if (c == ' ') lastSpace = i;
                width += cw;
                i++;
            }
            if (i >= end) {
                out.add(new Line(lineStart, end));
                return;
            }
            if (lastSpace > lineStart) {
                out.add(new Line(lineStart, lastSpace));
                lineStart = lastSpace + 1;
            } else {
                out.add(new Line(lineStart, i));
                lineStart = i;
            }
        }
        out.add(new Line(lineStart, lineStart));
    }
}
