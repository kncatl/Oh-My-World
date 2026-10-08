package com.kncatl.ohmyworld;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kncatl.ohmyworld.expr.ExprEvaluator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5-lite「单一事实来源」的漂移测试：函数表（{@link ExprEvaluator#functionNames()}）
 * 里的每个函数都必须在中英指南里有对应说明（以 {@code 名字(} 形式出现）。
 *
 * <p>函数表在 Java 侧是唯一来源；指南仍是手工维护，本测试保证两边不漂移。
 * 新增函数时若忘记写指南，这里会失败。
 */
class GuideFunctionTableTest {

    private static String readGuide(String path) throws IOException {
        try (InputStream in = GuideFunctionTableTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "找不到指南资源: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyFunctionIsDocumentedInBothGuides() throws IOException {
        String zh = readGuide("/assets/ohmyworld/doc/guide.txt");
        String en = readGuide("/assets/ohmyworld/doc/guide_en.txt");
        List<String> missing = new ArrayList<>();
        for (String name : ExprEvaluator.functionNames()) {
            if (!zh.contains(name + "(")) missing.add("guide.txt: " + name);
            if (!en.contains(name + "(")) missing.add("guide_en.txt: " + name);
        }
        assertTrue(missing.isEmpty(), "指南缺少以下函数的说明: " + missing);
    }
}
