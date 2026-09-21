package com.kncatl.ohmyworld;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.InflaterInputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界等价性校验：逐区块比较两个世界目录的生成结果。
 *
 * <p>参与断言的是**生成逻辑决定**的数据：
 * <ul>
 *   <li>{@code Heightmaps} —— 地表高度、运动阻挡高度等</li>
 *   <li>{@code sections} 中除光照外的全部内容 —— 方块调色板、压缩方块数据、生物群系调色板</li>
 * </ul>
 *
 * <p><b>SkyLight/BlockLight 为什么单独统计：</b>光照不是世界生成输出，而是光照引擎从
 * 方块推导出的状态。落盘的光照值取决于关服时光照更新跑到哪一步：引擎会把尚未传播完
 * 的区段按当前进度写入，顶部空区段甚至只在有光时才写入数组。实测同一份代码多次运行
 * 结果一致，但不同代码会因生成耗时不同而停在不同的传播进度上——方块数据完全一致而
 * 光照不一致，只说明引擎调度不同，不说明地形有差异。因此光照差异单独打印、不参与
 * 断言；只要有区块的生成数据不同就判失败。
 *
 * <p>时间相关字段（{@code LastUpdate}、{@code InhabitedTime} 等）位于区块顶层，
 * 不在上述子树内，因此不同时刻生成同一世界也能判定为等价。
 *
 * <p><b>为什么用 {@code Tag.write} 而不是类型化 getter：</b>本项目通过 Stonecutter
 * 面向多个 Minecraft 版本编译同一份测试源码，而 Minecraft 1.21.9 起重做了 NBT API
 * ——{@code getAllKeys}/{@code getString} 被移除，{@code getList}/{@code getCompound}
 * 改为一组返回 {@code Optional} 的新方法。{@code get(String)}、{@code ListTag.get(int)}
 * 与二进制序列化 {@code Tag.write} 是所有目标版本都有的，且序列化无损，不受 getter
 * 语义变化影响。
 *
 * <p><b>注意：{@code Tag.write} 不是「规范」序列化。</b>CompoundTag 按插入顺序写出键，
 * 而插入顺序不是内容的函数——同一个区段有没有光照键、从哪个存档读出来，键的顺序都
 * 可能不同。若直接对整个区段/列表做序列化，就会出现「内容相同、字节不同」的假差异
 * （本项目实测遇到过：SkyLight 缺省与否会改变其它键的写出顺序）。因此这里只对**已知
 * 的区段键**逐个序列化；这些键的值（数组、列表、其内部结构）由内容唯一决定，顺序稳定。
 * 缺点是未来版本若新增区段键需要在此登记。
 *
 * <p>两个世界必须来自**同一个** Minecraft 版本，本项目正是如此。
 *
 * <p>用 {@link NbtIo} 解析，避免手写 NBT 解析器的对齐问题。两个目录都不存在时
 * 自动跳过，不影响正常构建；可用系统属性
 * {@code -Dohmyworld.worldA=... -Dohmyworld.worldB=...} 指定目录。
 */
class WorldCompareTest {

    private static final Path A = Path.of(System.getProperty("ohmyworld.worldA", "/tmp/world-old"));
    private static final Path B = Path.of(System.getProperty("ohmyworld.worldB", "/tmp/world-new"));

    /** 解析失败时的占位前缀；它会让该区块被判为「生成数据不同」而不是被忽略。 */
    private static final String ERROR = "ERROR ";

    /**
     * 区段里由生成逻辑决定的键。这些键的值由内容唯一决定，序列化顺序稳定；
     * 不要改成序列化整个区段（见类注释：CompoundTag 的键顺序不可靠）。
     */
    private static final String[] TERRAIN_SECTION_KEYS = {"Y", "block_states", "biomes"};

    /** 区段里由光照引擎决定的键，单独统计、不参与断言。 */
    private static final String[] LIGHT_SECTION_KEYS = {"SkyLight", "BlockLight"};

    private static String brief(String value) {
        return value.length() <= 160 ? value : value.substring(0, 160) + "…";
    }

    /**
     * 单个区块的两份指纹。
     *
     * @param terrainBytes 参与生成数据指纹的序列化字节数（用于证明「确实比到了数据」）
     * @param terrainHash  高度图 + 去光照区段的 sha256
     * @param lightBytes   参与光照指纹的序列化字节数
     * @param lightHash    SkyLight/BlockLight 的 sha256
     */
    private record Fingerprint(long terrainBytes, String terrainHash, long lightBytes, String lightHash) {
        boolean terrainError() {
            return terrainHash.startsWith(ERROR);
        }
    }

