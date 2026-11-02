package com.codecritic.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCandidateLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void infersTheConventionalTestPathFromThePackageAndClass() throws Exception {
        Path source = tempDir.resolve("candidate/CalculatorTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.math;

                class CalculatorTest {
                }
                """);

        TestCandidate candidate = TestCandidateLoader.load(source, tempDir.resolve("repo"), null);

        assertThat(candidate.relativePath())
                .isEqualTo("src/test/java/com/example/math/CalculatorTest.java");
        assertThat(candidate.className()).isEqualTo("com.example.math.CalculatorTest");
        assertThat(candidate.source()).contains("class CalculatorTest");
    }

    @Test
    void preservesAPathAlreadyUnderTheTestSourceRoot() throws Exception {
        Path repository = tempDir.resolve("repo");
        Path source = repository.resolve("src/test/java/com/example/CalculatorTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                class CalculatorTest {
                }
                """);

        TestCandidate candidate = TestCandidateLoader.load(source, repository, null);

        assertThat(candidate.relativePath())
                .isEqualTo("src/test/java/com/example/CalculatorTest.java");
    }

    @Test
    void acceptsAnExplicitProjectRelativePath() throws Exception {
        Path source = tempDir.resolve("CalculatorTest.java");
        Files.writeString(source, """
                package com.example;

                class CalculatorTest {
                }
                """);

        TestCandidate candidate = TestCandidateLoader.load(
                source,
                tempDir.resolve("repo"),
                "module-a/src/test/java/com/example/CalculatorTest.java");

        assertThat(candidate.relativePath())
                .isEqualTo("module-a/src/test/java/com/example/CalculatorTest.java");
    }

    @Test
    void rejectsPathsOutsideTheProject() throws Exception {
        Path source = tempDir.resolve("CalculatorTest.java");
        Files.writeString(source, """
                package com.example;

                class CalculatorTest {
                }
                """);

        assertThatThrownBy(() -> TestCandidateLoader.load(
                source,
                tempDir.resolve("repo"),
                "../CalculatorTest.java"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inside the project");
    }

    @Test
    void rejectsFilesThatAreNotParseableJava() throws Exception {
        Path source = tempDir.resolve("Broken.java");
        Files.writeString(source, "not java at all");

        assertThatThrownBy(() -> TestCandidateLoader.load(
                source,
                tempDir.resolve("repo"),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parseable");
    }
}
