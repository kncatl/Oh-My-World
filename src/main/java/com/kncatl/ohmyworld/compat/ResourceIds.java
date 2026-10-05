package com.kncatl.ohmyworld.compat;

import net.minecraft.resources.ResourceKey;
//? >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}

/**
 * 资源 ID 的版本差异收敛层。
 *
 * <p>1.21.11 起 {@code ResourceLocation} 改名为 {@code Identifier}。需要跨版本
 * 持有的场景（字段、比较）用 {@link #keyId} / {@link #sameKey} 以 {@code Object}
 * 为桥梁；构造新 ID 用 {@link #of}（返回值类型随版本变化，调用方用 {@code var} 接收）。
 *
 * <p>版本差异只允许出现在 {@code compat} 包内 —— 业务代码不要直接写
 * {@code Identifier}/{@code ResourceLocation} 的条件分支。
 */
public final class ResourceIds {

    private ResourceIds() {}

    /** 构造资源 ID。返回类型随版本变化，调用方请用 {@code var} 接收。 */
    //? >=1.21.11 {
    public static Identifier of(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
    //?} else {
    public static ResourceLocation of(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
    //?}

    /** ResourceKey 的 ID，以 {@code Object} 返回，便于跨版本存放与比较。 */
    public static Object keyId(ResourceKey<?> key) {
        //? >=1.21.11 {
        return key.identifier();
        //?} else {
        return key.location();
        //?}
    }

    /** ResourceKey 的 ID 字符串（{@code namespace:path}），跨版本可用。 */
    public static String keyIdString(ResourceKey<?> key) {
        return keyId(key).toString();
    }

    /** 两个 ResourceKey 是否指向同一个 ID。 */
    public static boolean sameKey(ResourceKey<?> a, ResourceKey<?> b) {
        return keyId(a).equals(keyId(b));
    }
}
