package com.kncatl.ohmyworld.platform;

import java.nio.file.Path;

public interface Platform {

    Path gameDir();

    Path configDir();

    void onLevelLoad(LevelLoadCallback callback);

    void onScreenRenderPost(ScreenRenderCallback callback);

    final class Holder {
        private static Platform instance;

        public static Platform get() { return instance; }
        public static void set(Platform impl) { instance = impl; }
    }

    static Platform get() { return Holder.get(); }
    static void set(Platform impl) { Holder.set(impl); }
}