package com.kncatl.ohmyworld.compat;

import java.awt.Desktop;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 「用系统打开文件/文件夹」的跨版本兼容入口。依次尝试三条路：
 * <ol>
 *   <li>{@code com.mojang.blaze3d.Blaze3D.openPath(Path)}——26.3 起的平台层
 *       （内部走 {@code ProcessBuilder} 调系统命令，不依赖 AWT）；</li>
 *   <li>{@code Util.getPlatform().openFile(File)}——同为系统命令实现，但类名跨版本
 *       搬过家（1.21.5 及以前是 {@code net.minecraft.Util}，1.21.11 起是
 *       {@code net.minecraft.util.Util}，而 1.21.5 节点要覆盖到 1.21.10），
 *       因此用反射按两种类名探测；</li>
 *   <li>{@code java.awt.Desktop}——老兜底；部分启动器/精简 JDK 下 AWT 不可用，
 *       模块化运行环境也可能整体关闭。</li>
 * </ol>
 * 全部失败时返回 false，并把最后的异常写进日志（便于用户反馈时排查）。
 */
public final class FileOpen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] UTIL_CLASSES = {"net.minecraft.util.Util", "net.minecraft.Util"};

    private FileOpen() {}

    /** 尝试用系统默认方式打开该路径；成功返回 true。 */
    public static boolean open(Path path) {
        Throwable last = openViaBlaze3D(path);
        if (last == null) return true;
        last = openViaUtil(path, last);
        if (last == null) return true;
        return openViaDesktop(path, last);
    }

    private static Throwable openViaBlaze3D(Path path) {
        try {
            Class<?> blaze3d = Class.forName("com.mojang.blaze3d.Blaze3D");
            Method openPath = blaze3d.getMethod("openPath", Path.class);
            openPath.invoke(null, path);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static Throwable openViaUtil(Path path, Throwable previous) {
        Throwable last = previous;
        for (String className : UTIL_CLASSES) {
            try {
                Class<?> util = Class.forName(className);
                Object platform = util.getMethod("getPlatform").invoke(null);
                platform.getClass().getMethod("openFile", File.class).invoke(platform, path.toFile());
                return null;
            } catch (Throwable t) {
                last = t;
            }
        }
        return last;
    }

    private static boolean openViaDesktop(Path path, Throwable previous) {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException("desktop unsupported");
            }
            Desktop.getDesktop().open(path.toFile());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("ohmyworld: could not open {} (Blaze3D / Util / Desktop all failed)", path, t);
            return false;
        }
    }
}
