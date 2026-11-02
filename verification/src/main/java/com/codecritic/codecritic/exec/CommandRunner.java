package com.codecritic.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface CommandRunner {

    ProcessResult run(Path workingDirectory, List<String> command, Duration timeout, int maxOutputBytes);

    default String environment() {
        return "host";
    }
}
