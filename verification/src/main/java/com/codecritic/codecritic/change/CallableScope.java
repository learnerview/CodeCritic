package com.codecritic.change;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;

public final class CallableScope {

    private CallableScope() {
    }

    public static boolean belongsTo(Node node, CallableDeclaration<?> owner) {
        Node parent = node.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof CallableDeclaration<?>) {
                return parent == owner;
            }
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }
}
