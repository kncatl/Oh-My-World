package com.kncatl.ohmyworld.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//? >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

import com.kncatl.ohmyworld.FormulaParser;
import com.kncatl.ohmyworld.compat.ClientScreens;
import com.kncatl.ohmyworld.compat.GuiCompat;
import com.kncatl.ohmyworld.compat.MultiLineBox;

/**
 * 全屏公式编辑器：把公式框放大到整屏，方便编辑多维度长公式。
 *
 * <p>「完成」把文本写回来源屏幕（不直接应用到世界；应用仍由主界面的「完成」负责），
 * 取消/Esc 丢弃本次修改。Ctrl+Enter 等同「完成」。
 */
public class FullFormulaScreen extends Screen {

    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int OK_COLOR = 0xFF7FE07F;
    private static final int MUTED_COLOR = 0xFF9A9A9A;
    // 旧事件分支（<1.21.11）的键码：GLFW 值；新事件分支用 isConfirmation()/hasControlDown() 由事件自适应
    private static final int KEY_ENTER = 257;   // GLFW_KEY_ENTER
    private static final int MOD_CONTROL = 2;   // GLFW_MOD_CONTROL

    private final CustomFlatScreen parent;
    private String initial;
    private MultiLineBox.Handle formulaBox;
    private int statusY;
    private List<String> errors = new ArrayList<>();
    private FormulaParser.DimensionParseResult result;

    private static final Component TITLE = Component.translatable("ohmyworld.custom_screen.fullscreen_title");
    private static final Component FORMULA_HINT = Component.translatable("ohmyworld.custom_screen.formula_hint");
    private static final Component DONE = Component.translatable("ohmyworld.custom_screen.done");
    private static final Component CANCEL = Component.translatable("ohmyworld.custom_screen.cancel");
    private static final Component EMPTY = Component.translatable("ohmyworld.custom_screen.empty");

    public FullFormulaScreen(CustomFlatScreen parent, String initial) {
        super(TITLE);
        this.parent = parent;
        this.initial = initial;
    }

    @Override
    protected void init() {
        // 窗口刷新会重新执行 init：保留未保存的编辑
        if (this.formulaBox != null) {
            this.initial = this.formulaBox.value();
        }
        int boxX = 16;
        int boxY = 30;
        int boxW = this.width - 32;
        int boxH = Math.max(40, this.height - 30 - 48);
        this.formulaBox = MultiLineBox.create(this.font, boxX, boxY, boxW, boxH,
                FORMULA_HINT, TITLE, FormulaParser.MAX_INPUT_LENGTH, t -> validate());
        this.formulaBox.setValue(this.initial == null ? "" : this.initial);
        this.addRenderableWidget(this.formulaBox.widget());

        this.statusY = this.height - 44;
        int btnY = this.height - 26;
        this.addRenderableWidget(Button.builder(CANCEL, b -> onClose())
                .bounds(this.width / 2 - 104, btnY, 100, 20).build());
        this.addRenderableWidget(Button.builder(DONE, b -> finish())
                .bounds(this.width / 2 + 4, btnY, 100, 20).build());

        validate();
    }

    private void validate() {
        if (this.formulaBox == null) return;
        String input = this.formulaBox.value();
        FormulaParser.DimensionParseResult parsed = FormulaParser.parseDimensionsWithErrors(input);
        this.result = parsed;
        this.errors = parsed.errors();
        this.formulaBox.setErrorSpans(FormulaErrorLocator.locate(input, this.errors));
    }

    /** 把文本写回来源屏幕并返回（来源屏幕会在 init 时读取 pendingFormula）。 */
    private void finish() {
        this.parent.setPendingFormula(this.formulaBox.value());
        ClientScreens.show(this.minecraft, this.parent);
    }

    //? >=1.21.11 {
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.isConfirmation() && event.hasControlDown()) {
            finish();
            return true;
        }
        return super.keyPressed(event);
    }
    //?} else {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == KEY_ENTER && (modifiers & MOD_CONTROL) != 0) {
            finish();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    //? >=26.1 {
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawStatus(GuiCompat.of(graphics));
    }
    //?} else {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        drawStatus(GuiCompat.of(graphics));
    }
    //?}

    /** 状态行：错误（计数 + 首条）> 空输入提示 > 解析摘要。 */
    private void drawStatus(GuiCompat.Draw t) {
        if (!this.errors.isEmpty()) {
            Component line = Component.translatable("ohmyworld.custom_screen.errors", this.errors.size())
                    .copy().append(" ").append(Component.literal(this.errors.get(0)));
            String trimmed = this.font.plainSubstrByWidth(line.getString(), this.width - 32);
            t.text(this.font, Component.literal(trimmed), 16, this.statusY, ERROR_COLOR);
            return;
        }
        if (this.formulaBox.value().isBlank()) {
            t.text(this.font, EMPTY, 16, this.statusY, MUTED_COLOR);
            return;
        }
        if (this.result != null) {
            t.text(this.font, CustomFlatScreen.summaryLine(this.result), 16, this.statusY, OK_COLOR);
        }
    }

    @Override
    public void onClose() {
        ClientScreens.show(this.minecraft, this.parent);
    }
}
