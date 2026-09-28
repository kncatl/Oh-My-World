package com.kncatl.ohmyworld.client;

import java.util.Objects;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import com.kncatl.ohmyworld.PatternData;
import com.kncatl.ohmyworld.PresetKeys;
import com.kncatl.ohmyworld.compat.ResourceIds;

public final class FlatPatternScreenEvents {

    /** 上一次看到的预设键；ID 类型随版本变化，因此用 Object 存放（见 ResourceIds）。 */
    private static Object lastPresetKey;
    private static Screen lastScreen;

    public static void onScreenRenderPost(Screen screen) {
        if (!(screen instanceof CreateWorldScreen cw)) {
            lastScreen = null;
            lastPresetKey = null;
            return;
        }
        if (lastScreen != cw) {
            lastScreen = cw;
            lastPresetKey = null;
        }

        WorldCreationUiState state = cw.getUiState();
        if (state.getWorldType() == null) return;
        var holder = state.getWorldType().preset();
        if (holder == null) return;
        Object key = holder.unwrapKey().map(ResourceIds::keyId).orElse(null);
        if (key == null) return;

        if (Objects.equals(key, lastPresetKey)) return;
        lastPresetKey = key;

        if (!key.equals(ResourceIds.keyId(PresetKeys.OUR_KEY))) {
            PatternData.clearActive();
            PatternData.clearPending();
            return;
        }

        PatternData.setPending();
    }
}
