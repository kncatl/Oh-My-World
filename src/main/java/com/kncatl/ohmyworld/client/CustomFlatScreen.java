package com.kncatl.ohmyworld.client;

import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import com.kncatl.ohmyworld.compat.MultiLineBox;

public class CustomFlatScreen extends Screen implements PresetEditor {

    private static final int CARD_MAX_W = 640;
    private static final int CARD_H = 324;
    private static final int PREVIEW_CELL = 4;
    private static final int PREVIEW_PX = FormulaPreview.SIZE * PREVIEW_CELL;
    private static final int PREVIEW_BOX = PREVIEW_PX + 4;
    private static final int ERROR_COLOR = 0xFFFF5555;
    private static final int OK_COLOR = 0xFF7FE07F;
    private static final int WARN_COLOR = 0xFFFFAA00;
    private static final int MUTED_COLOR = 0xFF9A9A9A;
    private static final long PREVIEW_DEBOUNCE_MS = 500;
    private static final int PREVIEW_LIMIT = 30_000_000;
    private static final int[] PREVIEW_ZOOM_LEVELS = {1, 2, 4, 8, 16};
    /** Z 标尺占用的左侧宽度（视图框外）。 */
    private static final int PREVIEW_ZRULER_W = 36;
    /** 标尺文字在视图内的像素位置（横向/纵向各 4 个，均匀分布）。 */
    private static final int[] PREVIEW_X_RULER_PX = {PREVIEW_PX / 8, PREVIEW_PX * 3 / 8, PREVIEW_PX * 5 / 8, PREVIEW_PX * 7 / 8};
    private static final int[] PREVIEW_Z_RULER_PX = {PREVIEW_PX / 8, PREVIEW_PX * 3 / 8, PREVIEW_PX * 5 / 8, PREVIEW_PX * 7 / 8};
    private static final int KEY_ENTER = 257;   // GLFW_KEY_ENTER（26.x 编译路径不暴露 LWJGL，直接用数值）
    private static final int MOD_CONTROL = 2;   // GLFW_MOD_CONTROL

    /** 示例公式：名称文案键 + 公式内容（与指南示例保持一致）。 */
    private record Example(String nameKey, String formula) {}

    private static final List<Example> EXAMPLES = List.of(
            new Example("ohmyworld.custom_screen.example.checker",
                    "y=-64: minecraft:bedrock;y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete"),
            new Example("ohmyworld.custom_screen.example.grid3",
                    "y=-64..64: (floordiv(x,3)+floordiv(z,3))%2==0 ? minecraft:white_concrete : minecraft:gray_concrete"),
            new Example("ohmyworld.custom_screen.example.cycle",
                    "y=-64..64: 3*[minecraft:bedrock],2*[minecraft:dirt],1*[(x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete]"),
            new Example("ohmyworld.custom_screen.example.multi",
                    "{overworld=y=-64: minecraft:bedrock;y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete}"
                            + "{the_nether=y=0..5: minecraft:netherrack;y=6..120: seedhash(x, z, 1) < 0.5 ? minecraft:basalt : minecraft:blackstone}"));

    private final CreateWorldScreen parent;
    private MultiLineBox.Handle formulaBox;
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
    private boolean previewVisible;
    private int previewX;
    private int previewY;
    private List<String> currentErrors = new ArrayList<>();
    private FormulaParser.DimensionParseResult currentResult;
    private Component statusMessage;
    private int statusColor = OK_COLOR;
    private long statusUntil;
    private int[] previewColors;
    private int previewColorsCells;
    private String previewSelectedDim;
    private String previewComputedFor;
    private long previewDueAt;
    private boolean previewRunning;
    private Button switchBtn;
    private Button zoomOutBtn;
    private Button zoomInBtn;
    private Button resetBtn;
    private int previewCenterX;
    private int previewCenterZ;
    private int previewSpacing = 1;
    private boolean previewDragging;
    private double dragStartMouseX;
    private double dragStartMouseY;
    private int dragStartCenterX;
    private int dragStartCenterZ;
    private String pendingFormula;
    private String pendingName;

