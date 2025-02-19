package org.jetbrains.java.decompiler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.Test;
import codechicken.diffpatch.cli.CliOperation;
import codechicken.diffpatch.cli.DiffOperation;
import codechicken.diffpatch.util.Utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RetroGradleComparisonTest {

    Path fg2output   = Paths.get("testData", "retrogradle", "fg2/decompiled.zip");
    Path fg2filtered = Paths.get("testData", "retrogradle", "fg2/decompiled-filtered.zip");
    Path fg3input    = Paths.get("testData", "retrogradle", "fg3/output.jar");
    Path diffOutput  = Paths.get("testData", "retrogradle", "output");

    /**
     * Setup Required: <br>
     * <br>
     * - clone 1.11.x (FG2) from <a href="https://github.com/RetroGradle/MinecraftForge">RetroGradle/MinecraftForge</a><br>
     * - `gradlew setup` (May need to be from the terminal due to gradle version) <br>
     * - copy `build/localCache/decompiled.zip` -> `testData/retrogradle/fg2/decompiled.zip` <br>
     * <br>
     * - checkout retrogradle1.11 branch <br>
     * - `gradlew setup` (May require a second `gradlew setup` as the first downloads the custom MCPConfig zip) <br>
     * - copy `projects/mcp/build/mcp/decompile/output.jar` -> `testData/retrogradle/fg3/output.jar` <br>
     */
    @Test
    public void diffOutputs() throws IOException {
        if (Files.exists(diffOutput)) Utils.deleteFolder(diffOutput);
        Files.createDirectories(diffOutput);

        if (!Files.exists(fg2output)) fail("FG2 1.11.x decompiled.zip not provided");
        if (!Files.exists(fg3input))  fail("Retrogradle 1.11.x output.jar not provided");

        // We have to filter the fg2output to only contain the java files like fg3 does
        filter(fg2output, fg2filtered);

        final CliOperation.Result<DiffOperation.DiffSummary> result = DiffOperation.builder()
            .aPrefix("fg2")
            .bPrefix("fg3")
            .aPath(fg2filtered)
            .bPath(fg3input)
            .outputPath(diffOutput)
            .logTo(System.out)
            .verbose(false)
            .summary(true) // Summary is fairly useful in testing
            .build()
            .operate();

        assertEquals(2, result.summary.removedFiles); // Side & SideOnly aren't present in the FG3 zip
        assertEquals(0, result.summary.changedFiles); // Otherwise we expect 0 differences with FG2 zip
        assertEquals(0, result.summary.addedFiles);
    }

    private static void filter(Path input, Path output) throws IOException {
        if (Files.exists(output)) return;

        filter(Files.newInputStream(input), Files.newOutputStream(output));
    }

    private static void filter(InputStream input, OutputStream output) throws IOException {
        try (ZipOutputStream zout = new ZipOutputStream(output); ZipInputStream zin = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.getName().endsWith(".java")) {
                    zout.putNextEntry(entry);
                    Utils.copy(zin, zout);
                }
            }
        }
    }
}
