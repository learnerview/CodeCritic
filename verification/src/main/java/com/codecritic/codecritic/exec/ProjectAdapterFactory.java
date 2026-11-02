package com.codecritic.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class ProjectAdapterFactory {

    private final CommandRunner commandRunner;
    private final TestResultXmlParser resultParser;

    public ProjectAdapterFactory() {
        this(new DockerSandboxRunner(), new TestResultXmlParser());
    }

    public ProjectAdapterFactory(CommandRunner commandRunner, TestResultXmlParser resultParser) {
        this.commandRunner = commandRunner;
        this.resultParser = resultParser;
    }

    public Optional<ProjectAdapter> detect(Path worktree) {
        if (Files.isRegularFile(worktree.resolve("pom.xml"))) {
            return Optional.of(new MavenProjectAdapter(commandRunner, resultParser));
        }
        if (Files.isRegularFile(worktree.resolve("build.gradle"))
                || Files.isRegularFile(worktree.resolve("build.gradle.kts"))) {
            return Optional.of(new GradleProjectAdapter(commandRunner, resultParser));
        }
        return Optional.empty();
    }
}