    private static final Component TITLE = Component.translatable("ohmyworld.custom_screen.title");
    private static final Component HINT = Component.translatable("ohmyworld.custom_screen.layers");
    private static final Component FORMULA_LABEL = Component.translatable("ohmyworld.custom_screen.formula_label");
    private static final Component FORMULA_HINT = Component.translatable("ohmyworld.custom_screen.formula_hint");
    private static final Component NAME_LABEL = Component.translatable("ohmyworld.custom_screen.name_label");
    private static final Component NAME_HINT = Component.translatable("ohmyworld.custom_screen.name_hint");
    private static final Component OPEN_GUIDE = Component.translatable("ohmyworld.custom_screen.open_guide");
    private static final Component EXAMPLES_BTN = Component.translatable("ohmyworld.custom_screen.examples");
    private static final Component EXAMPLES_TITLE = Component.translatable("ohmyworld.custom_screen.examples_title");
    private static final Component PREVIEW_SWITCH = Component.translatable("ohmyworld.custom_screen.preview_switch");
    private static final Component PREVIEW_RESET = Component.translatable("ohmyworld.custom_screen.preview_reset");
    private static final Component EXPAND = Component.translatable("ohmyworld.custom_screen.expand");
    private static final Component RENAME = Component.translatable("ohmyworld.custom_screen.rename");
    private static final Component DELETE = Component.translatable("ohmyworld.custom_screen.delete");
    private static final Component RENAME_TITLE = Component.translatable("ohmyworld.custom_screen.rename_title");
    private static final Component RENAME_HINT = Component.translatable("ohmyworld.custom_screen.rename_hint");
    private static final Component RENAME_EXISTS = Component.translatable("ohmyworld.custom_screen.rename_exists");
    private static final Component RENAME_INVALID = Component.translatable("ohmyworld.custom_screen.rename_invalid");
    private static final Component DELETE_TITLE = Component.translatable("ohmyworld.custom_screen.delete_title");
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
        int maxW = Math.min(CARD_MAX_W, this.width - 16);
        boolean wantPreview = maxW - 32 - (PREVIEW_BOX + PREVIEW_ZRULER_W + 12) >= 360;
        this.cardW = wantPreview ? maxW : Math.min(maxW, 480);
        this.cardH = Math.min(CARD_H, Math.max(220, this.height - 8));
        this.cardX = (this.width - this.cardW) / 2;
        this.cardY = Math.max(4, (this.height - this.cardH) / 2);

        int deficit = CARD_H - this.cardH;
        int formulaH = Math.max(36, 84 - deficit);
        this.formulaY = this.cardY + 66;
        this.previewVisible = wantPreview && this.formulaY + 238 <= this.cardY + this.cardH;
        int leftW = this.cardW - 32 - (this.previewVisible ? PREVIEW_BOX + 12 : 0);
        int fx = this.cardX + 16;

        this.formulaBox = MultiLineBox.create(this.font, fx, this.formulaY, leftW, formulaH,
                FORMULA_HINT, FORMULA_LABEL, FormulaParser.MAX_INPUT_LENGTH, t -> validate());
        this.formulaBox.setValue(pendingFormula != null ? pendingFormula : PatternData.getRawInput());
        this.addRenderableWidget(this.formulaBox.widget());

        this.nameY = this.formulaY + formulaH + 16;
        this.nameInput = new EditBox(this.font, fx, this.nameY, leftW, 20, NAME_LABEL);
        this.nameInput.setMaxLength(64);
        if (pendingName != null) this.nameInput.setValue(pendingName);
        this.nameInput.setResponder(t -> updateButtonState());
        this.addRenderableWidget(this.nameInput);

