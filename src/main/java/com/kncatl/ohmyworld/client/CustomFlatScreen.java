package com.kncatl.ohmyworld.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;

import com.kncatl.ohmyworld.FormulaParser;
import com.kncatl.ohmyworld.PatternData;

public class CustomFlatScreen extends Screen implements PresetEditor {

    private final CreateWorldScreen parent;
    private EditBox layersInput;
    private EditBox nameInput;
    private Button saveBtn;
    private Button loadBtn;
    private List<String> currentErrors = new ArrayList<>();
    private String pendingFormula;
    private String pendingName;

    private static final Component TITLE = Component.translatable("ohmyworld.custom_screen.title");
    private static final Component LAYERS_LABEL = Component.translatable("ohmyworld.custom_screen.layers");
    private static final Component DONE = Component.translatable("ohmyworld.custom_screen.done");
    private static final Component CANCEL = Component.translatable("ohmyworld.custom_screen.cancel");
    private static final Component SAVE = Component.translatable("ohmyworld.custom_screen.save");
    private static final Component SAVE_NAME = Component.translatable("ohmyworld.custom_screen.save_name");
    private static final Component SAVE_NAME2 = Component.translatable("ohmyworld.custom_screen.save_name2");
    private static final Component LOAD = Component.translatable("ohmyworld.custom_screen.load");
    private static final Component LOAD_TITLE = Component.translatable("ohmyworld.custom_screen.load_title");
    private static final Component NO_SAVES = Component.translatable("ohmyworld.custom_screen.no_saves");
    private static final int ERROR_COLOR = 0xFF5555;
    private static final int OK_COLOR = 0x55FF55;

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
        int centerX = this.width / 2;
        int boxW = 300;
        int boxY = 45;
        int fxH = 44;

        this.layersInput = new EditBox(this.font, centerX - boxW / 2 + 10, boxY + 20, boxW - 20, fxH, LAYERS_LABEL);
        this.layersInput.setMaxLength(2000);
        this.layersInput.setValue(pendingFormula != null ? pendingFormula : PatternData.getRawInput());
        this.layersInput.setResponder(t -> validate());
        this.addRenderableWidget(this.layersInput);

        int nameY = boxY + 20 + fxH + 40;
        this.nameInput = new EditBox(this.font, centerX - boxW / 2 + 10, nameY, boxW - 20, 20, SAVE_NAME);
        this.nameInput.setMaxLength(64);
        if (pendingName != null) this.nameInput.setValue(pendingName);
        this.nameInput.setResponder(t -> updateButtonState());
        this.addRenderableWidget(this.nameInput);

        int btnY = nameY + 30;
        this.saveBtn = Button.builder(SAVE, b -> onSave()).bounds(centerX - boxW / 2, btnY, 80, 20).build();
        this.addRenderableWidget(this.saveBtn);
        this.loadBtn = Button.builder(LOAD, b -> openLoadList()).bounds(centerX - boxW / 2 + 90, btnY, 80, 20).build();
        this.addRenderableWidget(this.loadBtn);
        this.addRenderableWidget(Button.builder(DONE, b -> onDone()).bounds(centerX + boxW / 2 - 80, btnY, 80, 20).build());
        this.addRenderableWidget(Button.builder(CANCEL, b -> onCancel()).bounds(centerX - 40, btnY + 26, 80, 20).build());

        updateButtonState();
        validate();

