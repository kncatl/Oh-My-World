package com.kncatl.ohmyworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OhMyWorldConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void readsUtf8BomAndNormalizesLineEndings() throws Exception {
        Path file = tempDir.resolve("formula.txt");
        Files.writeString(file, "\uFEFF{overworld=[carvers:none]\r\n  y=..: minecraft:stone;\r\n}\r\n",
                StandardCharsets.UTF_8);
        assertEquals("{overworld=[carvers:none]\n  y=..: minecraft:stone;\n}\n",
                OhMyWorldConfig.readFormulaFile(file));
    }

    @Test
    void rejectsEmptyFormulaFile() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, " \n\t", StandardCharsets.UTF_8);
        IOException error = assertThrows(IOException.class, () -> OhMyWorldConfig.readFormulaFile(file));
        assertTrue(error.getMessage().contains("empty"));
    }

    @Test
    void rejectsMissingFormulaFile() {
        Path file = tempDir.resolve("missing.txt");
        assertThrows(IOException.class, () -> OhMyWorldConfig.readFormulaFile(file));
    }

    @Test
    void absolutePathIsUsedAsIs() {
        Path file = tempDir.resolve("absolute.txt");
        assertEquals(file, OhMyWorldConfig.resolveFormulaPath(file.toString()));
    }
}
