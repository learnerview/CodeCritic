package com.codecritic.change;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.util.Optional;

public final class JavaSourceParser {

    private JavaSourceParser() {
    }

    public static Optional<CompilationUnit> parse(String source) {
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }

        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);

        ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(source);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
    }
}
