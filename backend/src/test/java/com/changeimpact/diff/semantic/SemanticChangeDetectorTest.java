package com.changeimpact.diff.semantic;

import com.changeimpact.parser.java.JavaAstParser;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.ExtractedSymbol;
import com.changeimpact.parser.model.ParsedSourceFile;
import com.changeimpact.parser.model.ResolutionStatus;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.changeimpact.diff.semantic.ChangeKind.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantic diff of the "shop" sample project (v1) against "shop-v2", which contains a known set of edits.
 */
@DisplayName("Semantic change detection: shop v1 → shop v2")
class SemanticChangeDetectorTest {

    private static final String P = "com.shop.";
    private static Map<String, SymbolChange> changes;

    @BeforeAll
    static void detect() throws Exception {
        List<SymbolSnapshot> v1 = snapshots("/sample-projects/shop");
        List<SymbolSnapshot> v2 = snapshots("/sample-projects/shop-v2");
        Map<String, String> renames = Map.of(
                "src/main/java/com/shop/payment/StripeGateway.java",
                "src/main/java/com/shop/payment/stripe/StripeGateway.java");
        changes = new SemanticChangeDetector().detect(v1, v2, renames).stream()
                .collect(Collectors.toMap(SymbolChange::qualifiedName, c -> c));
    }

    private static SymbolChange change(String qn) {
        SymbolChange c = changes.get(P + qn);
        assertNotNull(c, "expected a change for " + qn + " but got: " + changes.keySet());
        return c;
    }

    @Test
    @DisplayName("comment/formatting-only edits are not reported")
    void cosmeticChangesIgnored() {
        assertFalse(changes.containsKey(P + "domain.Order#markPaid()"),
                "markPaid only changed comments and whitespace: " + changes.get(P + "domain.Order#markPaid()"));
        assertFalse(changes.containsKey(P + "domain.Order"));
    }

    @Test
    @DisplayName("changed parameter list is paired as PARAMETERS_CHANGED, not remove + add")
    void parameterChange() {
        SymbolChange c = change("service.OrderService#placeOrder(String,int,String)");
        assertTrue(c.getKinds().contains(PARAMETERS_CHANGED), c.getKinds().toString());
        assertTrue(c.isBreaking());
        assertEquals(P + "service.OrderService#placeOrder(String,int)", c.previousQualifiedName());
        assertFalse(changes.containsKey(P + "service.OrderService#placeOrder(String,int)"));
    }

    @Test
    @DisplayName("implementation-only edits are BODY_CHANGED and not breaking")
    void bodyChange() {
        SymbolChange c = change("service.OrderService#placeOrder(String)");
        assertEquals(EnumSet.of(BODY_CHANGED), c.getKinds());
        assertFalse(c.isBreaking());
        assertEquals(EnumSet.of(BODY_CHANGED), change("web.OrderController#create(String,int)").getKinds());
    }

    @Test
    @DisplayName("return type, modifier, visibility and annotation changes are classified")
    void contractChanges() {
        assertTrue(change("service.OrderService#listOrders()").getKinds().contains(RETURN_TYPE_CHANGED));
        SymbolChange field = change("service.OrderService#paymentGateway");
        assertTrue(field.getKinds().contains(MODIFIERS_CHANGED));
        assertFalse(field.isBreaking(), "removing final is not breaking");
        assertTrue(change("domain.Order$Line").getKinds().contains(VISIBILITY_REDUCED));
        assertTrue(change("domain.Order$Line").isBreaking());
        SymbolChange rest = change("web.OrderController#find(String)");
        assertTrue(rest.getKinds().contains(REST_MAPPING_CHANGED), rest.getKinds().toString());
    }

    @Test
    @DisplayName("moved type is MOVED (members follow it), removed method REMOVED, new type ADDED once")
    void structuralChanges() {
        SymbolChange moved = change("payment.stripe.StripeGateway");
        assertTrue(moved.getKinds().contains(MOVED));
        assertEquals(P + "payment.StripeGateway", moved.getDetails().get("movedFrom"));
        assertFalse(changes.containsKey(P + "payment.stripe.StripeGateway#charge(String,long)"),
                "unchanged members of a moved type are not reported");

        assertEquals(EnumSet.of(REMOVED), change("repo.OrderRepository#findAll()").getKinds());
        assertEquals(EnumSet.of(ADDED), change("domain.OrderStatus#CANCELLED").getKinds());
        assertEquals(EnumSet.of(ADDED), change("service.DiscountService").getKinds());
        assertFalse(changes.containsKey(P + "service.DiscountService#discountPercent(String)"),
                "members of an added type are covered by the type");
    }

