package com.kncatl.ohmyworld.client;

import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
//? >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;

import com.kncatl.ohmyworld.FormulaParser;
import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.compat.ClientScreens;
import com.kncatl.ohmyworld.compat.FileOpen;
import com.kncatl.ohmyworld.compat.GuiCompat;

public class CustomFlatScreen extends Screen implements PresetEditor {

    private static final int CARD_MAX_W = 420;
    private static final int CARD_H = 272;
    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int OK_COLOR = 0xFF7FE07F;
    private static final int WARN_COLOR = 0xFFFFAA00;
    private static final int MUTED_COLOR = 0xFF9A9A9A;

    private final CreateWorldScreen parent;
    private EditBox layersInput;
    private EditBox nameInput;
    private Button saveBtn;
    private Button loadBtn;
    private Button doneBtn;
    private int cardX;
    private int cardY;
    private int cardW;
    private int cardH;
    private int formulaY;
    private int nameY;
    private int statusY;
    private List<String> currentErrors = new ArrayList<>();
    private FormulaParser.DimensionParseResult currentResult;
    private Component statusMessage;
    private int statusColor = OK_COLOR;
    private long statusUntil;
    private String pendingFormula;
    private String pendingName;

    private static final Component TITLE = Component.translatable("ohmyworld.custom_screen.title");
    private static final Component HINT = Component.translatable("ohmyworld.custom_screen.layers");
    private static final Component FORMULA_LABEL = Component.translatable("ohmyworld.custom_screen.formula_label");
    private static final Component NAME_LABEL = Component.translatable("ohmyworld.custom_screen.name_label");
    private static final Component NAME_HINT = Component.translatable("ohmyworld.custom_screen.name_hint");
    private static final Component OPEN_GUIDE = Component.translatable("ohmyworld.custom_screen.open_guide");
    private static final Component DONE = Component.translatable("ohmyworld.custom_screen.done");
    private static final Component CANCEL = Component.translatable("ohmyworld.custom_screen.cancel");
    private static final Component SAVE = Component.translatable("ohmyworld.custom_screen.save");
    private static final Component LOAD = Component.translatable("ohmyworld.custom_screen.load");
    private static final Component LOAD_TITLE = Component.translatable("ohmyworld.custom_screen.load_title");
    private static final Component NO_SAVES = Component.translatable("ohmyworld.custom_screen.no_saves");
    private static final Component EMPTY = Component.translatable("ohmyworld.custom_screen.empty");

    public CustomFlatScreen(CreateWorldScreen parent, WorldCreationContext context) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    public Screen createEditScreen(CreateWorldScreen lastScreen, WorldCreationContext context) {
        return new CustomFlatScreen(lastScreen, context);
    }

    @Override
    protected void init() {
        this.cardW = Math.min(CARD_MAX_W, this.width - 16);
        this.cardH = Math.min(CARD_H, Math.max(220, this.height - 8));
        this.cardX = (this.width - this.cardW) / 2;
        this.cardY = Math.max(4, (this.height - this.cardH) / 2);
        int inner = this.cardW - 32;
        int fx = this.cardX + 16;

        // 窗口偏矮时压缩公式框（其余行依次上移），保证按钮始终在屏内
        int formulaH = Math.max(26, 56 - (CARD_H - this.cardH));
        this.formulaY = this.cardY + 66;
        this.layersInput = new EditBox(this.font, fx, this.formulaY, inner, formulaH, FORMULA_LABEL);
        this.layersInput.setMaxLength(FormulaParser.MAX_INPUT_LENGTH);
        this.layersInput.setValue(pendingFormula != null ? pendingFormula : PatternData.getRawInput());
        this.layersInput.setResponder(t -> validate());
        this.addRenderableWidget(this.layersInput);

        this.nameY = this.formulaY + formulaH + 16;
        this.nameInput = new EditBox(this.font, fx, this.nameY, inner, 20, NAME_LABEL);
        this.nameInput.setMaxLength(64);
        this.nameInput.setHint(NAME_HINT);
        if (pendingName != null) this.nameInput.setValue(pendingName);
        this.nameInput.setResponder(t -> updateButtonState());
        this.addRenderableWidget(this.nameInput);

        this.statusY = this.nameY + 40;

        this.addRenderableWidget(Button.builder(OPEN_GUIDE, b -> openGuide())
                .bounds(this.cardX + this.cardW - 16 - 76, this.cardY + 26, 76, 20).build());

        int btnY = this.cardY + this.cardH - 30;
        int gap = 6;
        int btnW = Math.max(50, Math.min(80, (inner - 3 * gap - 10) / 4));
        int doneW = Math.min(btnW + 10, inner - 3 * (btnW + gap));
        int right = this.cardX + this.cardW - 16;
        this.doneBtn = Button.builder(DONE, b -> onDone()).bounds(right - doneW, btnY, doneW, 20).build();
        this.loadBtn = Button.builder(LOAD, b -> openLoadList())
                .bounds(right - doneW - gap - btnW, btnY, btnW, 20).build();
        this.saveBtn = Button.builder(SAVE, b -> onSave())
                .bounds(right - doneW - 2 * (gap + btnW), btnY, btnW, 20).build();
        this.addRenderableWidget(this.saveBtn);
        this.addRenderableWidget(this.loadBtn);
        this.addRenderableWidget(this.doneBtn);
        this.addRenderableWidget(Button.builder(CANCEL, b -> onCancel())
                .bounds(fx, btnY, btnW, 20).build());

        updateButtonState();
        validate();

        this.pendingFormula = null;
        this.pendingName = null;
    }

