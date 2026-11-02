package com.codecritic.test;

import com.codecritic.change.GitCommandRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GitTestRepository {

    private final Path root;
    private final GitCommandRunner git = new GitCommandRunner();

    public GitTestRepository(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create test repository", e);
        }
        git("init");
        git("config", "user.email", "codecritic@example.com");
        git("config", "user.name", "CodeCritic Test");
        git("config", "core.autocrlf", "false");
    }

    public Path root() {
        return root;
    }

    public void write(String path, String content) {
        Path target = root.resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write " + path, e);
        }
    }

    public void delete(String path) {
        try {
            Files.delete(root.resolve(path));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to delete " + path, e);
        }
    }

    public void move(String from, String to) {
        try {
            Files.createDirectories(root.resolve(to).getParent());
            Files.move(root.resolve(from), root.resolve(to));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to move " + from + " to " + to, e);
        }
    }

    public String commit(String message) {
        git("add", "-A");
        git("commit", "-m", message);
        return git("rev-parse", "HEAD").strip();
    }

    public String git(String... arguments) {
        return git.runText(root, arguments);
    }
}