        this.statusY = this.nameY + 40;
        this.switchBtn = null;
        this.zoomOutBtn = null;
        this.zoomInBtn = null;
        this.resetBtn = null;
        if (this.previewVisible) {
            this.previewX = this.cardX + this.cardW - 16 - PREVIEW_BOX + 2;
            this.previewY = this.formulaY + 2;
            int gap = 4;
            int small = (PREVIEW_BOX - 2 * gap) / 3;
            int rulerY = this.previewY + PREVIEW_PX + 4; // X 标尺文字行（框外）
            int row1 = rulerY + 11;
            this.zoomOutBtn = Button.builder(Component.literal("−"), b -> adjustPreviewZoom(-1))
                    .bounds(this.previewX, row1, small, 20).build();
            this.zoomInBtn = Button.builder(Component.literal("＋"), b -> adjustPreviewZoom(1))
                    .bounds(this.previewX + small + gap, row1, small, 20).build();
            this.resetBtn = Button.builder(PREVIEW_RESET, b -> resetPreviewView())
                    .bounds(this.previewX + 2 * (small + gap), row1, small, 20).build();
            this.addRenderableWidget(this.zoomOutBtn);
            this.addRenderableWidget(this.zoomInBtn);
            this.addRenderableWidget(this.resetBtn);
            this.switchBtn = Button.builder(PREVIEW_SWITCH, b -> cyclePreviewDimension())
                    .bounds(this.previewX, row1 + 24, PREVIEW_BOX, 20).build();
            this.addRenderableWidget(this.switchBtn);
            updatePreviewButtons();
        }

        int right = this.cardX + this.cardW - 16;
        this.addRenderableWidget(Button.builder(EXPAND, b -> openFullscreen())
                .bounds(fx + 26, this.formulaY - 18, 56, 16).build());
        this.addRenderableWidget(Button.builder(OPEN_GUIDE, b -> openGuide())
                .bounds(right - 76, this.cardY + 26, 76, 20).build());
        this.addRenderableWidget(Button.builder(EXAMPLES_BTN, b -> openExamples())
                .bounds(right - 76 - 6 - 56, this.cardY + 26, 56, 20).build());

