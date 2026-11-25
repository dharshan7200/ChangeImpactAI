package com.changeimpact.diff.semantic;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Compares the symbols of two commits and classifies what changed, independent of line positions,
 * formatting and comments (content hashes are computed without comments).
 *
 * <h2>Matching</h2>
 * <ol>
 *   <li>Symbols are matched by qualified name. Method/constructor names include the erased parameter
 *       list, so changing a signature looks like remove + add.</li>
 *   <li><b>Moved types</b>: a removed top-level type and an added top-level type with the same simple name
 *       are paired when Git reports the file as renamed, or when exactly one candidate has the same header
 *       hash. Their members are then matched under the new name.</li>
 *   <li><b>Signature changes</b>: when exactly one overload of {@code Type#name} disappears and exactly one
 *       appears, they are paired as {@link ChangeKind#PARAMETERS_CHANGED}. Otherwise they stay ADDED/REMOVED
 *       (we do not guess which overload became which).</li>
 *   <li>Members of an added or removed type are not reported individually; the type change covers them
 *       (impact traversal of a type already includes callers of its members).</li>
 * </ol>
 */
@Component
public class SemanticChangeDetector {

    private static final Map<String, Integer> VISIBILITY_RANK = Map.of(
            "PRIVATE", 0, "PACKAGE_PRIVATE", 1, "PROTECTED", 2, "PUBLIC", 3);

    private static final Set<String> REST_ANNOTATIONS = Set.of("RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "DeleteMapping", "PatchMapping", "ResponseStatus", "RestController", "Controller",
            "CrossOrigin", "ExceptionHandler");
    private static final Set<String> SECURITY_ANNOTATIONS = Set.of("PreAuthorize", "PostAuthorize", "Secured",
            "RolesAllowed", "PermitAll", "DenyAll", "PreFilter", "PostFilter", "EnableWebSecurity",
            "EnableMethodSecurity");
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of("Entity", "Table", "Column", "Id", "GeneratedValue",
            "OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "JoinColumn", "JoinTable", "Embedded", "Embeddable",
            "EmbeddedId", "Enumerated", "Transient", "Version", "MappedSuperclass", "Inheritance", "OrderBy",
            "ElementCollection", "Lob", "Convert");
    private static final Set<String> TRANSACTION_ANNOTATIONS = Set.of("Transactional");
    private static final Set<String> CONFIG_ANNOTATIONS = Set.of("Value", "ConfigurationProperties",
            "ConditionalOnProperty", "Profile", "PropertySource");

    /**
     * @param renamedFiles Git-detected file renames, old path → new path
     */
    public List<SymbolChange> detect(Collection<SymbolSnapshot> baseSymbols, Collection<SymbolSnapshot> headSymbols,
                                     Map<String, String> renamedFiles) {
        Map<String, SymbolSnapshot> base = new LinkedHashMap<>();
        Map<String, SymbolSnapshot> head = new LinkedHashMap<>();
        keyByName(baseSymbols, headSymbols, base, head);

        Map<String, String> movedTypes = detectMovedTypes(base, head, renamedFiles);

        // Re-key base symbols under their (possibly moved) new name.
        Map<String, SymbolSnapshot> baseByNewName = new LinkedHashMap<>();
        base.forEach((key, s) -> baseByNewName.put(rename(key, movedTypes), s));

        Set<String> removedTypes = baseByNewName.entrySet().stream()
                .filter(e -> e.getValue().isType() && !head.containsKey(e.getKey()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> addedTypes = head.entrySet().stream()
                .filter(e -> e.getValue().isType() && !baseByNewName.containsKey(e.getKey()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());

        List<SymbolChange> changes = new ArrayList<>();
        List<SymbolSnapshot> unmatchedBase = new ArrayList<>();
        List<SymbolSnapshot> unmatchedHead = new ArrayList<>();

        for (Map.Entry<String, SymbolSnapshot> e : baseByNewName.entrySet()) {
            SymbolSnapshot h = head.get(e.getKey());
            if (h != null) {
                SymbolChange change = compare(e.getValue(), h, movedTypes);
                if (!change.getKinds().isEmpty()) {
                    changes.add(change);
                }
            } else if (!insideAny(e.getKey(), removedTypes) || removedTypes.contains(e.getKey())) {
                unmatchedBase.add(e.getValue());
            }
        }
        for (Map.Entry<String, SymbolSnapshot> e : head.entrySet()) {
            if (!baseByNewName.containsKey(e.getKey())
                    && (!insideAny(e.getKey(), addedTypes) || addedTypes.contains(e.getKey()))) {
                unmatchedHead.add(e.getValue());
            }
        }

        pairSignatureChanges(unmatchedBase, unmatchedHead, movedTypes, changes);

        unmatchedBase.forEach(b -> changes.add(new SymbolChange(b, null).add(ChangeKind.REMOVED)));
        unmatchedHead.forEach(h -> changes.add(new SymbolChange(null, h).add(ChangeKind.ADDED)));

        changes.sort(Comparator.comparing(SymbolChange::qualifiedName)
                .thenComparing(c -> Objects.toString(c.current().filePath(), ""))
                .thenComparingInt(c -> c.current().startLine()));
        return changes;
    }

    // ------------------------------------------------------------------
    // Pairwise comparison
    // ------------------------------------------------------------------

    SymbolChange compare(SymbolSnapshot b, SymbolSnapshot h, Map<String, String> movedTypes) {
        SymbolChange change = new SymbolChange(b, h);

        if (h.isType() && movedTypes.containsKey(b.qualifiedName())) {
            change.add(ChangeKind.MOVED).detail("movedFrom", b.qualifiedName()).detail("movedTo", h.qualifiedName());
        }

        int vb = VISIBILITY_RANK.getOrDefault(b.visibility(), 1);
        int vh = VISIBILITY_RANK.getOrDefault(h.visibility(), 1);
        if (vh < vb) {
            change.add(ChangeKind.VISIBILITY_REDUCED);
        } else if (vh > vb) {
            change.add(ChangeKind.VISIBILITY_EXPANDED);
        }
        if (vh != vb) {
            change.detail("visibility", beforeAfter(b.visibility(), h.visibility()));
        }

        if (!b.isType() && !Objects.equals(norm(rename(b.returnType(), movedTypes)), norm(h.returnType()))) {
            change.add(h.isCallable() ? ChangeKind.RETURN_TYPE_CHANGED : ChangeKind.FIELD_TYPE_CHANGED)
                    .detail(h.isCallable() ? "returnType" : "fieldType", beforeAfter(b.returnType(), h.returnType()));
        }

        // languages without overloading (Python) keep the name when parameters change; Java's names carry the signature
        if (h.isCallable() && !h.qualifiedName().contains("(")
                && !Objects.equals(norm(b.parameters()), norm(h.parameters()))) {
            boolean compatible = onlyOptionalParametersAppended(b.parameters(), h.parameters());
            boolean sameShape = shape(b.parameters()).equals(shape(h.parameters()));
            change.add(!compatible ? ChangeKind.PARAMETERS_CHANGED
                            : sameShape ? ChangeKind.PARAMETER_ANNOTATIONS_CHANGED : ChangeKind.PARAMETERS_EXTENDED)
                    .detail("parameters", beforeAfter(b.parameters(), h.parameters()));
        }

        if (b.isStatic() != h.isStatic() || b.isAbstract() != h.isAbstract() || b.isFinal() != h.isFinal()) {
            change.add(ChangeKind.MODIFIERS_CHANGED)
                    .detail("modifiers", beforeAfter(modifiers(b), modifiers(h)));
            // static flips change call syntax; newly abstract/final forbids existing subclasses/overrides.
            if (b.isStatic() != h.isStatic() || (!b.isAbstract() && h.isAbstract()) || (!b.isFinal() && h.isFinal())) {
                change.markBreaking();
            }
        }

        if (b.isType()) {
            Set<String> before = b.supertypes().stream().map(s -> rename(s, movedTypes)).collect(Collectors.toSet());
            if (!before.equals(h.supertypes())) {
                Set<String> removed = new TreeSet<>(before);
                removed.removeAll(h.supertypes());
                Set<String> added = new TreeSet<>(h.supertypes());
                added.removeAll(before);
                change.add(ChangeKind.TYPE_HIERARCHY_CHANGED)
                        .detail("supertypesRemoved", removed).detail("supertypesAdded", added);
                if (!removed.isEmpty()) {
                    change.markBreaking(); // code relying on the removed supertype no longer compiles
                }
            }
        }

        compareAnnotations(b, h, change);

        boolean hashChanged = b.contentHash() != null && h.contentHash() != null && !b.contentHash().equals(h.contentHash());
        if (hashChanged && change.getKinds().stream().allMatch(k -> k == ChangeKind.MOVED)) {
            change.add(h.isType() ? ChangeKind.DECLARATION_CHANGED : ChangeKind.BODY_CHANGED);
        }
        return change;
    }

    private void compareAnnotations(SymbolSnapshot b, SymbolSnapshot h, SymbolChange change) {
        Set<String> before = annotationLines(b.annotationText());
        Set<String> after = annotationLines(h.annotationText());
        if (before.equals(after)) {
            return;
        }
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        Set<String> touched = new HashSet<>();
        removed.forEach(a -> touched.add(annotationName(a)));
        added.forEach(a -> touched.add(annotationName(a)));

        boolean categorised = false;
        if (touched.stream().anyMatch(REST_ANNOTATIONS::contains)) {
            change.add(ChangeKind.REST_MAPPING_CHANGED);
            categorised = true;
        }
        if (touched.stream().anyMatch(SECURITY_ANNOTATIONS::contains)) {
            change.add(ChangeKind.SECURITY_RULE_CHANGED);
            categorised = true;
        }
        if (touched.stream().anyMatch(ENTITY_ANNOTATIONS::contains)) {
            change.add(ChangeKind.ENTITY_MAPPING_CHANGED);
            categorised = true;
        }
        if (touched.stream().anyMatch(TRANSACTION_ANNOTATIONS::contains)) {
            change.add(ChangeKind.TRANSACTION_BOUNDARY_CHANGED);
            categorised = true;
        }
        if (touched.stream().anyMatch(CONFIG_ANNOTATIONS::contains)) {
            change.add(ChangeKind.CONFIG_BINDING_CHANGED);
            categorised = true;
        }
        Set<String> other = new HashSet<>(touched);
        other.removeAll(REST_ANNOTATIONS);
        other.removeAll(SECURITY_ANNOTATIONS);
        other.removeAll(ENTITY_ANNOTATIONS);
        other.removeAll(TRANSACTION_ANNOTATIONS);
        other.removeAll(CONFIG_ANNOTATIONS);
        if (!other.isEmpty() || !categorised) {
            change.add(ChangeKind.ANNOTATIONS_CHANGED);
        }
        change.detail("annotationsRemoved", removed).detail("annotationsAdded", added);
    }

    // ------------------------------------------------------------------
    // Moved types and signature pairing
    // ------------------------------------------------------------------

    Map<String, String> detectMovedTypes(Map<String, SymbolSnapshot> base, Map<String, SymbolSnapshot> head,
                                         Map<String, String> renamedFiles) {
        List<SymbolSnapshot> removed = base.values().stream()
                .filter(s -> s.isType() && s.parentQualifiedName() == null && !head.containsKey(s.qualifiedName()))
                .toList();
        List<SymbolSnapshot> added = head.values().stream()
                .filter(s -> s.isType() && s.parentQualifiedName() == null && !base.containsKey(s.qualifiedName()))
                .toList();

        Map<String, String> moved = new HashMap<>();
        Set<String> usedTargets = new HashSet<>();
        for (SymbolSnapshot r : removed) {
            List<SymbolSnapshot> sameName = added.stream()
                    .filter(a -> a.simpleName().equals(r.simpleName()) && a.symbolType() == r.symbolType()
                            && !usedTargets.contains(a.qualifiedName()))
                    .toList();
            String renamedTo = renamedFiles.get(r.filePath());
            Optional<SymbolSnapshot> byFile = sameName.stream().filter(a -> a.filePath().equals(renamedTo)).findFirst();
            Optional<SymbolSnapshot> pick = byFile.isPresent() ? byFile
                    : sameName.size() == 1 && Objects.equals(sameName.get(0).contentHash(), r.contentHash())
                    ? Optional.of(sameName.get(0)) : Optional.empty();
            pick.ifPresent(a -> {
                moved.put(r.qualifiedName(), a.qualifiedName());
                usedTargets.add(a.qualifiedName());
            });
        }
        return moved;
    }

    private void pairSignatureChanges(List<SymbolSnapshot> unmatchedBase, List<SymbolSnapshot> unmatchedHead,
                                      Map<String, String> movedTypes, List<SymbolChange> changes) {
        Map<String, List<SymbolSnapshot>> baseByKey = unmatchedBase.stream().filter(SymbolSnapshot::isCallable)
                .collect(Collectors.groupingBy(s -> rename(s.callableKey(), movedTypes)));
        Map<String, List<SymbolSnapshot>> headByKey = unmatchedHead.stream().filter(SymbolSnapshot::isCallable)
                .collect(Collectors.groupingBy(SymbolSnapshot::callableKey));

        for (Map.Entry<String, List<SymbolSnapshot>> e : baseByKey.entrySet()) {
            List<SymbolSnapshot> heads = headByKey.get(e.getKey());
            if (e.getValue().size() == 1 && heads != null && heads.size() == 1) {
                SymbolSnapshot b = e.getValue().get(0);
                SymbolSnapshot h = heads.get(0);
                SymbolChange change = compare(b, h, movedTypes);
                change.getKinds().remove(ChangeKind.BODY_CHANGED); // the signature change explains the hash change
                change.add(ChangeKind.PARAMETERS_CHANGED)
                        .detail("parameters", beforeAfter(b.parameters(), h.parameters()));
                changes.add(change);
                unmatchedBase.remove(b);
                unmatchedHead.remove(h);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Keys the symbols of both commits by qualified name. Declarations that share a name (an enum whose constants
     * override the same method, local or anonymous classes, the same type in two modules) get distinct keys and are
     * paired across the commits deterministically: identical content first, then in source order. Before, the first
     * row per name won, so the pairing followed the database row order and the same comparison could report
     * different changes from run to run.
     */
    static void keyByName(Collection<SymbolSnapshot> baseSymbols, Collection<SymbolSnapshot> headSymbols,
                          Map<String, SymbolSnapshot> base, Map<String, SymbolSnapshot> head) {
        Map<String, List<SymbolSnapshot>> byNameBase = groupInSourceOrder(baseSymbols);
        Map<String, List<SymbolSnapshot>> byNameHead = groupInSourceOrder(headSymbols);
        Set<String> names = new TreeSet<>(byNameBase.keySet());
        names.addAll(byNameHead.keySet());
        for (String name : names) {
            List<SymbolSnapshot> bs = byNameBase.getOrDefault(name, List.of());
            List<SymbolSnapshot> hs = byNameHead.getOrDefault(name, List.of());
            if (bs.size() <= 1 && hs.size() <= 1) {
                if (!bs.isEmpty()) base.put(name, bs.get(0));
                if (!hs.isEmpty()) head.put(name, hs.get(0));
                continue;
            }
            boolean[] usedBase = new boolean[bs.size()];
            boolean[] usedHead = new boolean[hs.size()];
            List<int[]> pairs = new ArrayList<>();
            for (int i = 0; i < bs.size(); i++) {               // unchanged declarations find their twin
                String hash = bs.get(i).contentHash();
                for (int j = 0; hash != null && j < hs.size(); j++) {
                    if (!usedHead[j] && hash.equals(hs.get(j).contentHash())) {
                        usedBase[i] = usedHead[j] = true;
                        pairs.add(new int[]{i, j});
                        break;
                    }
                }
            }
            int j = 0;
            for (int i = 0; i < bs.size(); i++) {               // the rest pair up in source order
                if (usedBase[i]) continue;
                while (j < hs.size() && usedHead[j]) j++;
                if (j == hs.size()) break;
                usedBase[i] = usedHead[j] = true;
                pairs.add(new int[]{i, j});
            }
            pairs.sort(Comparator.comparingInt(p -> p[0]));
            int k = 0;
            for (int[] p : pairs) {
                String key = duplicateKey(name, k++);
                base.put(key, bs.get(p[0]));
                head.put(key, hs.get(p[1]));
            }
            for (int i = 0; i < bs.size(); i++) if (!usedBase[i]) base.put(duplicateKey(name, k++), bs.get(i));
            for (int h = 0; h < hs.size(); h++) if (!usedHead[h]) head.put(duplicateKey(name, k++), hs.get(h));
        }
    }

    private static Map<String, List<SymbolSnapshot>> groupInSourceOrder(Collection<SymbolSnapshot> symbols) {
        return symbols.stream()
                .sorted(Comparator.comparing(SymbolSnapshot::qualifiedName)
                        .thenComparing(s -> Objects.toString(s.filePath(), ""))
                        .thenComparingInt(SymbolSnapshot::startLine)
                        .thenComparing(s -> Objects.toString(s.contentHash(), "")))
                .collect(Collectors.groupingBy(SymbolSnapshot::qualifiedName, LinkedHashMap::new, Collectors.toList()));
    }

    /** The first declaration keeps its name; further ones get an internal suffix that never matches a real name. */
    private static String duplicateKey(String name, int index) {
        return index == 0 ? name : name + "\u0000" + index;
    }

    /** Rewrites a name whose (outer) type moved: {@code a.Old#m()} → {@code b.Old#m()}, {@code a.Old$In} → {@code b.Old$In}. */
    static String rename(String name, Map<String, String> movedTypes) {
        if (name == null || movedTypes.isEmpty()) {
            return name;
        }
        for (Map.Entry<String, String> m : movedTypes.entrySet()) {
            String from = m.getKey();
            if (name.equals(from)) {
                return m.getValue();
            }
            if (name.startsWith(from + "$") || name.startsWith(from + "#")) {
                return m.getValue() + name.substring(from.length());
            }
        }
        return name;
    }

    private static boolean insideAny(String qualifiedName, Set<String> types) {
        for (String t : types) {
            if (qualifiedName.equals(t) || qualifiedName.startsWith(t + "#") || qualifiedName.startsWith(t + "$")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> annotationLines(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return text.lines().map(String::trim).filter(l -> !l.isEmpty()).collect(Collectors.toSet());
    }

    static String annotationName(String annotation) {
        String a = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        int paren = a.indexOf('(');
        String name = paren >= 0 ? a.substring(0, paren) : a;
        return name.substring(name.lastIndexOf('.') + 1).trim();
    }

    private static String norm(String type) {
        return type == null ? null : type.replaceAll("\\s+", "");
    }

    private static String modifiers(SymbolSnapshot s) {
        List<String> m = new ArrayList<>();
        if (s.isStatic()) m.add("static");
        if (s.isAbstract()) m.add("abstract");
        if (s.isFinal()) m.add("final");
        return String.join(" ", m);
    }

    private static Map<String, Object> beforeAfter(Object before, Object after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", before);
        m.put("after", after);
        return m;
    }

    /**
     * True when every existing call still works: the old positional parameters are a prefix of the new ones (same
     * names, same order), every added parameter has a default or is variadic, and no {@code *args}/{@code **kwargs}
     * (or keyword-only marker) of the old list disappeared. Python parameter lists ({@code name: type = default}).
     */
    static boolean onlyOptionalParametersAppended(String before, String after) {
        List<String> b = splitParams(before), a = splitParams(after);
        List<String> bPos = b.stream().filter(p -> !p.startsWith("*") && !p.equals("/")).toList();
        List<String> aPos = a.stream().filter(p -> !p.startsWith("*") && !p.equals("/")).toList();
        if (aPos.size() < bPos.size()) return false;
        for (int i = 0; i < bPos.size(); i++) if (!paramName(bPos.get(i)).equals(paramName(aPos.get(i)))) return false;
        if (aPos.subList(bPos.size(), aPos.size()).stream().anyMatch(p -> !p.contains("="))) return false;
        Set<String> aStar = a.stream().filter(p -> p.startsWith("*")).map(SemanticChangeDetector::paramName).collect(Collectors.toSet());
        return b.stream().filter(p -> p.startsWith("*")).map(SemanticChangeDetector::paramName).allMatch(aStar::contains);
    }

    /** Names in order, each marked with whether it has a default: equal shapes differ only in annotations. */
    static List<String> shape(String params) {
        return splitParams(params).stream().map(p -> paramName(p) + (p.contains("=") ? "=" : "")).toList();
    }

    private static String paramName(String p) {
        String n = p;
        int cut = n.length();
        for (char c : new char[]{':', '='}) { int i = n.indexOf(c); if (i >= 0) cut = Math.min(cut, i); }
        return n.substring(0, cut).strip();
    }

    private static List<String> splitParams(String params) {
        List<String> out = new ArrayList<>();
        if (params == null || params.isBlank()) return out;
        int depth = 0, start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) { out.add(params.substring(start, i).strip()); start = i + 1; }
        }
        out.add(params.substring(start).strip());
        return out;
    }

}
