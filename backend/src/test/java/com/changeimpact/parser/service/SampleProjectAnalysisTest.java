package com.changeimpact.parser.service;

import com.changeimpact.parser.java.JavaAstParser;
import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.ExtractedDependency;
import com.changeimpact.parser.model.ExtractedSymbol;
import com.changeimpact.parser.model.ParsedSourceFile;
import com.changeimpact.parser.model.ResolutionStatus;
import com.changeimpact.parser.model.SymbolType;
import com.changeimpact.symbols.entity.Symbol;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check of the deterministic analysis on a small multi-package sample project
 * ({@code src/test/resources/sample-projects/shop}): parsing, symbol naming and linking of
 * dependencies to internal symbols exactly as {@link CodeAnalysisService} does it.
 */
@DisplayName("Static analysis of the 'shop' sample project")
class SampleProjectAnalysisTest {

    private static final String P = "com.shop.";

    private static List<ExtractedSymbol> symbols;
    /** Every extracted dependency together with the link CodeAnalysisService would create. */
    private static List<Linked> linked;

    record Linked(ExtractedDependency dep, CodeAnalysisService.TargetMatch match) {}

    @BeforeAll
    static void analyzeSampleProject() throws IOException, URISyntaxException {
        Path root = Paths.get(Objects.requireNonNull(
                SampleProjectAnalysisTest.class.getResource("/sample-projects/shop")).toURI());
        JavaAstParser astParser = new JavaAstParser();
        JavaParser parser = astParser.createConfiguredParser(root);

        List<ParsedSourceFile> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> astParser.parseSourceFile(parser, root, p).ifPresent(files::add));
        }
        assertEquals(8, files.size(), "all sample files must parse");

        symbols = files.stream().flatMap(f -> f.getSymbols().stream()).toList();
        Map<String, Symbol> byKey = new HashMap<>();
        for (ExtractedSymbol s : symbols) {
            byKey.put(CodeAnalysisService.lookupKey(s.getQualifiedName()), Symbol.builder()
                    .id(UUID.randomUUID()).qualifiedName(s.getQualifiedName()).symbolType(s.getSymbolType()).build());
        }
        linked = files.stream().flatMap(f -> f.getDependencies().stream())
                .map(d -> new Linked(d, CodeAnalysisService.matchTarget(d, byKey)))
                .toList();
    }

    private static Optional<Linked> find(String source, DependencyType type, String targetQn) {
        return linked.stream()
                .filter(l -> l.dep().getSourceQualifiedName().equals(P + source))
                .filter(l -> l.dep().getDependencyType() == type)
                .filter(l -> l.match().target() != null && l.match().target().getQualifiedName().equals(P + targetQn))
                .findFirst();
    }

    private static void assertLinked(String source, DependencyType type, String target, ResolutionStatus status) {
        Linked l = find(source, type, target).orElseThrow(() -> new AssertionError(
                "missing " + type + " edge " + source + " -> " + target + "\nedges from source: " +
                linked.stream().filter(x -> x.dep().getSourceQualifiedName().equals(P + source))
                        .map(x -> x.dep().getDependencyType() + " " + x.dep().getTargetQualifiedName()
                                + " [" + x.match().status() + "]").toList()));
        assertEquals(status, l.match().status(), "resolution status of " + source + " -> " + target);
    }

    @Test
    @DisplayName("extracts types, overloaded methods with erased signatures, nested classes, enum constants, record components")
    void extractsSymbolsWithStableNames() {
        Set<String> names = new HashSet<>();
        symbols.forEach(s -> names.add(s.getQualifiedName()));

        assertTrue(names.contains(P + "service.OrderService#placeOrder(String)"));
        assertTrue(names.contains(P + "service.OrderService#placeOrder(String,int)"));
        assertTrue(names.contains(P + "service.OrderService#summarize(String[])"), "varargs are normalised to []");
        assertTrue(names.contains(P + "domain.Order$Line"));
        assertTrue(names.contains(P + "domain.Order$Line#<init>(String,int)"));
        assertTrue(names.contains(P + "domain.OrderStatus#PAID"), "enum constants are symbols");
        assertTrue(names.contains(P + "domain.OrderSummary#status"), "record components are symbols");
        assertEquals(SymbolType.RECORD, symbols.stream()
                .filter(s -> s.getQualifiedName().equals(P + "domain.OrderSummary")).findFirst().orElseThrow().getSymbolType());
    }

    @Test
    @DisplayName("method calls link to the exact overload, not to the declaring class")
    void methodCallsLinkToExactOverload() {
        assertLinked("web.OrderController#create(String,int)", DependencyType.METHOD_CALL,
                "service.OrderService#placeOrder(String,int)", ResolutionStatus.RESOLVED);
        assertLinked("service.OrderService#placeOrder(String)", DependencyType.METHOD_CALL,
                "service.OrderService#placeOrder(String,int)", ResolutionStatus.RESOLVED);
        assertLinked("web.OrderController#bulk(String,String)", DependencyType.METHOD_CALL,
                "service.OrderService#summarize(String[])", ResolutionStatus.RESOLVED);
        assertLinked("service.OrderService#placeOrder(String,int)", DependencyType.METHOD_CALL,
                "payment.PaymentGateway#charge(String,long)", ResolutionStatus.RESOLVED);
        assertLinked("web.OrderController#find(String)", DependencyType.METHOD_CALL,
                "repo.OrderRepository#findById(String)", ResolutionStatus.RESOLVED);
    }

    @Test
    @DisplayName("constructor calls, including nested classes, link to the exact constructor")
    void constructorCallsLinkToExactConstructor() {
        assertLinked("service.OrderService#placeOrder(String,int)", DependencyType.CONSTRUCTOR_CALL,
                "domain.Order#<init>(String)", ResolutionStatus.RESOLVED);
        assertLinked("domain.Order#addLine(String,int)", DependencyType.CONSTRUCTOR_CALL,
                "domain.Order$Line#<init>(String,int)", ResolutionStatus.RESOLVED);
    }

    @Test
    @DisplayName("type references (fields, parameters, generic return types, inheritance) are resolved")
    void typeReferencesAreResolved() {
        assertLinked("service.OrderService#repository", DependencyType.FIELD_TYPE,
                "repo.OrderRepository", ResolutionStatus.RESOLVED);
        assertLinked("service.OrderService#listOrders()", DependencyType.RETURN_TYPE,
                "domain.Order", ResolutionStatus.RESOLVED);
        // Map<String, List<OrderSummary>>: the nested generic argument is found too.
        assertLinked("service.OrderService#summarize(String[])", DependencyType.RETURN_TYPE,
                "domain.OrderSummary", ResolutionStatus.RESOLVED);
        assertLinked("payment.StripeGateway", DependencyType.IMPLEMENTS,
                "payment.PaymentGateway", ResolutionStatus.RESOLVED);
        assertLinked("web.OrderController", DependencyType.IMPORT,
                "repo.OrderRepository", ResolutionStatus.RESOLVED);
    }

    @Test
    @DisplayName("field and enum-constant accesses are recorded")
    void fieldAccessesAreRecorded() {
        assertLinked("domain.Order#markPaid()", DependencyType.FIELD_ACCESS,
                "domain.OrderStatus#PAID", ResolutionStatus.RESOLVED);
        assertLinked("service.OrderService#placeOrder(String,int)", DependencyType.FIELD_ACCESS,
                "service.OrderService#paymentGateway", ResolutionStatus.RESOLVED);
    }

    @Test
    @DisplayName("JDK types are EXTERNAL and unknown library calls stay UNRESOLVED and unlinked")
    void externalAndUnresolvedAreHonest() {
        Linked listReturn = linked.stream()
                .filter(l -> l.dep().getSourceQualifiedName().equals(P + "service.OrderService#listOrders()"))
                .filter(l -> l.dep().getTargetQualifiedName().equals("java.util.List"))
                .findFirst().orElseThrow();
        assertNull(listReturn.match().target());
        assertEquals(ResolutionStatus.EXTERNAL, listReturn.match().status());

        List<Linked> audit = linked.stream()
                .filter(l -> l.dep().getSourceQualifiedName().equals(P + "service.OrderService#auditTrail()"))
                .filter(l -> l.dep().getDependencyType() == DependencyType.METHOD_CALL)
                .toList();
        assertFalse(audit.isEmpty());
        audit.forEach(l -> {
            assertNull(l.match().target(), "unresolved calls must never be linked to a symbol");
            assertEquals(ResolutionStatus.UNRESOLVED, l.match().status());
        });
    }

    @Test
    @DisplayName("no dependency is reported RESOLVED without the solver having resolved it")
    void resolvedOnlyWhenSolverResolved() {
        linked.stream()
                .filter(l -> l.match().status() == ResolutionStatus.RESOLVED)
                .forEach(l -> assertEquals(ResolutionStatus.RESOLVED, l.dep().getResolutionStatus(),
                        "RESOLVED link from a non-resolved extraction: " + l.dep().getTargetQualifiedName()));
    }
}