    @Test
    @DisplayName("nothing else is reported")
    void noUnexpectedChanges() {
        Set<String> expected = Set.of(
                "service.OrderService#placeOrder(String,int,String)", "service.OrderService#placeOrder(String)",
                "service.OrderService#listOrders()", "service.OrderService#paymentGateway",
                "web.OrderController#create(String,int)", "web.OrderController#find(String)",
                "domain.Order$Line", "payment.stripe.StripeGateway", "repo.OrderRepository#findAll()",
                "domain.OrderStatus#CANCELLED", "service.DiscountService");
        Set<String> actual = changes.keySet().stream().map(k -> k.substring(P.length())).collect(Collectors.toSet());
        assertEquals(new TreeSet<>(expected), new TreeSet<>(actual));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("same-named declarations (enum constant bodies) pair by content, then source order — not by row order")
    void duplicateNamesPairDeterministically() {
        String qn = "a.StopWatch$State#isStarted()";
        List<SymbolSnapshot> base = List.of(method(qn, 10, "h1"), method(qn, 20, "h2"), method(qn, 30, "h3"),
                method("a.StopWatch#start()", 40, "s"));
        List<SymbolSnapshot> head = List.of(method(qn, 12, "h1"), method(qn, 22, "h2-edited"), method(qn, 32, "h3"),
                method("a.StopWatch#start()", 42, "s"));
        Set<String> outcomes = new TreeSet<>();
        for (List<SymbolSnapshot> b : permutations(base)) {
            for (List<SymbolSnapshot> h : permutations(head)) {
                outcomes.add(describe(new SemanticChangeDetector().detect(b, h, Map.of())));
            }
        }
        // exactly one change: the edited second constant, whatever order the rows arrive in
        assertEquals(Set.of("a.StopWatch$State#isStarted() 20->22 [BODY_CHANGED]"), outcomes);

        // a constant body removed: the declaration without a twin is the removed one
        List<SymbolSnapshot> fewer = List.of(method(qn, 12, "h1"), method(qn, 32, "h3"));
        Set<String> removed = new TreeSet<>();
        for (List<SymbolSnapshot> b : permutations(base.subList(0, 3))) {
            removed.add(describe(new SemanticChangeDetector().detect(b, fewer.reversed(), Map.of())));
        }
        assertEquals(Set.of("a.StopWatch$State#isStarted() 20->- [REMOVED]"), removed);
    }

    private static SymbolSnapshot method(String qn, int line, String hash) {
        String simple = qn.substring(qn.indexOf('#') + 1, qn.indexOf('('));
        return new SymbolSnapshot(UUID.randomUUID(), qn, simple, com.changeimpact.parser.model.SymbolType.METHOD,
                qn.substring(0, qn.indexOf('#')), "public", "boolean", "", false, false, false, null, hash, Set.of(),
                "src/main/java/a/StopWatch.java", line, line + 3, false);
    }

    private static String describe(List<SymbolChange> changes) {
        return changes.stream().map(c -> c.qualifiedName() + " "
                        + (c.getBase() == null ? "-" : c.getBase().startLine()) + "->"
                        + (c.getHead() == null ? "-" : c.getHead().startLine()) + " " + c.getKinds())
                .collect(Collectors.joining("; "));
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) return List.of(items);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T first = rest.remove(i);
            for (List<T> p : permutations(rest)) {
                List<T> l = new ArrayList<>();
                l.add(first);
                l.addAll(p);
                out.add(l);
            }
        }
        return out;
    }

    static List<SymbolSnapshot> snapshots(String resource) throws Exception {
        Path root = Paths.get(Objects.requireNonNull(SemanticChangeDetectorTest.class.getResource(resource)).toURI());
        JavaAstParser astParser = new JavaAstParser();
        JavaParser parser = astParser.createConfiguredParser(root);
        List<ParsedSourceFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> astParser.parseSourceFile(parser, root, p).ifPresent(files::add));
        }
        List<SymbolSnapshot> out = new ArrayList<>();
        for (ParsedSourceFile f : files) {
            Map<String, Set<String>> supertypes = new HashMap<>();
            f.getDependencies().stream()
                    .filter(d -> d.getDependencyType() == DependencyType.EXTENDS || d.getDependencyType() == DependencyType.IMPLEMENTS)
                    .filter(d -> d.getResolutionStatus() == ResolutionStatus.RESOLVED)
                    .forEach(d -> supertypes.computeIfAbsent(d.getSourceQualifiedName(), k -> new TreeSet<>())
                            .add(d.getTargetQualifiedName()));
            for (ExtractedSymbol s : f.getSymbols()) {
                out.add(new SymbolSnapshot(UUID.randomUUID(), s.getQualifiedName(), s.getSimpleName(), s.getSymbolType(),
                        s.getParentQualifiedName(), s.getVisibility(), s.getReturnType(), s.getParameters(),
                        s.isStatic(), s.isAbstract(), s.isFinal(), s.getAnnotationText(), s.getContentHash(),
                        supertypes.getOrDefault(s.getQualifiedName(), Set.of()), f.getRelativePath(),
                        s.getStartLine(), s.getEndLine(), f.isTest()));
            }
        }
        return out;
    }

    @Test
    void pythonParameterCompatibility() {
        assertTrue(SemanticChangeDetector.onlyOptionalParametersAppended("url, **kwargs", "url, params=None, **kwargs"));
        assertTrue(SemanticChangeDetector.onlyOptionalParametersAppended("event", "event, level=\"info\""));
        assertTrue(SemanticChangeDetector.onlyOptionalParametersAppended("a: int", "a: int, *args, b: str = 'x'"));
        assertFalse(SemanticChangeDetector.onlyOptionalParametersAppended("a, b", "a, c=1, b=2"), "b moved");
        assertFalse(SemanticChangeDetector.onlyOptionalParametersAppended("a", "a, b"), "new required parameter");
        assertFalse(SemanticChangeDetector.onlyOptionalParametersAppended("a, **kwargs", "a"), "**kwargs removed");
        assertFalse(SemanticChangeDetector.onlyOptionalParametersAppended("a, b=1", "a"), "parameter removed");
        assertTrue(SemanticChangeDetector.onlyOptionalParametersAppended("method: str, url: Url", "method: str, url: Url | str"), "annotation only");
        assertEquals(SemanticChangeDetector.shape("method: str, url: Url = None"), SemanticChangeDetector.shape("method: int, url: str = 'x'"));
        assertNotEquals(SemanticChangeDetector.shape("url"), SemanticChangeDetector.shape("url, params=None"));
    }

}
