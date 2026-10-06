package com.kncatl.ohmyworld.client;

import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
//? >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

import com.kncatl.ohmyworld.compat.GuiCompat;

/**
 * 带语法高亮的多行公式编辑器。
 *
 * <p>文本状态（{@link FormulaTextModel}）与绘制完全自持：编辑行为、换行布局、
 * 命中测试共用同一套数据，因此 14 个构建节点表现一致；按键只转发成语义操作
 * （光标移动/选区/剪贴板），剪贴板走原版 {@code KeyboardHandler}。
 */
public class FormulaEditor extends AbstractWidget {

    private static final int PAD = 4;
    private static final int SCROLLBAR_W = 4;
    private static final int SCROLL_STEP_LINES = 2;

    private static final int COLOR_BG = 0xFF0A0A0A;
    private static final int COLOR_BORDER = 0xFF4A4A4A;
    private static final int COLOR_BORDER_FOCUSED = 0xFF6E6E6E;
    private static final int COLOR_PLACEHOLDER = 0xFF6E6E6E;
    private static final int COLOR_SELECTION = 0x7A3B6EA5;
    private static final int COLOR_CURSOR = 0xFFD0D0D0;
    private static final int COLOR_SCROLLBAR = 0xFF5A5A5A;
    private static final int COLOR_SCROLLBAR_HOVER = 0xFF9A9A9A;

    private static final int K_LEFT = InputConstants.KEY_LEFT;
    private static final int K_RIGHT = InputConstants.KEY_RIGHT;
    private static final int K_UP = InputConstants.KEY_UP;
    private static final int K_DOWN = InputConstants.KEY_DOWN;
    private static final int K_HOME = InputConstants.KEY_HOME;
    private static final int K_END = InputConstants.KEY_END;
    private static final int K_PAGE_UP = InputConstants.KEY_PAGEUP;
    private static final int K_PAGE_DOWN = InputConstants.KEY_PAGEDOWN;
    private static final int K_BACKSPACE = InputConstants.KEY_BACKSPACE;
    private static final int K_DELETE = InputConstants.KEY_DELETE;

    private final Font font;
    private final FormulaTextModel model;
    private final Component placeholder;
    private final int innerWidth;

    /** 视口第一条可见行的行号（滚动以整行为步长）。 */
    private int scrollLine;
    private boolean dragging;
    private boolean scrollDragging;
    /** 按住滑块时，光标相对滑块顶部的偏移；点轨道时为滑块高度一半（滑块居中跳过去）。 */
    private double scrollGrabOffset;
    private long focusedTime;

    public FormulaEditor(Font font, int x, int y, int width, int height,
                         Component placeholder, Component narration, int maxLength,
                         Consumer<String> responder) {
        super(x, y, width, height, narration);
        this.font = font;
        this.placeholder = placeholder;
        this.innerWidth = Math.max(1, width - PAD * 2 - SCROLLBAR_W - 1);
        this.model = new FormulaTextModel(font, this.innerWidth);
        this.model.setCharacterLimit(maxLength);
        if (responder != null) this.model.setValueListener(responder);
    }

    public String value() {
        return this.model.value();
    }

    public void setValue(String value) {
        this.model.setValue(value);
        this.scrollLine = 0;
    }

    public void setResponder(Consumer<String> responder) {
        this.model.setValueListener(responder);
    }

    // ---------------------------------------------------------------- 滚动

    private int lineHeight() {
        return this.font.lineHeight;
    }

    private int innerHeight() {
        return Math.max(1, getHeight() - PAD * 2);
    }

    private int visibleLines() {
        return Math.max(1, innerHeight() / lineHeight());
    }

