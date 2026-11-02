package com.codecritic.exec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class ProcessProbe {

    private ProcessProbe() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> {
                System.out.println(args[1]);
                System.exit(Integer.parseInt(args[2]));
            }
            case "sleep" -> {
                Thread.sleep(Long.parseLong(args[1]));
                System.out.println("done");
            }
            case "flood" -> {
                int lines = Integer.parseInt(args[1]);
                for (int i = 0; i < lines; i++) {
                    System.out.println("line-" + i);
                }
            }
            case "spawn" -> {
                Process child = new ProcessBuilder(
                        javaExecutable(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        ProcessProbe.class.getName(),
                        "sleep",
                        "10000")
                        .start();
                Files.writeString(Path.of(args[1]), String.valueOf(child.pid()));
                Thread.sleep(10000);
            }
            default -> throw new IllegalArgumentException("Unknown probe: " + args[0]);
        }
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }
}
