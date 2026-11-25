package com.github.javaparser.symbolsolver.javaparsermodel.contexts;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;

/**
 * ChangeImpact AI addition used by the patched {@link BlockStmtContext} and {@link StatementContext}: position of a
 * statement by object identity (O(n), no deep comparison). Falls back to {@link NodeList#indexOf} when the instance is
 * not in the list, preserving upstream behaviour.
 */
final class IdentityIndex {

    private IdentityIndex() {}

    static int of(NodeList<? extends Node> list, Node node) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == node) return i;
        }
        return list.indexOf(node);
    }
}
