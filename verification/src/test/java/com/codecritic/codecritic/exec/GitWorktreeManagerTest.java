package com.codecritic.exec;

import com.codecritic.change.GitException;
import com.codecritic.test.GitTestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWorktreeManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDetachedWorktreesAndCleansThemUp() throws Exception {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo"));
        repository.write("src/Main.java", "base");
        String base = repository.commit("base");
        repository.write("src/Main.java", "head");
        String head = repository.commit("head");

        GitWorktreeManager manager = new GitWorktreeManager(repository.root());
        Path baseTree = manager.add(base);
        Path headTree = manager.add(head);

        assertThat(Files.readString(baseTree.resolve("src/Main.java")).strip()).isEqualTo("base");
        assertThat(Files.readString(headTree.resolve("src/Main.java")).strip()).isEqualTo("head");

        List<String> warnings = manager.closeAndCollect();

        assertThat(warnings).isEmpty();
        assertThat(Files.exists(baseTree)).isFalse();
        assertThat(Files.exists(headTree)).isFalse();
        long worktrees = repository.git("worktree", "list", "--porcelain")
                .lines()
                .filter(line -> line.startsWith("worktree "))
                .count();
        assertThat(worktrees).isEqualTo(1);
    }

    @Test
    void doesNotLeaveAWorktreeWhenTheRefDoesNotExist() {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo"));
        repository.write("README.md", "base");
        repository.commit("base");

        GitWorktreeManager manager = new GitWorktreeManager(repository.root());

        assertThatThrownBy(() -> manager.add("does-not-exist"))
                .isInstanceOf(GitException.class);
        assertThat(manager.closeAndCollect()).isEmpty();
    }
}
