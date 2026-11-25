package com.changeimpact.diff.semantic;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A detected change of one symbol. {@code base} is null for additions, {@code head} for removals.
 * {@code details} holds before/after evidence per change kind.
 */
public final class SymbolChange {

    private final SymbolSnapshot base;
    private final SymbolSnapshot head;
    private final EnumSet<ChangeKind> kinds = EnumSet.noneOf(ChangeKind.class);
    private final Map<String, Object> details = new LinkedHashMap<>();
    private boolean breaking;

    public SymbolChange(SymbolSnapshot base, SymbolSnapshot head) {
        this.base = base;
        this.head = head;
    }

    public SymbolChange add(ChangeKind kind) {
        kinds.add(kind);
        if (kind.isContractChange()) {
            breaking = true;
        }
        return this;
    }

    public SymbolChange markBreaking() {
        breaking = true;
        return this;
    }

    public SymbolChange detail(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public SymbolSnapshot current() {
        return head != null ? head : base;
    }

    public SymbolSnapshot getBase() {
        return base;
    }

    public SymbolSnapshot getHead() {
        return head;
    }

    public EnumSet<ChangeKind> getKinds() {
        return kinds;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public boolean isBreaking() {
        return breaking;
    }

    public double severity() {
        return kinds.stream().mapToDouble(ChangeKind::getSeverity).max().orElse(0);
    }

    public String qualifiedName() {
        return current().qualifiedName();
    }

    public String previousQualifiedName() {
        return base != null && head != null && !base.qualifiedName().equals(head.qualifiedName())
                ? base.qualifiedName() : null;
    }
}
