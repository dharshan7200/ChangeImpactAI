package com.changeimpact.parser.language.ts;

import java.util.*;

/**
 * Pass-1 index of the types declared in a repository, used by the class-based languages (PHP, C#, Go, Ruby) to link
 * member calls: which methods and fields a type declares, the declared types of its fields and the return types of
 * its methods, and its supertypes. Lookups walk supertypes declared in the repository; nothing outside it is
 * assumed.
 */
public final class TypeIndex {

    public static final class TypeInfo {
        public final String qn;
        public final String kind;
        public final Set<String> methods = new HashSet<>();
        public final Set<String> staticMethods = new HashSet<>();
        public final Map<String, String> fieldTypes = new HashMap<>();
        public final Set<String> fields = new HashSet<>();
        public final Map<String, String> returnTypes = new HashMap<>();
        public final List<String> supertypes = new ArrayList<>();

        TypeInfo(String qn, String kind) {
            this.qn = qn;
            this.kind = kind;
        }
    }

    private final Map<String, TypeInfo> types = new HashMap<>();
    private final Map<String, List<String>> bySimpleName = new HashMap<>();
    private final Map<String, String> functions = new HashMap<>();
    private final Map<String, List<String>> functionsBySimpleName = new HashMap<>();

    public TypeInfo declare(String qn, String kind) {
        TypeInfo t = types.get(qn);
        if (t != null) return t; // first declaration wins (partial classes, reopened Ruby classes merge into it)
        t = new TypeInfo(qn, kind);
        types.put(qn, t);
        bySimpleName.computeIfAbsent(simple(qn), k -> new ArrayList<>()).add(qn);
        return t;
    }

    public void function(String qn, String simpleName) {
        functions.putIfAbsent(qn, simpleName);
        functionsBySimpleName.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(qn);
    }

    public boolean has(String qn) { return qn != null && types.containsKey(qn); }
    public TypeInfo get(String qn) { return qn == null ? null : types.get(qn); }
    public boolean hasFunction(String qn) { return functions.containsKey(qn); }
    public Collection<TypeInfo> all() { return types.values(); }

    /** The single type with this simple name, or null if none or ambiguous. */
    public String uniqueBySimpleName(String simple) {
        List<String> l = bySimpleName.getOrDefault(simple, List.of());
        return l.size() == 1 ? l.get(0) : null;
    }

    /** The single function with this simple name among {@code candidates}' filter, or null if none or ambiguous. */
    public String uniqueFunction(String simple) {
        List<String> l = functionsBySimpleName.getOrDefault(simple, List.of());
        return l.size() == 1 ? l.get(0) : null;
    }

    /** {@code Type#method} for the type that declares {@code method}, searching supertypes; null if not found. */
    public String method(String typeQn, String method) {
        return walk(typeQn, t -> t.methods.contains(method) || t.staticMethods.contains(method) ? t.qn + "#" + method : null);
    }

    public String field(String typeQn, String field) {
        return walk(typeQn, t -> t.fields.contains(field) ? t.qn + "#" + field : null);
    }

    /** Declared type of a field (searching supertypes), or null. */
    public String fieldType(String typeQn, String field) {
        return walk(typeQn, t -> t.fieldTypes.get(field));
    }

    public String returnType(String typeQn, String method) {
        return walk(typeQn, t -> t.returnTypes.get(method));
    }

    /** First supertype declared in the repository. */
    public String superclass(String typeQn) {
        TypeInfo t = types.get(typeQn);
        return t == null ? null : t.supertypes.stream().filter(types::containsKey).findFirst().orElse(null);
    }

    private <R> R walk(String typeQn, java.util.function.Function<TypeInfo, R> f) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        if (typeQn != null) queue.add(typeQn);
        while (!queue.isEmpty()) {
            String q = queue.poll();
            if (!seen.add(q) || seen.size() > 64) continue;
            TypeInfo t = types.get(q);
            if (t == null) continue;
            R r = f.apply(t);
            if (r != null) return r;
            queue.addAll(t.supertypes);
        }
        return null;
    }

    static String simple(String qn) {
        int i = Math.max(qn.lastIndexOf('.'), qn.lastIndexOf('#'));
        return i < 0 ? qn : qn.substring(i + 1);
    }
}