    private int maxScrollLine() {
        return Math.max(0, this.model.lines().size() - visibleLines());
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, maxScrollLine()));
    }

    // ------------------------------------------------------------ 滚动条交互

    private int scrollbarX() {
        return getX() + getWidth() - PAD - SCROLLBAR_W;
    }

    private int trackTop() {
        return getY() + PAD;
    }

    private int trackHeight() {
        return Math.max(1, getHeight() - PAD * 2);
    }

    private int thumbHeight() {
        int count = Math.max(1, this.model.lines().size());
        int track = trackHeight();
        return Math.min(track, Math.max(8, (int) ((long) track * visibleLines() / count)));
    }

    private int thumbY() {
        int max = maxScrollLine();
        if (max <= 0) return trackTop();
        return trackTop() + (int) ((long) (trackHeight() - thumbHeight()) * this.scrollLine / max);
    }

    private boolean scrollbarVisibleNow() {
        return maxScrollLine() > 0;
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        if (!scrollbarVisibleNow()) return false;
        int sx = scrollbarX();
        return mouseX >= sx - 1 && mouseX <= sx + SCROLLBAR_W + 1
                && mouseY >= trackTop() - 1 && mouseY <= trackTop() + trackHeight() + 1;
    }

    /** 点击落在滚动条上时接管本次点击（按到滑块保持抓取偏移，点轨道则滑块居中跳过去）。 */
    private boolean startScrollbarDrag(double mouseX, double mouseY) {
        if (!isOverScrollbar(mouseX, mouseY)) return false;
        this.dragging = false;
        int thumbTop = thumbY();
        int thumbH = thumbHeight();
        if (mouseY >= thumbTop && mouseY <= thumbTop + thumbH) {
            this.scrollGrabOffset = mouseY - thumbTop;
        } else {
            this.scrollGrabOffset = thumbH / 2.0;
        }
        this.scrollDragging = true;
        scrollFromMouse(mouseY);
        return true;
    }

    private void scrollFromMouse(double mouseY) {
        int span = trackHeight() - thumbHeight();
        int max = maxScrollLine();
        if (span <= 0 || max <= 0) {
            this.scrollLine = 0;
            return;
        }
        double rel = mouseY - trackTop() - this.scrollGrabOffset;
        this.scrollLine = clampScroll((int) Math.round(rel / span * max));
    }

    /** 光标行保持可见。 */
    private void scrollToCursor() {
        int line = this.model.cursorLine();
        int visible = visibleLines();
        if (line < this.scrollLine) this.scrollLine = line;
        else if (line >= this.scrollLine + visible) this.scrollLine = line - visible + 1;
        this.scrollLine = clampScroll(this.scrollLine);
    }

    /** 屏幕坐标 → 文本下标。 */
    private int indexAt(double mouseX, double mouseY) {
        List<FormulaTextModel.Line> lines = this.model.lines();
        int lineH = lineHeight();
        int localY = (int) Math.floor(mouseY - (getY() + PAD)) + this.scrollLine * lineH;
        int lineIdx = Math.max(0, Math.min(lines.size() - 1, localY / lineH));
        return this.model.indexAt(lineIdx, mouseX - (getX() + PAD));
    }

    @Override
    public void playDownSound(SoundManager soundManager) {
        // 文本编辑器不发出按钮点击音
    }

    @Override
    public void setFocused(boolean focused) {
        boolean wasFocused = isFocused();
        super.setFocused(focused);
        if (focused && !wasFocused) this.focusedTime = System.currentTimeMillis();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, this.createNarrationMessage());
    }

    // ---------------------------------------------------------------- 键盘

    private record KeyInput(int key, boolean ctrl, boolean shift, boolean enter,
                            boolean selectAll, boolean copy, boolean paste, boolean cut) {}

    private boolean handleKey(KeyInput in) {
        if (in.enter()) {
            if (in.ctrl()) return false; // Ctrl+Enter 由屏幕处理（完成 / 全屏写回）
            this.model.insertText("\n");
            scrollToCursor();
            return true;
        }
        if (in.selectAll()) {
            this.model.selectAll();
            scrollToCursor();
            return true;
        }
        if (in.copy()) {
            copySelection();
            return true;
        }
        if (in.cut()) {
            cutSelection();
            scrollToCursor();
            return true;
        }
        if (in.paste()) {
            pasteClipboard();
            scrollToCursor();
            return true;
        }

        int key = in.key();
        if (key == K_LEFT) {
            this.model.moveHorizontal(-1, in.ctrl(), in.shift());
        } else if (key == K_RIGHT) {
            this.model.moveHorizontal(1, in.ctrl(), in.shift());
        } else if (key == K_UP) {
            this.model.moveVertical(-1, in.shift());
        } else if (key == K_DOWN) {
            this.model.moveVertical(1, in.shift());
        } else if (key == K_HOME) {
            this.model.moveToEdge(false, in.ctrl(), in.shift());
        } else if (key == K_END) {
            this.model.moveToEdge(true, in.ctrl(), in.shift());
        } else if (key == K_PAGE_UP) {
            this.model.moveVertical(-visibleLines(), in.shift());
        } else if (key == K_PAGE_DOWN) {
            this.model.moveVertical(visibleLines(), in.shift());
        } else if (key == K_BACKSPACE) {
            if (in.ctrl()) this.model.deleteWord(-1);
            else this.model.deleteText(-1);
        } else if (key == K_DELETE) {
            if (in.ctrl()) this.model.deleteWord(1);
            else this.model.deleteText(1);
        } else {
            return false;
        }
        scrollToCursor();
        return true;
    }

    private void copySelection() {
        if (!this.model.hasSelection()) return;
        String selected = this.model.value().substring(this.model.selectionStart(), this.model.selectionEnd());
        Minecraft.getInstance().keyboardHandler.setClipboard(selected);
    }

    private void cutSelection() {
        if (!this.model.hasSelection()) return;
        copySelection();
        this.model.deleteText(-1); // 有选区时删除选区
    }

    private void pasteClipboard() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip != null && !clip.isEmpty()) this.model.insertText(clip);
    }

    // ---------------------------------------------------------------- 事件

    //? >=1.21.11 {
    @Override
    public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (startScrollbarDrag(event.x(), event.y())) return;
        this.dragging = true;
        int index = indexAt(event.x(), event.y());
        if (doubled) {
            int[] word = this.model.wordAt(index);
            this.model.select(word[0], word[1]);
        } else {
            this.model.moveTo(index, event.hasShiftDown());
        }
        scrollToCursor();
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (this.scrollDragging) {
            scrollFromMouse(event.y());
            return true;
        }
        if (this.dragging) {
            this.model.moveTo(indexAt(event.x(), event.y()), true);
            scrollToCursor();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        this.dragging = false;
        this.scrollDragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return handleKey(new KeyInput(event.key(), event.hasControlDown(), event.hasShiftDown(),
                event.isConfirmation(), event.isSelectAll(), event.isCopy(), event.isPaste(), event.isCut()));
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!this.visible || !isFocused() || !event.isAllowedChatCharacter()) return false;
        this.model.insertText(event.codepointAsString());
        scrollToCursor();
        return true;
    }
    //?} else {
    @Override
    public void onClick(double mouseX, double mouseY) {
        if (startScrollbarDrag(mouseX, mouseY)) return;
        this.dragging = true;
        this.model.moveTo(indexAt(mouseX, mouseY), net.minecraft.client.gui.screens.Screen.hasShiftDown());
        scrollToCursor();
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.scrollDragging) {
            scrollFromMouse(mouseY);
            return true;
        }
        if (this.dragging && button == 0) {
            this.model.moveTo(indexAt(mouseX, mouseY), true);
            scrollToCursor();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.dragging = false;
        this.scrollDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return handleKey(new KeyInput(keyCode,
                net.minecraft.client.gui.screens.Screen.hasControlDown(),
                net.minecraft.client.gui.screens.Screen.hasShiftDown(),
                keyCode == InputConstants.KEY_RETURN,
                net.minecraft.client.gui.screens.Screen.isSelectAll(keyCode),
                net.minecraft.client.gui.screens.Screen.isCopy(keyCode),
                net.minecraft.client.gui.screens.Screen.isPaste(keyCode),
                net.minecraft.client.gui.screens.Screen.isCut(keyCode)));
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.visible || !isFocused() || !StringUtil.isAllowedChatCharacter(codePoint)) return false;
        this.model.insertText(String.valueOf(codePoint));
        scrollToCursor();
        return true;
    }
    //?}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!this.active || !this.visible || verticalAmount == 0) return false;
        int delta = verticalAmount > 0 ? -SCROLL_STEP_LINES : SCROLL_STEP_LINES;
        this.scrollLine = clampScroll(this.scrollLine + delta);
        return true;
    }

    // ---------------------------------------------------------------- 绘制

    //? >=26.1 {
    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawEditor(GuiCompat.of(graphics), mouseX, mouseY);
    }
    //?} else {
    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawEditor(GuiCompat.of(graphics), mouseX, mouseY);
    }
    //?}

    private void drawEditor(GuiCompat.Draw t, int mouseX, int mouseY) {
        this.scrollLine = clampScroll(this.scrollLine);
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        t.fill(x, y, x + w, y + h, isFocused() ? COLOR_BORDER_FOCUSED : COLOR_BORDER);
        t.fill(x + 1, y + 1, x + w - 1, y + h - 1, COLOR_BG);

        int ix = x + PAD;
        int iy = y + PAD;
        int ih = innerHeight();
        String value = this.model.value();

        t.pushClip(ix, iy, ix + this.innerWidth, iy + ih);
        try {
            if (value.isEmpty() && !isFocused()) {
                drawPlaceholder(t, ix, iy, ih);
            } else {
                drawSelection(t, ix, iy);
                drawText(t, value, ix, iy);
                drawCursor(t, ix, iy);
            }
        } finally {
            t.popClip();
        }
        drawScrollbar(t, mouseX, mouseY);
    }

    private void drawPlaceholder(GuiCompat.Draw t, int ix, int iy, int ih) {
        String text = this.placeholder.getString();
        int lineH = lineHeight();
        int start = 0;
        int y = iy;
        while (start < text.length() && y + lineH <= iy + ih) {
            int width = 0;
            int end = start;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (c == '\n') break;
                int cw = this.font.width(String.valueOf(c));
                if (width + cw > this.innerWidth && end > start) break;
                width += cw;
                end++;
            }
            t.text(this.font, text.substring(start, end), ix, y, COLOR_PLACEHOLDER);
            start = end < text.length() && text.charAt(end) == '\n' ? end + 1 : Math.max(end, start + 1);
            y += lineH;
        }
    }

    private void drawSelection(GuiCompat.Draw t, int ix, int iy) {
        if (!this.model.hasSelection()) return;
        int from = this.model.selectionStart();
        int to = this.model.selectionEnd();
        int lineH = lineHeight();
        List<FormulaTextModel.Line> lines = this.model.lines();
        for (int i = this.scrollLine; i < lines.size() && i <= this.scrollLine + visibleLines(); i++) {
            FormulaTextModel.Line line = lines.get(i);
            int s = Math.max(from, line.start());
            int e = Math.min(to, line.end());
            if (s >= e) continue;
            int y = iy + (i - this.scrollLine) * lineH;
            int x1 = ix + this.model.xInLine(i, s);
            int x2 = ix + this.model.xInLine(i, e);
            t.fill(x1, y - 1, x2, y + lineH + 1, COLOR_SELECTION);
        }
    }

    private void drawText(GuiCompat.Draw t, String value, int ix, int iy) {
        List<FormulaHighlighter.Span> spans = FormulaHighlighter.spans(value);
        int lineH = lineHeight();
        List<FormulaTextModel.Line> lines = this.model.lines();
        for (int i = this.scrollLine; i < lines.size() && i <= this.scrollLine + visibleLines(); i++) {
            FormulaTextModel.Line line = lines.get(i);
            if (line.start() >= line.end()) continue;
            int y = iy + (i - this.scrollLine) * lineH;
            drawLine(t, value, line, spans, ix, y);
        }
    }

    private void drawLine(GuiCompat.Draw t, String value, FormulaTextModel.Line line,
                          List<FormulaHighlighter.Span> spans, int ix, int y) {
        int begin = line.start();
        int end = line.end();
        int x = ix;
        int pos = begin;
        for (FormulaHighlighter.Span span : spans) {
            if (span.end() <= pos) continue;
            if (span.start() >= end) break;
            int s = Math.max(span.start(), pos);
            int e = Math.min(span.end(), end);
            if (s >= e) continue;
            if (s > pos) x = drawRun(t, value, pos, s, x, y, FormulaHighlighter.COLOR_DEFAULT);
            x = drawRun(t, value, s, e, x, y, span.color());
            pos = e;
        }
        if (pos < end) drawRun(t, value, pos, end, x, y, FormulaHighlighter.COLOR_DEFAULT);
    }

    private int drawRun(GuiCompat.Draw t, String value, int from, int to, int x, int y, int color) {
        String text = value.substring(from, to);
        t.text(this.font, text, x, y, color);
        return x + this.font.width(text);
    }

    private void drawCursor(GuiCompat.Draw t, int ix, int iy) {
        if (!isFocused()) return;
        long phase = System.currentTimeMillis() - this.focusedTime;
        if (phase > 350 && (phase / 350) % 2 == 1) return;
        int lineH = lineHeight();
        int y = iy + (this.model.cursorLine() - this.scrollLine) * lineH;
        int cx = ix + this.model.cursorX();
        t.fill(cx, y - 1, cx + 1, y + lineH + 1, COLOR_CURSOR);
    }

    private void drawScrollbar(GuiCompat.Draw t, int mouseX, int mouseY) {
        if (!scrollbarVisibleNow()) return;
        boolean active = this.scrollDragging || isOverScrollbar(mouseX, mouseY);
        int sx = scrollbarX();
        int top = thumbY();
        t.fill(sx, top, sx + SCROLLBAR_W, top + thumbHeight(),
                active ? COLOR_SCROLLBAR_HOVER : COLOR_SCROLLBAR);
    }
}
