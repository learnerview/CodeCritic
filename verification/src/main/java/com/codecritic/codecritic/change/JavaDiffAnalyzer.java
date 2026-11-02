package com.codecritic.change;

import com.github.javaparser.ast.CompilationUnit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JavaDiffAnalyzer {

    public JavaDiffResult diff(String path, String baseSource, String headSource) {
        List<String> warnings = new ArrayList<>();
        ParsedSource baseParse = parse(path, baseSource, "base", warnings);
        ParsedSource headParse = parse(path, headSource, "head", warnings);
        if (!baseParse.parsed() || !headParse.parsed()) {
            return new JavaDiffResult(List.of(), warnings);
        }

        Map<String, MethodInfo> base = baseParse.methods();
        Map<String, MethodInfo> head = headParse.methods();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(base.keySet());
        keys.addAll(head.keySet());

        List<MethodChange> changes = new ArrayList<>();
        for (String key : keys) {
            MethodInfo before = base.get(key);
            MethodInfo after = head.get(key);

            if (before == null) {
                changes.add(new MethodChange(
                        path,
                        key,
                        after.className(),
                        after.methodName(),
                        MethodChangeKind.ADDED,
                        null,
                        after.signature(),
                        after.publicApi(),
                        0,
                        after.cyclomaticComplexity(),
                        0,
                        after.line(),
                        List.of("new callable")));
                continue;
            }

            if (after == null) {
                changes.add(new MethodChange(
                        path,
                        key,
                        before.className(),
                        before.methodName(),
                        MethodChangeKind.REMOVED,
                        before.signature(),
                        null,
                        before.publicApi(),
                        before.cyclomaticComplexity(),
                        0,
                        before.line(),
                        0,
                        List.of("callable removed")));
                continue;
            }

            boolean signatureChanged = !before.signature().equals(after.signature());
            boolean bodyChanged = !before.body().equals(after.body());

            if (!signatureChanged && !bodyChanged) {
                continue;
            }

            List<String> details = new ArrayList<>();
            MethodChangeKind kind;
            if (signatureChanged) {
                kind = MethodChangeKind.SIGNATURE_CHANGED;
                details.add("signature: " + before.signature() + " -> " + after.signature());
                if (bodyChanged) {
                    details.add("body also changed");
                }
                if (before.publicApi() != after.publicApi()) {
                    details.add("public API visibility changed");
                }
            } else {
                kind = MethodChangeKind.MODIFIED;
                details.add("body changed");
            }

            if (before.cyclomaticComplexity() != after.cyclomaticComplexity()) {
                details.add("complexity: " + before.cyclomaticComplexity()
                        + " -> " + after.cyclomaticComplexity());
            }

            changes.add(new MethodChange(
                    path,
                    key,
                    after.className(),
                    after.methodName(),
                    kind,
                    before.signature(),
                    after.signature(),
                    before.publicApi() || after.publicApi(),
                    before.cyclomaticComplexity(),
                    after.cyclomaticComplexity(),
                    before.line(),
                    after.line(),
                    details));
        }

        return new JavaDiffResult(changes, warnings);
    }

    private ParsedSource parse(String path, String source, String side, List<String> warnings) {
        if (source == null || source.isBlank()) {
            return new ParsedSource(Map.of(), true);
        }
        Optional<CompilationUnit> unit = JavaSourceParser.parse(source);
        if (unit.isEmpty()) {
            warnings.add("Could not parse " + path + " at " + side + "; file skipped");
            return new ParsedSource(Map.of(), false);
        }
        return new ParsedSource(JavaMethodExtractor.extract(unit.get()), true);
    }

    private record ParsedSource(Map<String, MethodInfo> methods, boolean parsed) {
    }
}
