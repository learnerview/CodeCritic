package com.codecritic.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectAdapterFactoryTest {

    @TempDir
    Path tempDir;

    private final ProjectAdapterFactory factory = new ProjectAdapterFactory();

    @Test
    void detectsMaven() throws Exception {
        Path project = tempDir.resolve("maven");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>");

        Optional<ProjectAdapter> adapter = factory.detect(project);

        assertThat(adapter).isPresent();
        assertThat(adapter.get().name()).isEqualTo("maven");
    }

    @Test
    void detectsGradle() throws Exception {
        Path project = tempDir.resolve("gradle");
        Files.createDirectories(project);
        Files.writeString(project.resolve("build.gradle.kts"), "");

        Optional<ProjectAdapter> adapter = factory.detect(project);

        assertThat(adapter).isPresent();
        assertThat(adapter.get().name()).isEqualTo("gradle");
    }

    @Test
    void prefersMavenWhenBothBuildToolsExist() throws Exception {
        Path project = tempDir.resolve("both");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("build.gradle"), "");

        Optional<ProjectAdapter> adapter = factory.detect(project);

        assertThat(adapter).isPresent();
        assertThat(adapter.get().name()).isEqualTo("maven");
    }

    @Test
    void returnsEmptyForAnUnsupportedProject() throws Exception {
        Path project = tempDir.resolve("plain");
        Files.createDirectories(project);

        assertThat(factory.detect(project)).isEmpty();
    }
}
