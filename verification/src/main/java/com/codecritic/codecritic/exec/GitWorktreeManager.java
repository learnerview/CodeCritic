package com.codecritic.exec;

import com.codecritic.change.GitCommandRunner;
import com.codecritic.change.GitException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public final class GitWorktreeManager implements AutoCloseable {

    private final GitCommandRunner git;
    private final Path repository;
    private final List<Path> worktrees = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public GitWorktreeManager(Path repository) {
        this(repository, new GitCommandRunner());
    }

    GitWorktreeManager(Path repository, GitCommandRunner git) {
        this.repository = repository.toAbsolutePath().normalize();
        this.git = git;
    }

    public Path add(String ref) {
        Path path;
        try {
            path = Files.createTempDirectory("codecritic-worktree-");
        } catch (IOException e) {
            throw new GitException("Unable to create a worktree directory: " + e.getMessage(), e);
        }

        try {
            git.runText(
                    repository,
                    "worktree",
                    "add",
                    "--detach",
                    "--force",
                    path.toString(),
                    ref);
            worktrees.add(path);
            return path;
        } catch (RuntimeException e) {
            deleteRecursively(path);
            throw e;
        }
    }

    public List<String> closeAndCollect() {
        for (int i = worktrees.size() - 1; i >= 0; i--) {
            Path path = worktrees.get(i);
            try {
                git.runText(repository, "worktree", "remove", "--force", path.toString());
            } catch (RuntimeException e) {
                warnings.add("Unable to remove git worktree " + path + ": " + e.getMessage());
            }
            if (Files.exists(path) && !deleteRecursively(path)) {
                warnings.add("Unable to delete worktree directory " + path);
            }
        }
        worktrees.clear();

        try {
            git.runText(repository, "worktree", "prune");
        } catch (RuntimeException e) {
            warnings.add("Unable to prune git worktree metadata: " + e.getMessage());
        }
        return List.copyOf(warnings);
    }

    @Override
    public void close() {
        closeAndCollect();
    }

    private static boolean deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return true;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return !Files.exists(root);
        } catch (IOException e) {
            return false;
        }
    }
}
