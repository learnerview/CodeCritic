package com.codecritic.exec;

import java.nio.file.Path;

public interface ProjectAdapter {

    String name();

    TestSuiteResult execute(Path worktree, String revision, ExecutionOptions options);

    TestSuiteResult execute(
            Path worktree,
            String revision,
            ExecutionOptions options,
            String testSelector);
}
