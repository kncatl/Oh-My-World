package com.kncatl.ohmyworld;

import java.util.List;

/**
 * 公式里的维度级指令（与维度内容一起解析、随 marker / 配置持久化）。
 *
 * <p>结构规则（{@code [structure:...]}）与群系规则（{@code [biome:...]}）互不依赖；
 * 都是"不写 = 默认"。
 */
public final class DimensionRules {

    /**
     * 结构控制规则。
     *
     * <p>{@code names} 存的是规范化名称（去掉 {@code minecraft:} 前缀；自定义命名空间保留）。
     * 匹配同时看结构组 ID 与成员结构 ID（{@code only=villages} 与 {@code only=village_plains}
     * 都能命中 villages 结构组）。
     */
    public record StructureRule(Mode mode, List<String> names) {
        public enum Mode { ALL, NONE, ONLY, EXCEPT }

        public static final StructureRule ALL = new StructureRule(Mode.ALL, List.of());

        public boolean isDefault() { return mode == Mode.ALL; }

        /** 一个结构组（含其成员结构 id）是否允许生成。 */
        public boolean allows(String setId, List<String> memberIds) {
            return switch (mode) {
                case ALL -> true;
                case NONE -> false;
                case ONLY -> matches(setId, memberIds);
                case EXCEPT -> !matches(setId, memberIds);
            };
        }

        private boolean matches(String setId, List<String> memberIds) {
            for (String name : names) {
                if (name.equals(setId)) return true;
                for (String member : memberIds) {
                    if (name.equals(member)) return true;
                }
            }
            return false;
        }
    }

    /**
     * 群系规则：{@code vanilla=true} 表示使用该维度的原版群系源；
     * 否则 {@code singleBiomeId} 为固定单一群系（规范化名称）。
     */
    public record BiomeRule(boolean vanilla, String singleBiomeId) {
        public static final BiomeRule VANILLA = new BiomeRule(true, null);

        public static BiomeRule single(String biomeId) {
            return new BiomeRule(false, biomeId);
        }
    }

    /**
     * 公式群系未覆盖位置的回退方式（{@code [biome-fallback:none|2d|3d]}，缺省 none）。
     *
     * <p>none 要求 biome 行覆盖整个维度（解析期检查）；2d 按"参考 y"（公式地形表面）
     * 采样原版一次后覆盖整列；3d 按实际 y 采样原版（保留原版纵向分层，如洞穴群系）。
     */
    public enum BiomeFallback {
        NONE, TWO_D, THREE_D
    }

    /** 规范化名称：去掉 {@code minecraft:} 前缀，其余命名空间保留。 */
    public static String normalizeName(String name) {
        String s = name.trim();
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    private DimensionRules() {}
}
