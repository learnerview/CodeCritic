package com.codecritic.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ExecutableCommand {

    private ExecutableCommand() {
    }

    static List<String> withWrapper(
            Path worktree,
            String executable,
            String wrapperName,
            List<String> arguments) {
        boolean windows = isWindows();
        Path wrapper = worktree.resolve(windows ? wrapperName + ".cmd" : wrapperName);
        if (Files.isRegularFile(wrapper) && (windows || Files.isExecutable(wrapper))) {
            if (windows) {
                return prepend(List.of("cmd.exe", "/d", "/s", "/c", wrapper.toString()), arguments);
            }
            return prepend(List.of(wrapper.toString()), arguments);
        }
        if (windows) {
            return prepend(List.of("cmd.exe", "/d", "/s", "/c", executable), arguments);
        }
        return prepend(List.of(executable), arguments);
    }

    private static List<String> prepend(List<String> prefix, List<String> arguments) {
        List<String> command = new ArrayList<>(prefix.size() + arguments.size());
        command.addAll(prefix);
        command.addAll(arguments);
        return List.copyOf(command);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