        this.pendingFormula = null;
        this.pendingName = null;
    }

    private void validate() {
        String input = this.layersInput.getValue();
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors(input);
        this.currentErrors = result.errors();
        updateButtonState();
    }

    private void updateButtonState() {
        boolean hasName = !this.nameInput.getValue().isBlank();
        boolean hasFormula = !this.layersInput.getValue().isBlank();
        this.saveBtn.active = hasName && hasFormula;
    }

    private void onSave() {
        String name = this.nameInput.getValue().trim();
        String formula = this.layersInput.getValue();
        if (name.isEmpty() || formula.isBlank()) return;
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".txt"), formula);
        } catch (Exception ignored) {}
    }

    private List<String> listSavedFormulas() {
        List<String> names = new ArrayList<>();
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld");
            if (!Files.isDirectory(dir)) return names;
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".txt"))
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
            this.minecraft.setScreen(new Screen(Component.translatable("ohmyworld.custom_screen.load_title")) {
                @Override
                protected void init() {
                    this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                            b -> this.minecraft.setScreen(CustomFlatScreen.this)).bounds(this.width / 2 - 40, this.height / 2 + 10, 80, 20).build());
                }
                @Override
                public void render(GuiGraphics g, int mx, int my, float pt) {
                    super.render(g, mx, my, pt);
                    g.drawCenteredString(this.font, NO_SAVES, this.width / 2, this.height / 2 - 10, 0xFF5555);
                }
                @Override
                public void onClose() { this.minecraft.setScreen(CustomFlatScreen.this); }
            });
            return;
        }

        this.minecraft.setScreen(new Screen(LOAD_TITLE) {
            @Override
            protected void init() {
                int y = 40;
                int bw = 240;
                for (String name : saves) {
                    String displayName = name.length() > 30 ? name.substring(0, 27) + "..." : name;
                    this.addRenderableWidget(Button.builder(Component.literal(displayName), b -> {
                        loadFormula(name);
                        this.minecraft.setScreen(CustomFlatScreen.this);
                    }).bounds(this.width / 2 - bw / 2, y, bw, 20).build());
                    y += 24;
                    if (y > this.height - 40) break;
                }
                this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                        b -> this.minecraft.setScreen(CustomFlatScreen.this)).bounds(this.width / 2 - 40, this.height - 28, 80, 20).build());
            }
            @Override
            public void onClose() { this.minecraft.setScreen(CustomFlatScreen.this); }
        });
    }

    private void loadFormula(String name) {
        try {
            Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("ohmyworld").resolve(name + ".txt");
            String content = Files.readString(file);
            this.pendingFormula = content;
            this.pendingName = name;
        } catch (Exception ignored) {}
    }

    private void onDone() {
        String input = this.layersInput.getValue();
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors(input);
        PatternData.set(result.layers(), input);

        if (!this.nameInput.getValue().isBlank()) onSave();
        this.minecraft.setScreen(this.parent);
    }

    private void onCancel() { this.minecraft.setScreen(this.parent); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, TITLE, this.width / 2, 18, 0xFFFFFF);
        graphics.drawString(this.font, LAYERS_LABEL, this.width / 2 - 145, 47, 0xA0A0A0);
        graphics.drawString(this.font, SAVE_NAME, this.width / 2 - 145, this.nameInput.getY() - 22, 0xA0A0A0);
        graphics.drawString(this.font, SAVE_NAME2, this.width / 2 - 145, this.nameInput.getY() - 12, 0xA0A0A0);

        int errorY = this.nameInput.getY() + 30 + 26 + 20 + 10;
        if (currentErrors.isEmpty()) {
            if (!this.layersInput.getValue().isBlank()) {
                graphics.drawString(this.font, Component.translatable("ohmyworld.custom_screen.no_errors"),
                        this.width / 2 - 145, errorY, OK_COLOR);
            }
        } else {
            graphics.drawString(this.font, Component.translatable("ohmyworld.custom_screen.errors", currentErrors.size()),
                    this.width / 2 - 145, errorY, ERROR_COLOR);
            int lineY = errorY + 12;
            int maxShow = Math.min(currentErrors.size(), 6);
            for (int i = 0; i < maxShow; i++) {
                String msg = currentErrors.get(i);
                String trimmed = this.font.plainSubstrByWidth(msg, 290);
                graphics.drawString(this.font, Component.literal(trimmed), this.width / 2 - 145, lineY, ERROR_COLOR);
                lineY += 11;
            }
            if (currentErrors.size() > 6) {
                graphics.drawString(this.font, Component.literal("..."),
                        this.width / 2 - 145, lineY, ERROR_COLOR);
            }
        }
    }

    @Override
    public void onClose() { this.minecraft.setScreen(this.parent); }
}