    private void validate() {
        String input = this.layersInput.getValue();
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(input);
        this.currentResult = result;
        this.currentErrors = result.errors();
        updateButtonState();
    }

    private void updateButtonState() {
        boolean hasName = !this.nameInput.getValue().isBlank();
        boolean hasFormula = !this.layersInput.getValue().isBlank();
        this.saveBtn.active = hasName && hasFormula;
        this.doneBtn.active = hasFormula && this.currentErrors.isEmpty();
    }

    /** 短暂状态提示（保存/加载/打开指南的反馈）。 */
    private void setStatus(Component message, int color, long millis) {
        this.statusMessage = message;
        this.statusColor = color;
        this.statusUntil = System.currentTimeMillis() + millis;
    }

    private void onSave() {
        String name = sanitizeFileName(this.nameInput.getValue());
        String formula = this.layersInput.getValue();
        if (name.isEmpty() || formula.isBlank()) return;
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld");
            Files.createDirectories(dir);
            writeFormulaAtomically(dir.resolve(name + ".txt"), formula);
            setStatus(Component.translatable("ohmyworld.custom_screen.saved", name + ".txt"), OK_COLOR, 4000);
        } catch (Exception e) {
            String detail = e.getMessage() == null ? name : e.getMessage();
            setStatus(Component.translatable("ohmyworld.custom_screen.save_failed", detail), ERROR_COLOR, 6000);
        }
    }

    /** 打开公式指南：优先当前语言、其次另一语言、最后退回 ohmyworld 目录；在后台线程尝试，结果回主线程显示。 */
    private void openGuide() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld");
        String language = this.minecraft.options.languageCode;
        Path zh = dir.resolve("README_zh_cn.md");
        Path en = dir.resolve("README_en_us.md");
        Path preferred = language != null && language.startsWith("zh") ? zh : en;
        Path target = Files.exists(preferred, LinkOption.NOFOLLOW_LINKS) ? preferred
                : Files.exists(zh, LinkOption.NOFOLLOW_LINKS) ? zh
                : Files.exists(en, LinkOption.NOFOLLOW_LINKS) ? en
                : dir;
        Thread opener = new Thread(() -> {
            boolean ok = FileOpen.open(target);
            if (!ok && !target.equals(dir)) ok = FileOpen.open(dir);
            boolean opened = ok;
            Minecraft.getInstance().execute(() -> {
                if (opened) {
                    setStatus(Component.translatable("ohmyworld.custom_screen.guide_opened"), OK_COLOR, 2500);
                } else {
                    setStatus(Component.translatable("ohmyworld.custom_screen.guide_failed", dir.toString()),
                            WARN_COLOR, 8000);
                }
            });
        }, "ohmyworld-open-guide");
        opener.setDaemon(true);
        opener.start();
    }

    /** 去除路径分隔符与目录穿越片段，防止保存/加载时写出 ohmyworld 目录。 */
    private static String sanitizeFileName(String name) {
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        while (s.contains("..")) s = s.replace("..", "_");
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        if (s.isBlank() || s.equals(".")) s = "_";
        return s;
    }

    private List<String> listSavedFormulas() {
        List<String> names = new ArrayList<>();
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld");
            if (!Files.isDirectory(dir)) return names;
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".txt"))
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(p -> {
                            String fn = p.getFileName().toString();
                            names.add(fn.substring(0, fn.length() - 4));
                        });
            }
        } catch (Exception ignored) {}
        names.sort(String::compareTo);
        return names;
    }

    private void openLoadList() {
        List<String> saves = listSavedFormulas();
        if (saves.isEmpty()) {
            this.showScreen(new Screen(Component.translatable("ohmyworld.custom_screen.load_title")) {
                @Override
                protected void init() {
                    this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                            b -> CustomFlatScreen.this.showScreen(CustomFlatScreen.this)).bounds(this.width / 2 - 40, this.height / 2 + 10, 80, 20).build());
                }
                //? >=26.1 {
                @Override
                public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
                    super.extractRenderState(g, mx, my, pt);
                    GuiCompat.of(g).centered(this.font, NO_SAVES, this.width / 2, this.height / 2 - 10, 0xFFFF5555);
                }
                //?} else {
                @Override
                public void render(GuiGraphics g, int mx, int my, float pt) {
                    super.render(g, mx, my, pt);
                    GuiCompat.of(g).centered(this.font, NO_SAVES, this.width / 2, this.height / 2 - 10, 0xFFFF5555);
                }
                //?}
                @Override
                public void onClose() { CustomFlatScreen.this.showScreen(CustomFlatScreen.this); }
            });
            return;
        }

        this.showScreen(new Screen(LOAD_TITLE) {
            private int scrollOffset;
            private int contentHeight;

            @Override
            protected void init() {
                int bw = 240;
                int y = 40;
                this.contentHeight = 0;
                for (String name : saves) {
                    int btnY = y - this.scrollOffset;
                    if (btnY >= 24 && btnY <= this.height - 44) {
                        String displayName = name.length() > 30 ? name.substring(0, 27) + "..." : name;
                        this.addRenderableWidget(Button.builder(Component.literal(displayName), b -> {
                            loadFormula(name);
                            CustomFlatScreen.this.showScreen(CustomFlatScreen.this);
                        }).bounds(this.width / 2 - bw / 2, btnY, bw, 20).build());
                    }
                    y += 24;
                    this.contentHeight = y - 40;
                }
                this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        b -> CustomFlatScreen.this.showScreen(CustomFlatScreen.this)).bounds(this.width / 2 - 40, this.height - 28, 80, 20).build());
            }

            @Override
            public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
                int maxScroll = Math.max(0, this.contentHeight - (this.height - 76));
                int clamped = Math.max(0, Math.min(this.scrollOffset - (int) (verticalAmount * 24), maxScroll));
                if (clamped != this.scrollOffset) {
                    this.scrollOffset = clamped;
                    this.clearWidgets();
                    this.init();
                }
                return true;
            }

            @Override
            public void onClose() { CustomFlatScreen.this.showScreen(CustomFlatScreen.this); }
        });
    }

    private void loadFormula(String name) {
        try {
            Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld")
                    .resolve(sanitizeFileName(name) + ".txt");
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > FormulaParser.MAX_INPUT_LENGTH) return;
            String content = Files.readString(file);
            this.pendingFormula = content;
            this.pendingName = name;
            setStatus(Component.translatable("ohmyworld.custom_screen.loaded", name), OK_COLOR, 4000);
        } catch (Exception ignored) {}
    }

    private void onDone() {
        String input = this.layersInput.getValue();
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(input);
        if (!PatternData.setDimensions(result, input)) {
            // 存在解析/语义错误时禁止应用残缺地形
            this.currentResult = result;
            this.currentErrors = result.errors().isEmpty()
                    ? List.of("Formula contains no valid layers") : result.errors();
            updateButtonState();
            return;
        }

        if (!this.nameInput.getValue().isBlank()) onSave();
        this.showScreen(this.parent);
    }

    private void onCancel() { this.showScreen(this.parent); }

    private static void writeFormulaAtomically(Path target, String formula) throws Exception {
        if (Files.isSymbolicLink(target)) throw new IllegalStateException("symbolic-link target");
        Path parent = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, formula);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    //? >=26.1 {
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        drawCard(GuiCompat.of(graphics));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawOverlay(GuiCompat.of(graphics));
    }
    //?} else {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        drawCard(GuiCompat.of(graphics));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        drawOverlay(GuiCompat.of(graphics));
    }
    //?}

    /** 版本无关的切换屏幕入口（setScreen / setScreenAndShow 的差异收敛在 compat 里）。 */
    private void showScreen(Screen screen) {
        ClientScreens.show(this.minecraft, screen);
    }

    /** 背景层：卡片底板（在背景之后、控件之前绘制）。 */
    private void drawCard(GuiCompat.Draw t) {
        int x = this.cardX;
        int y = this.cardY;
        int w = this.cardW;
        int h = this.cardH;
        t.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x40000000);
        t.fill(x, y, x + w, y + h, 0xE6141414);
        t.fill(x, y, x + w, y + 1, 0xFF4A4A4A);
        t.fill(x, y + h - 1, x + w, y + h, 0xFF4A4A4A);
        t.fill(x, y, x + 1, y + h, 0xFF4A4A4A);
        t.fill(x + w - 1, y, x + w, y + h, 0xFF4A4A4A);
        t.fill(x + 12, y + 24, x + w - 12, y + 25, 0xFF333333);
    }

    /** 前景层：标题、标签与状态区（在控件之后绘制）。 */
    private void drawOverlay(GuiCompat.Draw t) {
        int fx = this.cardX + 16;
        t.centered(this.font, TITLE, this.cardX + this.cardW / 2, this.cardY + 8, 0xFFFFFFFF);
        t.text(this.font, HINT, fx, this.cardY + 32, 0xFFB8B8B8);
        t.text(this.font, FORMULA_LABEL, fx, this.formulaY - 12, MUTED_COLOR);
        t.text(this.font, NAME_LABEL, fx, this.nameY - 12, MUTED_COLOR);
        drawStatus(t, fx, this.statusY, this.cardW - 32);
    }

    /** 状态区：短暂提示 > 错误列表 > 空输入提示 > 解析摘要（+ 原版主世界提醒）。 */
    private void drawStatus(GuiCompat.Draw t, int x, int y, int width) {
        if (this.statusMessage != null && System.currentTimeMillis() < this.statusUntil) {
            t.text(this.font, this.statusMessage, x, y, this.statusColor);
            return;
        }
        this.statusMessage = null;

        if (!this.currentErrors.isEmpty()) {
            t.text(this.font, Component.translatable("ohmyworld.custom_screen.errors", this.currentErrors.size()),
                    x, y, ERROR_COLOR);
            int lineY = y + 12;
            int maxShow = Math.min(this.currentErrors.size(), 4);
            for (int i = 0; i < maxShow; i++) {
                String trimmed = this.font.plainSubstrByWidth(this.currentErrors.get(i), width);
                t.text(this.font, Component.literal(trimmed), x, lineY, ERROR_COLOR);
                lineY += 11;
            }
            if (this.currentErrors.size() > maxShow) {
                t.text(this.font, Component.literal("..."), x, lineY, ERROR_COLOR);
            }
            return;
        }

        if (this.layersInput.getValue().isBlank()) {
            t.text(this.font, EMPTY, x, y, MUTED_COLOR);
            return;
        }

        if (this.currentResult != null) {
            t.text(this.font, summaryLine(this.currentResult), x, y, OK_COLOR);
            if (this.currentResult.sectioned()
                    && !this.currentResult.dimensions().containsKey(FormulaParser.DIM_OVERWORLD)) {
                t.text(this.font, Component.translatable("ohmyworld.custom_screen.vanilla_overworld"),
                        x, y + 13, WARN_COLOR);
            }
        }
    }

    /** 「已识别 N 个维度 · 主世界 12 层 · 下界 5 层」。 */
    private static Component summaryLine(FormulaParser.DimensionParseResult result) {
        List<String> parts = new ArrayList<>();
        // Map.copyOf 不保证顺序：按固定维度顺序展示
        for (String dim : List.of(FormulaParser.DIM_OVERWORLD, FormulaParser.DIM_NETHER, FormulaParser.DIM_END)) {
            FormulaParser.ParsedDimension parsed = result.dimensions().get(dim);
            if (parsed == null) continue;
            Component name = Component.translatable("ohmyworld.dimension." + dim);
            Component part = Component.translatable("ohmyworld.custom_screen.dim_part",
                    name, parsed.layers().size());
            parts.add(part.getString());
        }
        return Component.translatable("ohmyworld.custom_screen.summary",
                result.dimensions().size(), String.join(" · ", parts));
    }

    @Override
    public void onClose() { this.showScreen(this.parent); }
}
