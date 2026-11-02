package com.codecritic.exec;

import java.nio.file.Path;
import java.util.List;

public final class GradleProjectAdapter extends AbstractProjectAdapter {

    private static final List<String> TEST_ARGUMENTS =
            List.of("--no-daemon", "--console=plain", "test");

    public GradleProjectAdapter(CommandRunner commandRunner, TestResultXmlParser resultParser) {
        super(commandRunner, resultParser);
    }

    @Override
    public String name() {
        return "gradle";
    }

    @Override
    protected List<String> testCommand(Path worktree) {
        return ExecutableCommand.withWrapper(worktree, "gradle", "gradlew", TEST_ARGUMENTS);
    }

    @Override
    protected List<String> targetedTestCommand(Path worktree, String testSelector) {
        return ExecutableCommand.withWrapper(
                worktree,
                "gradle",
                "gradlew",
                List.of("--no-daemon", "--console=plain", "test", "--tests", testSelector));
    }

    @Override
    protected List<Path> testResultRoots(Path worktree) {
        return findResultDirectories(worktree, "build", "test-results");
    }
}
