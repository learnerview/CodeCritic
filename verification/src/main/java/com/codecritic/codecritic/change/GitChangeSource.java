package com.codecritic.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GitChangeSource {

    private final GitCommandRunner git;
    private final GitBlobBatchReader blobReader;

    public GitChangeSource() {
        this(new GitCommandRunner(), new GitBlobBatchReader());
    }

    GitChangeSource(GitCommandRunner git) {
        this(git, new GitBlobBatchReader());
    }

    GitChangeSource(GitCommandRunner git, GitBlobBatchReader blobReader) {
        this.git = git;
        this.blobReader = blobReader;
    }

    public ChangeSet load(Path repository, String baseRef, String headRef) {
        Path root = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new GitException("Repository directory does not exist: " + root);
        }

        git.runText(root, "rev-parse", "--verify", baseRef + "^{commit}");
        git.runText(root, "rev-parse", "--verify", headRef + "^{commit}");

        Map<String, String> headSources = loadHeadSources(root, headRef);
        List<ChangedFile> files = new ArrayList<>();

        for (RawChange raw : readRawChanges(root, baseRef, headRef)) {
            switch (raw.kind()) {
                case ADDED -> files.add(new ChangedFile(
                        null,
                        raw.newPath(),
                        ChangeKind.ADDED,
                        null,
                        headSource(root, headRef, headSources, raw.newPath())));
                case DELETED -> files.add(new ChangedFile(
                        raw.oldPath(),
                        null,
                        ChangeKind.DELETED,
                        readAtRef(root, baseRef, raw.oldPath()),
                        null));
                case RENAMED -> files.add(new ChangedFile(
                        raw.oldPath(),
                        raw.newPath(),
                        ChangeKind.RENAMED,
                        readAtRef(root, baseRef, raw.oldPath()),
                        headSource(root, headRef, headSources, raw.newPath())));
                case MODIFIED -> files.add(new ChangedFile(
                        raw.oldPath(),
                        raw.newPath(),
                        ChangeKind.MODIFIED,
                        readAtRef(root, baseRef, raw.oldPath()),
                        headSource(root, headRef, headSources, raw.newPath())));
            }
        }

        return new ChangeSet(root, baseRef, headRef, files, headSources);
    }

    private Map<String, String> loadHeadSources(Path root, String headRef) {
        byte[] tree = git.runBytes(root, "ls-tree", "-r", "--name-only", "-z", headRef);
        String[] paths = new String(tree, StandardCharsets.UTF_8).split("\u0000");
        List<String> javaPaths = new ArrayList<>();
        for (String path : paths) {
            if (isJava(path)) {
                javaPaths.add(path);
            }
        }

        List<String> batchPaths = javaPaths.stream()
                .filter(path -> !path.contains("\n") && !path.contains("\r"))
                .toList();
        Map<String, String> batched = blobReader.read(root, headRef, batchPaths);

        Map<String, String> sources = new LinkedHashMap<>();
        for (String path : javaPaths) {
            String source = batched.get(path);
            sources.put(path, source != null ? source : readAtRef(root, headRef, path));
        }
        return sources;
    }

    private List<RawChange> readRawChanges(Path root, String baseRef, String headRef) {
        byte[] diff = git.runBytes(
                root,
                "diff",
                "--name-status",
                "-M",
                "-z",
                "--no-ext-diff",
                baseRef,
                headRef);
        String[] tokens = new String(diff, StandardCharsets.UTF_8).split("\u0000");
        List<RawChange> changes = new ArrayList<>();

        int index = 0;
        while (index < tokens.length) {
            String status = tokens[index++];
            if (status.isBlank()) {
                continue;
            }
            char code = status.charAt(0);
            if (code == 'R') {
                String oldPath = tokens[index++];
                String newPath = tokens[index++];
                if (isJava(oldPath) || isJava(newPath)) {
                    changes.add(new RawChange(ChangeKind.RENAMED, oldPath, newPath));
                }
            } else if (code == 'C') {
                String oldPath = tokens[index++];
                String newPath = tokens[index++];
                if (isJava(oldPath) || isJava(newPath)) {
                    changes.add(new RawChange(ChangeKind.ADDED, null, newPath));
                }
            } else {
                String path = tokens[index++];
                if (!isJava(path)) {
                    continue;
                }
                ChangeKind kind = switch (code) {
                    case 'A' -> ChangeKind.ADDED;
                    case 'D' -> ChangeKind.DELETED;
                    default -> ChangeKind.MODIFIED;
                };
                changes.add(new RawChange(kind, path, path));
            }
        }
        return changes;
    }

    private String headSource(Path root, String headRef, Map<String, String> headSources, String path) {
        String source = headSources.get(path);
        return source != null ? source : readAtRef(root, headRef, path);
    }

    private String readAtRef(Path root, String ref, String path) {
        if (path == null) {
            return null;
        }
        byte[] content = git.runBytes(root, "show", ref + ":" + path);
        return new String(content, StandardCharsets.UTF_8);
    }

    private static boolean isJava(String path) {
        return path != null && path.endsWith(".java");
    }

    private record RawChange(ChangeKind kind, String oldPath, String newPath) {
    }
}