    @Test
    void worldsAreEquivalent() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(A) && Files.isDirectory(B),
                "缺少待比较的世界目录: " + A + " / " + B);

        Map<String, Fingerprint> a = fingerprint(A);
        Map<String, Fingerprint> b = fingerprint(B);

        List<String> onlyA = new ArrayList<>();
        List<String> onlyB = new ArrayList<>();
        for (String key : a.keySet()) if (!b.containsKey(key)) onlyA.add(key);
        for (String key : b.keySet()) if (!a.containsKey(key)) onlyB.add(key);

        int terrainSame = 0;
        int terrainDiff = 0;
        int lightDiff = 0;
        long terrainBytes = 0;
        long lightBytes = 0;
        List<String> examples = new ArrayList<>();
        List<String> lightExamples = new ArrayList<>();
        for (Map.Entry<String, Fingerprint> entry : a.entrySet()) {
            Fingerprint other = b.get(entry.getKey());
            if (other == null) continue;
            Fingerprint mine = entry.getValue();
            if (mine.terrainError() || other.terrainError()
                    || !mine.terrainHash().equals(other.terrainHash())) {
                terrainDiff++;
                if (examples.size() < 8) {
                    examples.add("    " + entry.getKey()
                            + "  A=" + brief(mine.terrainHash())
                            + "  B=" + brief(other.terrainHash()));
                }
                continue;
            }
            terrainSame++;
            terrainBytes += mine.terrainBytes();
            if (!mine.lightHash().equals(other.lightHash())) {
                lightDiff++;
                if (lightExamples.size() < 8) lightExamples.add("    " + entry.getKey());
            } else {
                lightBytes += mine.lightBytes();
            }
        }

        System.out.println();
        System.out.println("--- 世界等价性校验 ---");
        System.out.println("  A = " + A + "  →  " + a.size() + " 区块");
        System.out.println("  B = " + B + "  →  " + b.size() + " 区块");
        System.out.println("  取交集后  生成数据相同: " + terrainSame + "     生成数据不同: " + terrainDiff);
        System.out.printf("  参与比对的生成数据字节总数: %d（平均每区块 %d，确认确实比到了数据）%n",
                terrainBytes, terrainSame == 0 ? 0 : terrainBytes / terrainSame);
        if (lightDiff > 0) {
            System.out.println("  其中光照不一致: " + lightDiff
                    + "（光照是引擎状态，不作为失败条件；光照也一致的区块共 "
                    + (terrainSame - lightDiff) + " 个，参与比对的光照字节 "
                    + lightBytes + "）");
            for (String example : lightExamples) System.out.println(example);
        }
        if (!onlyA.isEmpty()) System.out.println("  仅 A 有: " + onlyA.size() + "  例: " + head(onlyA));
        if (!onlyB.isEmpty()) System.out.println("  仅 B 有: " + onlyB.size() + "  例: " + head(onlyB));
        for (String example : examples) System.out.println(example);
        boolean equivalent = terrainDiff == 0 && terrainSame > 0;
        System.out.println("  结论: " + (equivalent ? "生成数据在交集内完全一致 ✓" : "生成数据存在差异 ✗"));
        System.out.println();

        assertEquals(0, terrainDiff, "存在生成数据不同的区块");
        assertTrue(terrainSame > 0, "交集内没有可比对的区块（不能把空比对当作通过）");
    }

    private static List<String> head(List<String> list) {
        return list.subList(0, Math.min(5, list.size()));
    }

    private static Map<String, Fingerprint> fingerprint(Path worldDir) throws IOException {
        Map<String, Fingerprint> out = new TreeMap<>();
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

                    // 区域文件里的区块是 zlib（压缩类型 2），不是 gzip。
                    // 不能用 NbtIo.readCompressed——它固定按 GZIP 解压。
                    try (DataInputStream in = new DataInputStream(
                            new InflaterInputStream(new ByteArrayInputStream(data, start + 5, length - 1)))) {
                        CompoundTag chunk = NbtIo.read(in, NbtAccounter.unlimitedHeap());
                        out.put(name + "[" + i + "]", describe(chunk));
                    } catch (Exception e) {
                        String error = ERROR + e;
                        out.put(name + "[" + i + "]", new Fingerprint(0, error, 0, error));
                    }
                }
            }
        }
        return out;
    }

    /**
     * 汇总一个区块的生成数据与光照数据。
     *
     * <p>生成数据 = {@code Heightmaps} + 每个区段的 {@link #TERRAIN_SECTION_KEYS}；
     * 光照数据 = 每个区段的 {@link #LIGHT_SECTION_KEYS}（数组缺省也要留标记）。
     * 每份指纹都带上序列化字节数，让「什么都没比到」能被看见，而不是悄悄判为相等。
     */
    private static Fingerprint describe(CompoundTag chunk) throws IOException {
        MessageDigest terrain = sha256();
        MessageDigest light = sha256();
        long terrainBytes = 0;
        long lightBytes = 0;

        terrainBytes += update(terrain, chunk.get("Heightmaps"));

        Tag sectionsTag = chunk.get("sections");
        if (sectionsTag instanceof ListTag sections) {
            for (int i = 0; i < sections.size(); i++) {
                if (!(sections.get(i) instanceof CompoundTag section)) {
                    terrain.update((byte) 0xFE);
                    continue;
                }
                for (String key : TERRAIN_SECTION_KEYS) {
                    terrainBytes += update(terrain, section.get(key));
                }
                for (String key : LIGHT_SECTION_KEYS) {
                    lightBytes += update(light, section.get(key));
                }
            }
        } else {
            // 没有区段列表的区块：给生成数据留一个不可与正常区块混淆的标记
            terrain.update((byte) 0xFD);
        }

        return new Fingerprint(terrainBytes, HexFormat.of().formatHex(terrain.digest()),
                lightBytes, HexFormat.of().formatHex(light.digest()));
    }

    /** 把一个 tag 的规范序列化写进摘要；缺省（null）也要留下标记，不能与空数据混淆。 */
    private static long update(MessageDigest digest, Tag tag) throws IOException {
        if (tag == null) {
            digest.update((byte) 0xFF);
            return 0;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            tag.write(out);
        }
        byte[] bytes = buffer.toByteArray();
        // 带上长度，避免不同长度的数据被拼成同一串
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
        return bytes.length;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
