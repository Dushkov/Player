package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * minSdk is 24 without desugaring: java.nio.file (API 26) compiles and passes JVM tests but crashes on
 * Android 7. Guard our own classes against it, like the regex-flag guard in BgSubsQueryTest.
 */
public class BgSubsAndroidApiTest {

    @Test
    public void bgSubsClassesAvoidApisMissingOnAndroid7() throws Exception {
        File dir = new File("src/main/java/com/brouken/player");
        File[] sources = dir.listFiles((d, name) -> name.startsWith("BgSubs") && name.endsWith(".java"));
        assertTrue("sources not found from " + new File(".").getAbsolutePath(), sources != null && sources.length > 0);
        for (File f : sources) {
            String code = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(f.getName() + " uses java.nio.file", code.contains("java.nio.file"));
            assertFalse(f.getName() + " uses File.toPath()", code.contains(".toPath()"));
        }
    }
}
