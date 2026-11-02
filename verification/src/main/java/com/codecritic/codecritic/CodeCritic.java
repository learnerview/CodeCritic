package com.codecritic;

import com.codecritic.cli.CodeCriticCommand;
import picocli.CommandLine;

public final class CodeCritic {

    private CodeCritic() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CodeCriticCommand()).execute(args);
        System.exit(exitCode);
    }
}
