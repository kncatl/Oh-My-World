package com.kncatl.ohmyworld.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import com.kncatl.ohmyworld.FormulaParser;
import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.PatternFlatSource;

public class CustomFlatScreen extends Screen implements PresetEditor {
    private final CreateWorldScreen parent;
    private EditBox layersInput, nameInput;
    private Button saveBtn;
    private static final Component TITLE = Component.translatable("ohmyworld.custom_screen.title");
    private static final Component LAYERS_LABEL = Component.translatable("ohmyworld.custom_screen.layers");
    private static final Component DONE = Component.translatable("ohmyworld.custom_screen.done");
    private static final Component CANCEL = Component.translatable("ohmyworld.custom_screen.cancel");
    private static final Component SAVE = Component.translatable("ohmyworld.custom_screen.save");
    private static final Component SAVE_NAME = Component.translatable("ohmyworld.custom_screen.save_name");
    private static final Component SAVE_NAME2 = Component.translatable("ohmyworld.custom_screen.save_name2");
    public CustomFlatScreen(CreateWorldScreen parent, WorldCreationContext context) { super(TITLE); this.parent = parent; }
    @Override public Screen createEditScreen(CreateWorldScreen lastScreen, WorldCreationContext context) { return new CustomFlatScreen(lastScreen, context); }
    @Override protected void init() {
        int cx = this.width / 2, boxW = 300, boxY = 45, fxH = 44;
        this.layersInput = new EditBox(this.font, cx - boxW / 2 + 10, boxY + 20, boxW - 20, fxH, LAYERS_LABEL);
        this.layersInput.setMaxLength(2000); this.layersInput.setValue(PatternData.getRawInput()); this.addRenderableWidget(this.layersInput);
        int nameY = boxY + 20 + fxH + 40;
        this.nameInput = new EditBox(this.font, cx - boxW / 2 + 10, nameY, boxW - 20, 20, SAVE_NAME);
        this.nameInput.setMaxLength(64); this.addRenderableWidget(this.nameInput);
        int btnY = nameY + 30;
        this.saveBtn = Button.builder(SAVE, b -> onSave()).bounds(cx - boxW / 2, btnY, 80, 20).build();
        this.addRenderableWidget(this.saveBtn);
        this.addRenderableWidget(Button.builder(DONE, b -> onDone()).bounds(cx + boxW / 2 - 80, btnY, 80, 20).build());
        this.addRenderableWidget(Button.builder(CANCEL, b -> onCancel()).bounds(cx - 40, btnY + 26, 80, 20).build());
        updateButtonState();
    }
    private void updateButtonState() { saveBtn.active = !layersInput.getValue().isBlank() && !nameInput.getValue().isBlank(); }
    private void onSave() {
        String name = nameInput.getValue().trim(), formula = layersInput.getValue();
        if (name.isEmpty() || formula.isBlank()) return;
        try { Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld"); Files.createDirectories(dir); Files.writeString(dir.resolve(name + ".txt"), formula); } catch (Exception ignored) {}
    }
    private void onDone() {
        String input = layersInput.getValue(); PatternData.set(FormulaParser.parse(input), input);
        ChunkGenerator gen = parent.getUiState().getSettings().selectedDimensions().overworld();
        if (gen instanceof FlatLevelSource fs) parent.getUiState().updateDimensions((reg,dims) -> dims.replaceOverworldGenerator(reg, new PatternFlatSource(fs.settings())));
        if (!nameInput.getValue().isBlank()) onSave();
        minecraft.setScreen(parent);
    }
    private void onCancel() { minecraft.setScreen(parent); }
    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawCenteredString(font, TITLE, width / 2, 18, 0xFFFFFF);
        g.drawString(font, LAYERS_LABEL, width / 2 - 145, 47, 0xA0A0A0);
        g.drawString(font, SAVE_NAME, width / 2 - 145, nameInput.getY() - 22, 0xA0A0A0);
        g.drawString(font, SAVE_NAME2, width / 2 - 145, nameInput.getY() - 12, 0xA0A0A0);
        updateButtonState();
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
