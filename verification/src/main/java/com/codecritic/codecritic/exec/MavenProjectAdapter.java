package com.codecritic.exec;

import java.nio.file.Path;
import java.util.List;

public final class MavenProjectAdapter extends AbstractProjectAdapter {

    private static final List<String> TEST_ARGUMENTS =
            List.of("-B", "-ntp", "-Dstyle.color=never", "test");

    public MavenProjectAdapter(CommandRunner commandRunner, TestResultXmlParser resultParser) {
        super(commandRunner, resultParser);
    }

    @Override
    public String name() {
        return "maven";
    }

    @Override
    protected List<String> testCommand(Path worktree) {
        return ExecutableCommand.withWrapper(worktree, "mvn", "mvnw", TEST_ARGUMENTS);
    }

    @Override
    protected List<String> targetedTestCommand(Path worktree, String testSelector) {
        return ExecutableCommand.withWrapper(
                worktree,
                "mvn",
                "mvnw",
                List.of("-B", "-ntp", "-Dstyle.color=never", "-Dtest=" + testSelector, "test"));
    }

    @Override
    protected List<Path> testResultRoots(Path worktree) {
        return findResultDirectories(worktree, "target", "surefire-reports", "failsafe-reports");
    }
}
