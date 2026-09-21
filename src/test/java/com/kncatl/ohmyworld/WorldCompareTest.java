package com.kncatl.ohmyworld;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 世界等价性校验：逐区块比较两个世界目录的生成结果。
 *
 * <p>判定依据是区块里由生成逻辑决定的全部数据：
 * <ul>
 *   <li>{@code Heightmaps} 的每一个长整型数组（地表高度、运动阻挡高度等）</li>
 *   <li>每个区段的方块调色板（含顺序）与压缩后的方块数据数组</li>
 * </ul>
 * 时间相关字段（{@code LastUpdate}、{@code InhabitedTime} 等）不参与比较，
 * 因此不同时刻生成同一世界也能判定为等价。
 *
 * <p>用 Minecraft 自带的 {@link NbtIo} 解析，避免手写 NBT 解析器的对齐问题。
 * 两个目录都不存在时自动跳过，不影响正常构建；可用系统属性
 * {@code -Dohmyworld.worldA=... -Dohmyworld.worldB=...} 指定目录。
 */
class WorldCompareTest {

    private static final Path A = Path.of(System.getProperty("ohmyworld.worldA", "/tmp/world-old"));
    private static final Path B = Path.of(System.getProperty("ohmyworld.worldB", "/tmp/world-new"));

    @Test
    void worldsAreEquivalent() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(A) && Files.isDirectory(B),
                "缺少待比较的世界目录: " + A + " / " + B);

        Map<String, String> a = fingerprint(A);
        Map<String, String> b = fingerprint(B);

        List<String> onlyA = new ArrayList<>();
        List<String> onlyB = new ArrayList<>();
        for (String key : a.keySet()) if (!b.containsKey(key)) onlyA.add(key);
        for (String key : b.keySet()) if (!a.containsKey(key)) onlyB.add(key);

        int same = 0;
        int diff = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, String> entry : a.entrySet()) {
            String other = b.get(entry.getKey());
            if (other == null) continue;
            if (entry.getValue().equals(other)) {
                same++;
            } else {
                diff++;
                if (examples.size() < 5) examples.add("    " + entry.getKey());
            }
        }

        System.out.println();
        System.out.println("--- 世界等价性校验 ---");
        System.out.println("  A = " + A + "  →  " + a.size() + " 区块");
        System.out.println("  B = " + B + "  →  " + b.size() + " 区块");
        System.out.println("  取交集后  内容相同: " + same + "     内容不同: " + diff);
        if (!onlyA.isEmpty()) System.out.println("  仅 A 有: " + onlyA.size() + "  例: " + head(onlyA));
        if (!onlyB.isEmpty()) System.out.println("  仅 B 有: " + onlyB.size() + "  例: " + head(onlyB));
        for (String example : examples) System.out.println(example);
        boolean equivalent = diff == 0 && same > 0;
        System.out.println("  结论: " + (equivalent ? "交集内完全一致 ✓" : "存在差异 ✗"));
        System.out.println();

        assertEquals(0, diff, "存在内容不同的区块");
    }

    private static List<String> head(List<String> list) {
        return list.subList(0, Math.min(5, list.size()));
    }

    private static Map<String, String> fingerprint(Path worldDir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path regionDir = worldDir.resolve("region");
        if (!Files.isDirectory(regionDir)) return out;

        try (Stream<Path> files = Files.list(regionDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".mca")).sorted().toList()) {
                byte[] data = Files.readAllBytes(file);
                if (data.length < 8192) continue;
                String name = file.getFileName().toString();
                ByteBuffer header = ByteBuffer.wrap(data);

                for (int i = 0; i < 1024; i++) {
                    header.position(i * 4);
                    int off = ((header.get() & 0xFF) << 16) | ((header.get() & 0xFF) << 8) | (header.get() & 0xFF);
                    if (off == 0) continue;
                    int start = off * 4096;
                    if (start + 5 > data.length) continue;
                    int length = ((data[start] & 0xFF) << 24) | ((data[start + 1] & 0xFF) << 16)
                            | ((data[start + 2] & 0xFF) << 8) | (data[start + 3] & 0xFF);
                    // 压缩类型 2 = zlib（区域文件默认）
                    if (data[start + 4] != 2 || length <= 1) continue;

                    try (InputStream in = new ByteArrayInputStream(data, start + 5, length - 1)) {
                        CompoundTag chunk = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                        out.put(name + "[" + i + "]", describe(chunk));
                    } catch (Exception e) {
                        out.put(name + "[" + i + "]", "PARSE-ERROR " + e);
                    }
                }
            }
        }
        return out;
    }

    /** 汇总区块内由生成逻辑决定的数据。 */
    private static String describe(CompoundTag chunk) {
        StringBuilder sb = new StringBuilder();

        Tag heightmaps = chunk.get("Heightmaps");
        if (heightmaps instanceof CompoundTag hm) {
            for (String key : new TreeSet<>(hm.getAllKeys())) {
                if (hm.get(key) instanceof LongArrayTag array) {
                    sb.append("HM:").append(key).append('=').append(Arrays.toString(array.getAsLongArray())).append(';');
                }
            }
        }

        Tag sections = chunk.get("sections");
        if (sections instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                CompoundTag section = list.getCompound(i);
                sb.append("Y").append(section.getInt("Y")).append(':');
                CompoundTag states = section.getCompound("block_states");

                ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
                for (int k = 0; k < palette.size(); k++) {
                    sb.append(palette.getCompound(k).getString("Name")).append(',');
                }

                // 压缩后的方块数据数组：同样的调色板也可能对应不同的方块摆放
                if (states.get("data") instanceof LongArrayTag array) {
                    sb.append('|').append(Arrays.toString(array.getAsLongArray()));
                }
                sb.append(';');
            }
        }

        return sb.toString();
    }
}