        int btnY = this.cardY + this.cardH - 30;
        int gap = 6;
        int btnW = Math.max(50, Math.min(80, (leftW - 3 * gap - 10) / 4));
        int doneW = Math.min(btnW + 10, leftW - 3 * (btnW + gap));
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
        if (this.formulaBox == null) return; // init 期间的早期回调（MultiLineEditBox.setValue 会立即触发监听器）
        String input = this.formulaBox.value();
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(input);
        this.currentResult = result;
        this.currentErrors = result.errors();
        this.previewDueAt = System.currentTimeMillis() + PREVIEW_DEBOUNCE_MS;
        // 预览维度选择：失效/未选时退回第一个可用维度；只有一个可用维度时隐藏切换按钮
        List<String> available = availablePreviewDimensions();
        if (this.previewSelectedDim == null || !available.contains(this.previewSelectedDim)) {
            this.previewSelectedDim = available.isEmpty() ? null : available.get(0);
            this.previewComputedFor = null;
        }
        if (this.switchBtn != null) {
            this.switchBtn.visible = available.size() > 1;
        }
        updateButtonState();
    }

    /** 当前公式里有内容的维度，按固定顺序返回。 */
    private List<String> availablePreviewDimensions() {
        List<String> dims = new ArrayList<>();
        if (this.currentResult == null) return dims;
        for (String dim : List.of(FormulaParser.DIM_OVERWORLD,
                FormulaParser.DIM_NETHER, FormulaParser.DIM_END)) {
            if (this.currentResult.dimensions().containsKey(dim)) dims.add(dim);
        }
        return dims;
    }

    /** 循环切换预览维度（主世界 → 下界 → 末地 → …），并立即重建预览。 */
    private void cyclePreviewDimension() {
        List<String> available = availablePreviewDimensions();
        if (available.size() < 2) return;
        int index = available.indexOf(this.previewSelectedDim);
        this.previewSelectedDim = available.get((index + 1) % available.size());
        invalidatePreview(true); // 旧维度的画面先清掉，避免误导
    }

    /** 预览缩放级别（1/2/4/8/16：每格代表的方块数）；direction 为 -1 放大 / +1 缩小。 */
    private void adjustPreviewZoom(int direction) {
        int index = 0;
        for (int i = 0; i < PREVIEW_ZOOM_LEVELS.length; i++) {
            if (PREVIEW_ZOOM_LEVELS[i] == this.previewSpacing) index = i;
        }
        int next = Math.max(0, Math.min(PREVIEW_ZOOM_LEVELS.length - 1, index + direction));
        if (PREVIEW_ZOOM_LEVELS[next] == this.previewSpacing) return;
        this.previewSpacing = PREVIEW_ZOOM_LEVELS[next];
        invalidatePreview(true);
        updatePreviewButtons();
    }

    /** 复位预览视野（回到原点、间距 1）。 */
    private void resetPreviewView() {
        this.previewCenterX = 0;
        this.previewCenterZ = 0;
        this.previewSpacing = 1;
        invalidatePreview(true);
        updatePreviewButtons();
    }

    /** 让预览立即重建；clearImage=true 时先清空旧图（缩放/复位/切维度用，避免比例误导）。 */
    private void invalidatePreview(boolean clearImage) {
        if (clearImage) this.previewColors = null;
        this.previewComputedFor = null;
        this.previewDueAt = System.currentTimeMillis();
    }

    private void updatePreviewButtons() {
        if (this.zoomOutBtn != null) this.zoomOutBtn.active = this.previewSpacing > PREVIEW_ZOOM_LEVELS[0];
        if (this.zoomInBtn != null) {
            this.zoomInBtn.active = this.previewSpacing < PREVIEW_ZOOM_LEVELS[PREVIEW_ZOOM_LEVELS.length - 1];
        }
    }

    private boolean isOverPreview(double mouseX, double mouseY) {
        return mouseX >= this.previewX - 2 && mouseX < this.previewX + PREVIEW_BOX - 2
                && mouseY >= this.previewY - 2 && mouseY < this.previewY + PREVIEW_PX + 2;
    }

    /** 鼠标拖拽平移：按拖动格数移动采样中心（内容跟随光标）。 */
    private void updatePreviewPan(double mouseX, double mouseY) {
        int cellPx = PREVIEW_PX / FormulaPreview.cellsFor(this.previewSpacing);
        double cellsX = (mouseX - this.dragStartMouseX) / cellPx;
        double cellsZ = (mouseY - this.dragStartMouseY) / cellPx;
        int newCenterX = clampPreviewCenter(
                this.dragStartCenterX - (int) Math.round(cellsX) * this.previewSpacing);
        int newCenterZ = clampPreviewCenter(
                this.dragStartCenterZ - (int) Math.round(cellsZ) * this.previewSpacing);
        if (newCenterX != this.previewCenterX || newCenterZ != this.previewCenterZ) {
            this.previewCenterX = newCenterX;
            this.previewCenterZ = newCenterZ;
            invalidatePreview(false); // 拖动时保留旧图，异步换成新图，避免闪烁
        }
    }

    private static int clampPreviewCenter(int value) {
        return Math.max(-PREVIEW_LIMIT, Math.min(PREVIEW_LIMIT, value));
    }

    private void updateButtonState() {
        // init 期间控件还没建齐时的回调直接跳过（init 末尾会统一刷新一次）
        if (this.nameInput == null || this.saveBtn == null || this.doneBtn == null || this.formulaBox == null) {
            return;
        }
        boolean hasName = !this.nameInput.getValue().isBlank();
        boolean hasFormula = !this.formulaBox.value().isBlank();
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
        String formula = this.formulaBox.value();
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

    /** 示例公式列表：点选后填入公式与名称。 */
    private void openExamples() {
        this.showScreen(new Screen(EXAMPLES_TITLE) {
            @Override
            protected void init() {
                int bw = 260;
                int y = 40;
                for (Example example : EXAMPLES) {
                    this.addRenderableWidget(Button.builder(Component.translatable(example.nameKey()), b -> {
                        pendingFormula = example.formula();
                        pendingName = Component.translatable(example.nameKey()).getString();
                        CustomFlatScreen.this.showScreen(CustomFlatScreen.this);
                    }).bounds(this.width / 2 - bw / 2, y, bw, 20).build());
                    y += 24;
                }
                this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        b -> CustomFlatScreen.this.showScreen(CustomFlatScreen.this))
                        .bounds(this.width / 2 - 40, this.height - 28, 80, 20).build());
            }

            @Override
            public void onClose() { CustomFlatScreen.this.showScreen(CustomFlatScreen.this); }
        });
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
                int nameW = 190;
                int smallW = 46;
                int gap = 4;
                int rowW = nameW + 2 * (gap + smallW);
                int x = this.width / 2 - rowW / 2;
                int y = 40;
                this.contentHeight = 0;
                for (String name : saves) {
                    int rowY = y - this.scrollOffset;
                    if (rowY >= 24 && rowY <= this.height - 44) {
                        this.addRenderableWidget(Button.builder(Component.literal(displaySaveLabel(name)), b -> {
                            loadFormula(name);
                            CustomFlatScreen.this.showScreen(CustomFlatScreen.this);
                        }).bounds(x, rowY, nameW, 20).build());
                        this.addRenderableWidget(Button.builder(RENAME, b -> openRename(name))
                                .bounds(x + nameW + gap, rowY, smallW, 20).build());
                        this.addRenderableWidget(Button.builder(DELETE, b -> confirmDelete(name))
                                .bounds(x + nameW + gap + smallW + gap, rowY, smallW, 20).build());
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

    private Path formulaFile(String name) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld")
                .resolve(sanitizeFileName(name) + ".txt");
    }

    /** 保存条目的显示名：名称 + 修改时间（名称按需截断）。 */
    private String displaySaveLabel(String name) {
        String time = "";
        try {
            time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .format(Files.getLastModifiedTime(formulaFile(name), LinkOption.NOFOLLOW_LINKS)
                            .toInstant().atZone(ZoneId.systemDefault()));
        } catch (Exception ignored) {}
        String suffix = time.isEmpty() ? "" : "  " + time;
        int maxName = 182 - this.font.width(suffix);
        String trimmed = name;
        if (maxName < 12) {
            trimmed = "…";
        } else if (this.font.width(trimmed) > maxName) {
            trimmed = this.font.plainSubstrByWidth(trimmed, maxName - 6) + "…";
        }
        return trimmed + suffix;
    }

    /** 重命名已保存的公式（非法名/重名会提示，不改动原文件）。 */
    private void openRename(String oldName) {
        this.showScreen(new Screen(RENAME_TITLE) {
            private EditBox input;
            private Component error;

            @Override
            protected void init() {
                this.input = new EditBox(this.font, this.width / 2 - 100, this.height / 2 - 10, 200, 20, RENAME_HINT);
                this.input.setMaxLength(64);
                this.input.setValue(oldName);
                this.addRenderableWidget(this.input);
                this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> apply())
                        .bounds(this.width / 2 - 104, this.height / 2 + 16, 100, 20).build());
                this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> back())
                        .bounds(this.width / 2 + 4, this.height / 2 + 16, 100, 20).build());
            }

            private void apply() {
                String newName = sanitizeFileName(this.input.getValue());
                if (newName.isBlank() || newName.equals("_")) {
                    this.error = RENAME_INVALID;
                    return;
                }
                if (newName.equals(oldName)) {
                    back();
                    return;
                }
                try {
                    Path from = formulaFile(oldName);
                    Path to = formulaFile(newName);
                    if (Files.isSymbolicLink(from) || !Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
                        back();
                        return;
                    }
                    if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                        this.error = RENAME_EXISTS;
                        return;
                    }
                    Files.move(from, to);
                    back();
                } catch (Exception e) {
                    this.error = Component.literal(String.valueOf(e.getMessage()));
                }
            }

            private void back() {
                CustomFlatScreen.this.openLoadList();
            }

            //? >=26.1 {
            @Override
            public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
                super.extractRenderState(g, mx, my, pt);
                if (this.error != null) {
                    GuiCompat.of(g).centered(this.font, this.error, this.width / 2, this.height / 2 - 28, 0xFFFF5555);
                }
            }
            //?} else {
            @Override
            public void render(GuiGraphics g, int mx, int my, float pt) {
                super.render(g, mx, my, pt);
                if (this.error != null) {
                    GuiCompat.of(g).centered(this.font, this.error, this.width / 2, this.height / 2 - 28, 0xFFFF5555);
                }
            }
            //?}

            @Override
            public void onClose() { back(); }
        });
    }

    /** 删除已保存的公式（确认后删除；拒绝符号链接）。 */
    private void confirmDelete(String name) {
        this.showScreen(new Screen(DELETE_TITLE) {
            @Override
            protected void init() {
                this.addRenderableWidget(Button.builder(Component.translatable("gui.yes"), b -> {
                    try {
                        Path file = formulaFile(name);
                        if (!Files.isSymbolicLink(file)) Files.deleteIfExists(file);
                    } catch (Exception ignored) {}
                    CustomFlatScreen.this.openLoadList();
                }).bounds(this.width / 2 - 104, this.height / 2 + 4, 100, 20).build());
                this.addRenderableWidget(Button.builder(Component.translatable("gui.no"),
                        b -> CustomFlatScreen.this.openLoadList())
                        .bounds(this.width / 2 + 4, this.height / 2 + 4, 100, 20).build());
            }

            //? >=26.1 {
            @Override
            public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
                super.extractRenderState(g, mx, my, pt);
                GuiCompat.of(g).centered(this.font,
                        Component.translatable("ohmyworld.custom_screen.delete_confirm", name),
                        this.width / 2, this.height / 2 - 24, 0xFFFFAA00);
            }
            //?} else {
            @Override
            public void render(GuiGraphics g, int mx, int my, float pt) {
                super.render(g, mx, my, pt);
                GuiCompat.of(g).centered(this.font,
                        Component.translatable("ohmyworld.custom_screen.delete_confirm", name),
                        this.width / 2, this.height / 2 - 24, 0xFFFFAA00);
            }
            //?}

            @Override
            public void onClose() { CustomFlatScreen.this.openLoadList(); }
        });
    }

    private void loadFormula(String name) {
        try {
            Path file = formulaFile(name);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > FormulaParser.MAX_INPUT_LENGTH) return;
            String content = Files.readString(file);
            this.pendingFormula = content;
            this.pendingName = name;
            setStatus(Component.translatable("ohmyworld.custom_screen.loaded", name), OK_COLOR, 4000);
        } catch (Exception ignored) {}
    }

    private void onDone() {
        String input = this.formulaBox.value();
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

    /** 打开全屏公式编辑器（完成后经 {@link #setPendingFormula} 写回）。 */
    private void openFullscreen() {
        this.showScreen(new FullFormulaScreen(this, this.formulaBox.value()));
    }

    /** 全屏编辑器的「完成」写回入口：写入后来源界面 init 时会重新读取并校验。 */
    void setPendingFormula(String text) {
        this.pendingFormula = text;
    }

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

    //? >=1.21.11 {
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == KEY_ENTER && (event.modifiers() & MOD_CONTROL) != 0) {
            if (this.doneBtn.active) onDone();
            return true;
        }
        return super.keyPressed(event);
    }
    //?} else {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == KEY_ENTER && (modifiers & MOD_CONTROL) != 0) {
            if (this.doneBtn.active) onDone();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    //? >=1.21.11 {
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        if (this.previewVisible && event.button() == 0 && isOverPreview(event.x(), event.y())) {
            this.previewDragging = true;
            this.dragStartMouseX = event.x();
            this.dragStartMouseY = event.y();
            this.dragStartCenterX = this.previewCenterX;
            this.dragStartCenterZ = this.previewCenterZ;
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (this.previewDragging) {
            updatePreviewPan(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (this.previewDragging && event.button() == 0) {
            this.previewDragging = false;
            updatePreviewPan(event.x(), event.y());
            return true;
        }
        return super.mouseReleased(event);
    }
    //?} else {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.previewVisible && button == 0 && isOverPreview(mouseX, mouseY)) {
            this.previewDragging = true;
            this.dragStartMouseX = mouseX;
            this.dragStartMouseY = mouseY;
            this.dragStartCenterX = this.previewCenterX;
            this.dragStartCenterZ = this.previewCenterZ;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.previewDragging) {
            updatePreviewPan(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.previewDragging && button == 0) {
            this.previewDragging = false;
            updatePreviewPan(mouseX, mouseY);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    //?}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.previewVisible && verticalAmount != 0 && isOverPreview(mouseX, mouseY)) {
            adjustPreviewZoom(verticalAmount > 0 ? -1 : 1); // 滚轮上 = 放大
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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

    /** 前景层：标题、标签、预览与状态区（在控件之后绘制）。 */
    private void drawOverlay(GuiCompat.Draw t) {
        int fx = this.cardX + 16;
        t.centered(this.font, TITLE, this.cardX + this.cardW / 2, this.cardY + 8, 0xFFFFFFFF);
        t.text(this.font, HINT, fx, this.cardY + 32, 0xFFB8B8B8);
        t.text(this.font, FORMULA_LABEL, fx, this.formulaY - 12, MUTED_COLOR);
        t.text(this.font, NAME_LABEL, fx, this.nameY - 12, MUTED_COLOR);
        // 名称框占位文字自绘：原版 EditBox 的 hint 颜色跨版本不一致（1.21.1 偏黑）
        if (this.nameInput.getValue().isEmpty() && !this.nameInput.isFocused()) {
            t.text(this.font, NAME_HINT, this.nameInput.getX() + 4, this.nameInput.getY() + 6, MUTED_COLOR);
        }
        tickPreview();
        drawPreview(t);
        drawStatus(t, fx, this.statusY, this.cardW - 32 - (this.previewVisible ? PREVIEW_BOX + 12 : 0));
    }

    /** 预览区（含外框、框外坐标标尺与标题）；未就绪时只画框与提示。 */
    private void drawPreview(GuiCompat.Draw t) {
        if (!this.previewVisible) return;
        int px = this.previewX;
        int py = this.previewY;
        t.fill(px - 2, py - 2, px + PREVIEW_PX + 2, py + PREVIEW_PX + 2, 0xFF4A4A4A);
        t.fill(px - 1, py - 1, px + PREVIEW_PX + 1, py + PREVIEW_PX + 1, 0xFF0A0A0A);
        String dim = this.previewSelectedDim;
        if (dim == null) dim = FormulaParser.DIM_OVERWORLD;
        String info = Component.translatable("ohmyworld.dimension." + dim).getString()
                + (this.previewSpacing > 1 ? " ×" + this.previewSpacing : "");
        Component caption = Component.translatable("ohmyworld.custom_screen.preview", info);
        t.text(this.font, caption, px + PREVIEW_BOX - this.font.width(caption), this.formulaY - 12, MUTED_COLOR);

        int cells = FormulaPreview.cellsFor(this.previewSpacing);
        int cellPx = PREVIEW_PX / cells;
        int[] colors = this.previewColors;
        if (colors == null || this.previewColorsCells != cells) {
            // 尚未计算（或缩放级别刚变化）：在框内给一行操作提示
            t.text(this.font, Component.translatable("ohmyworld.custom_screen.preview_hint"),
                    px + 6, py + PREVIEW_PX / 2 - 8, 0xFF6E6E6E);
        } else {
            for (int dz = 0; dz < cells; dz++) {
                for (int dx = 0; dx < cells; dx++) {
                    int color = colors[dz * cells + dx];
                    int cx = px + dx * cellPx;
                    int cy = py + dz * cellPx;
                    t.fill(cx, cy, cx + cellPx, cy + cellPx, color);
                }
            }
        }

        // 框外坐标标尺（随拖动/缩放动态变化）：下侧 X、左侧 Z，各 4 个
        double blocksPerPx = (double) this.previewSpacing / cellPx;
        int centerPx = PREVIEW_PX / 2;
        for (int rulerPx : PREVIEW_X_RULER_PX) {
            String text = formatCoord(this.previewCenterX
                    + (int) Math.round((rulerPx - centerPx) * blocksPerPx));
            t.text(this.font, text, px + rulerPx - this.font.width(text) / 2, py + PREVIEW_PX + 4, MUTED_COLOR);
        }
        for (int rulerPx : PREVIEW_Z_RULER_PX) {
            String text = formatCoord(this.previewCenterZ
                    + (int) Math.round((rulerPx - centerPx) * blocksPerPx));
            t.text(this.font, text, px - 5 - this.font.width(text), py + rulerPx - 4, MUTED_COLOR);
        }
    }

    /** 标尺文字：十万以内显示整数，更大用 k 缩写（避免文字超出标尺条）。 */
    private static String formatCoord(int value) {
        if (Math.abs(value) < 100_000) return Integer.toString(value);
        return Math.round(value / 1000f) + "k";
    }

    /** 预览的防抖重建：公式停下约 0.5 秒后在后台线程采样，结果回主线程。 */
    private void tickPreview() {
        if (!this.previewVisible || this.previewRunning) return;
        long now = System.currentTimeMillis();
        if (this.previewDueAt == 0 || now < this.previewDueAt) return;
        String formula = this.formulaBox.value();
        String dimension = this.previewSelectedDim;
        if (formula.isBlank() || !this.currentErrors.isEmpty() || dimension == null) return;
        int centerX = this.previewCenterX;
        int centerZ = this.previewCenterZ;
        int spacing = this.previewSpacing;
        String key = formula + "\u0000" + dimension + "\u0000" + centerX + "," + centerZ + "," + spacing;
        if (key.equals(this.previewComputedFor)) return;
        FormulaParser.DimensionParseResult parsed = this.currentResult;
        this.previewRunning = true;
        Thread worker = new Thread(() -> {
            FormulaPreview.Result result = null;
            try {
                result = FormulaPreview.compute(parsed, dimension, centerX, centerZ, spacing);
            } catch (Exception ignored) {}
            FormulaPreview.Result computed = result;
            Minecraft.getInstance().execute(() -> {
                this.previewRunning = false;
                if (computed != null) {
                    this.previewColors = computed.colors();
                    this.previewColorsCells = computed.cells();
                }
                this.previewComputedFor = key;
            });
        }, "ohmyworld-preview");
        worker.setDaemon(true);
        worker.start();
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

        if (this.formulaBox.value().isBlank()) {
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

    /** 「已识别 N 个维度 · 主世界 12 层 · 下界 5 层」（主界面与全屏编辑共用）。 */
    static Component summaryLine(FormulaParser.DimensionParseResult result) {
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
