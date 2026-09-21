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
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 世界等价性校验：逐区块比较两个世界目录的生成结果。
 *
 * <p>判定依据是区块里由生成逻辑决定的两个子树：
 * <ul>
 *   <li>{@code Heightmaps} —— 地表高度、运动阻挡高度等</li>
 *   <li>{@code sections} —— 每个区段的调色板、压缩方块数据、生物群系与光照</li>
 * </ul>
 * 时间相关字段（{@code LastUpdate}、{@code InhabitedTime} 等）位于区块顶层，
 * 不在上述子树内，因此不同时刻生成同一世界也能判定为等价。
 *
 * <p><b>为什么用 {@code Tag.write} 而不是类型化 getter：</b>本项目通过 Stonecutter
 * 面向多个 Minecraft 版本编译同一份测试源码，而 Minecraft 1.21.9 起重做了 NBT API
 * ——{@code getAllKeys}/{@code getString} 被移除，{@code getCompound} 改为返回
 * {@code Optional}。{@code get(String)} 与规范二进制序列化 {@code Tag.write} 是所有
 * 目标版本都有的，且序列化无损，不受 getter 语义变化影响。
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

    /** 参与比较的两个子树。 */
    private static final String[] KEYS = {"Heightmaps", "sections"};

    /** 解析失败时的占位前缀；它会让该区块被判定为「不同」而不是被忽略。 */
    private static final String ERROR = "ERROR ";

    private static String brief(String value) {
        return value.length() <= 160 ? value : value.substring(0, 160) + "…";
    }

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
        long totalBytes = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, String> entry : a.entrySet()) {
            String other = b.get(entry.getKey());
            if (other == null) continue;
            if (!entry.getValue().equals(other)) {
                diff++;
                if (examples.size() < 8) examples.add("    " + entry.getKey() + "  A=" + brief(entry.getValue()) + "  B=" + brief(other));
                continue;
            }
            String value = entry.getValue();
            if (value.startsWith(ERROR)) {
                // 两边都解析失败也要暴露出来，不能悄悄算作相同
                diff++;
                if (examples.size() < 8) examples.add("    " + entry.getKey() + "  " + brief(value));
                continue;
            }
            same++;
            totalBytes += Long.parseLong(value.substring(0, value.indexOf(':')));
        }

        System.out.println();
        System.out.println("--- 世界等价性校验 ---");
        System.out.println("  A = " + A + "  →  " + a.size() + " 区块");
        System.out.println("  B = " + B + "  →  " + b.size() + " 区块");
        System.out.println("  取交集后  内容相同: " + same + "     内容不同: " + diff);
        if (same > 0) {
            System.out.printf("  参与比对的序列化字节总数: %d（平均每区块 %d，确认确实比到了数据）%n",
                    totalBytes, totalBytes / same);
        }
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

                    // 区域文件里的区块是 zlib（压缩类型 2），不是 gzip。
                    // 不能用 NbtIo.readCompressed——它固定按 GZIP 解压。
                    try (DataInputStream in = new DataInputStream(
                            new InflaterInputStream(new ByteArrayInputStream(data, start + 5, length - 1)))) {
                        CompoundTag chunk = NbtIo.read(in, NbtAccounter.unlimitedHeap());
                        out.put(name + "[" + i + "]", describe(chunk));
                    } catch (Exception e) {
                        out.put(name + "[" + i + "]", ERROR + e);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 汇总区块内由生成逻辑决定的数据。
     *
     * @return {@code 序列化字节数:sha256}，字节数一并返回是为了让「什么都没比到」
     *         这种情况（例如某一版本文档结构变了）能被看见，而不是悄悄判为相等
     */
    private static String describe(CompoundTag chunk) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;

        for (String key : KEYS) {
            Tag tag = chunk.get(key);
            if (tag == null) {
                digest.update((byte) 0xFF);
                continue;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                tag.write(out);
            }
            byte[] bytes = buffer.toByteArray();
            total += bytes.length;
            // 带上长度，避免不同长度的数据被拼成同一串
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }

        return total + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
