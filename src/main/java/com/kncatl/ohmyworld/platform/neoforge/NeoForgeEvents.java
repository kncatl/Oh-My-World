package com.kncatl.ohmyworld.platform.neoforge;

//? if NEOFORGE {
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import com.kncatl.ohmyworld.FlatPattern;
import com.kncatl.ohmyworld.FormulaBiomeSources;
import com.kncatl.ohmyworld.platform.Platform;

public class NeoForgeEvents {

    @EventBusSubscriber(modid = FlatPattern.MODID)
    public static class Common {
        @SubscribeEvent
        public static void onRegister(RegisterEvent event) {
            // 内置注册表登记阶段：把公式群系源注册成 BIOME_SOURCE 的正式类型
            // （噪声维度的公式群系行在世界保存时要求已注册的 dispatch 类型，
            // 见 FormulaBiomeSources）。
            if (event.getRegistryKey().equals(Registries.BIOME_SOURCE)) {
                FormulaBiomeSources.register();
            }
        }

        @SubscribeEvent
        public static void onLevelLoad(LevelEvent.Load event) {
            if (!(event.getLevel() instanceof ServerLevel sl)) return;
            ((NeoForgePlatform) Platform.get()).dispatchLevelLoad(sl);
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            ((NeoForgePlatform) Platform.get()).dispatchServerTick(event.getServer());
        }
    }
}
//?}
