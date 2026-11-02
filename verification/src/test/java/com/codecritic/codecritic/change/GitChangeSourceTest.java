package com.codecritic.change;

import com.codecritic.test.GitTestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitChangeSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsAddedModifiedAndDeletedJavaFiles() {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("repo"));
        repository.write("src/main/java/com/example/Calculator.java", """
                package com.example;

                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """);
        repository.write("src/main/java/com/example/Legacy.java", """
                package com.example;

                public class Legacy {
                    public void old() {
                    }
                }
                """);
        repository.write("src/test/java/com/example/CalculatorTest.java", """
                package com.example;

                class CalculatorTest {
                }
                """);
        String base = repository.commit("base");

        repository.write("src/main/java/com/example/Calculator.java", """
                package com.example;

                public class Calculator {
                    public int add(int a, int b) {
                        return Math.addExact(a, b);
                    }
                }
                """);
        repository.write("src/main/java/com/example/NewThing.java", """
                package com.example;

                public class NewThing {
                    public void run() {
                    }
                }
                """);
        repository.delete("src/main/java/com/example/Legacy.java");
        String head = repository.commit("head");

        ChangeSet changes = new GitChangeSource().load(repository.root(), base, head);

        assertThat(changes.files()).hasSize(3);
        assertThat(changes.files()).extracting(ChangedFile::displayPath)
                .containsExactlyInAnyOrder(
                        "src/main/java/com/example/Calculator.java",
                        "src/main/java/com/example/NewThing.java",
                        "src/main/java/com/example/Legacy.java");

        ChangedFile calculator = changes.files().stream()
                .filter(file -> file.displayPath().endsWith("Calculator.java"))
                .findFirst()
                .orElseThrow();
        assertThat(calculator.kind()).isEqualTo(ChangeKind.MODIFIED);
        assertThat(calculator.baseSource()).contains("return a + b;");
        assertThat(calculator.headSource()).contains("Math.addExact");

        ChangedFile added = changes.files().stream()
                .filter(file -> file.displayPath().endsWith("NewThing.java"))
                .findFirst()
                .orElseThrow();
        assertThat(added.kind()).isEqualTo(ChangeKind.ADDED);
        assertThat(added.baseSource()).isNull();
        assertThat(added.headSource()).contains("class NewThing");

        ChangedFile deleted = changes.files().stream()
                .filter(file -> file.displayPath().endsWith("Legacy.java"))
                .findFirst()
                .orElseThrow();
        assertThat(deleted.kind()).isEqualTo(ChangeKind.DELETED);
        assertThat(deleted.baseSource()).contains("class Legacy");
        assertThat(deleted.headSource()).isNull();

        assertThat(changes.headSources()).containsKeys(
                "src/main/java/com/example/Calculator.java",
                "src/main/java/com/example/NewThing.java",
                "src/test/java/com/example/CalculatorTest.java");
        assertThat(changes.headSources()).doesNotContainKey("src/main/java/com/example/Legacy.java");
    }

    @Test
    void detectsRenamedJavaFiles() {
        GitTestRepository repository = new GitTestRepository(tempDir.resolve("rename-repo"));
        repository.write("src/main/java/com/example/OldName.java", """
                package com.example;

                public class OldName {
                    public int value() {
                        return 42;
                    }
                }
                """);
        String base = repository.commit("base");

        repository.move(
                "src/main/java/com/example/OldName.java",
                "src/main/java/com/example/NewName.java");
        String head = repository.commit("rename");

        ChangeSet changes = new GitChangeSource().load(repository.root(), base, head);

        assertThat(changes.files()).hasSize(1);
        ChangedFile renamed = changes.files().get(0);
        assertThat(renamed.kind()).isEqualTo(ChangeKind.RENAMED);
        assertThat(renamed.oldPath()).endsWith("OldName.java");
        assertThat(renamed.newPath()).endsWith("NewName.java");
        assertThat(renamed.baseSource()).contains("class OldName");
        assertThat(renamed.headSource()).contains("class OldName");
    }
}
