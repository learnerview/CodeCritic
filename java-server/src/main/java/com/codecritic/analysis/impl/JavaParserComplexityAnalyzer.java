package com.codecritic.analysis.impl;

import com.codecritic.analysis.ComplexityAnalyzer;
import com.codecritic.dto.ComplexityResponse;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AST-based complexity analyzer using JavaParser.
 *
 * If parsing fails it delegates to a lightweight token heuristic so the API
 * still returns a useful answer (graceful degradation).
 */
@Component
public class JavaParserComplexityAnalyzer implements ComplexityAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaParserComplexityAnalyzer.class);

    @Override
    public ComplexityResponse analyze(String code) {
        if (code == null || code.isBlank()) {
            return new ComplexityResponse(0, 0);
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(code);
            ComplexityVisitor visitor = new ComplexityVisitor();
            visitor.visit(cu, null);
            int maxCyclomatic = visitor.getMaxCyclomatic();
            int cognitive = Math.max(1, maxCyclomatic / 2);
            return new ComplexityResponse(maxCyclomatic, cognitive);
        } catch (Exception e) {
            log.warn("JavaParser failed to parse source; falling back to heuristic: {}", e.getMessage());
            return fallbackHeuristic(code);
        }
    }

    private ComplexityResponse fallbackHeuristic(String code) {
        String cleaned = stripCommentsAndStrings(code == null ? "" : code);
        int cyclomatic = 1;
        String[] tokens = {"if(", "for(", "while(", "case ", "&&", "||", "catch(", "?:", "default:"};
        for (String t : tokens) {
            int idx = 0;
            while ((idx = cleaned.indexOf(t, idx)) != -1) {
                cyclomatic++;
                idx += t.length();
            }
        }
        int cognitive = Math.max(1, cyclomatic / 2);
        return new ComplexityResponse(cyclomatic, cognitive);
    }

    private static String stripCommentsAndStrings(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '"') {
                // skip string literal
                i++;
                while (i < n) {
                    char sc = code.charAt(i);
                    if (sc == '\\') { i += 2; continue; }
                    if (sc == '"') { i++; break; }
                    i++;
                }
                sb.append(' ');
            } else if (c == '\'') {
                // skip char literal
                i++;
                while (i < n && code.charAt(i) != '\'') i++;
                i++;
                sb.append(' ');
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                // skip line comment
                while (i < n && code.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                // skip block comment
                i += 2;
                while (i + 1 < n && !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) i++;
                i += 2;
                sb.append(' ');
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static class ComplexityVisitor extends VoidVisitorAdapter<Void> {
        private int current = 0;
        private int max = 0;

        @Override
        public void visit(MethodDeclaration md, Void arg) {
            int before = current;
            current = 1;
            super.visit(md, arg);
            if (current > max) max = current;
            current = before;
        }

        @Override
        public void visit(IfStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ForStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ForEachStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(WhileStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(DoStmt n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(SwitchEntry n, Void arg) {
            if (n.getLabels() != null && !n.getLabels().isEmpty()) {
                current += n.getLabels().size();
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(CatchClause n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(ConditionalExpr n, Void arg) {
            current++;
            super.visit(n, arg);
        }

        @Override
        public void visit(BinaryExpr n, Void arg) {
            if (n.getOperator() == BinaryExpr.Operator.AND || n.getOperator() == BinaryExpr.Operator.OR) {
                current++;
            }
            super.visit(n, arg);
        }

        public int getMaxCyclomatic() {
            return Math.max(1, max);
        }
    }
}
