package com.changeimpact.parser.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.logic.MethodResolutionLogic;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the patched {@code MethodResolutionLogic} (see the header of that file). Expected answers are what javac
 * selects; every call is also resolved with the candidate overloads in 40 shuffled orders, because upstream JavaParser
 * 3.28.2 picked a different (often wrong) overload depending on the hash-based candidate order.
 */
@DisplayName("JavaParser overload resolution (patched): javac's choice, independent of candidate order")
class OverloadResolutionTest {

    @TempDir
    static Path root;
    static JavaParser parser;
    static CombinedTypeSolver typeSolver;
    static Map<String, MethodCallExpr> calls = new LinkedHashMap<>();

    @BeforeAll
    static void parse() throws Exception {
        Path src = root.resolve("src/main/java/p");
        Files.createDirectories(src);
        Files.writeString(src.resolve("S.java"), """
                package p;
                public class S {
                  public static byte[] sort(byte[] a) { return a; }
                  public static double[] sort(double[] a) { return a; }
                  public static float[] sort(float[] a) { return a; }
                  public static long[] sort(long[] a) { return a; }
                  public static int[] sort(int[] a) { return a; }
                  public static <T> T[] sort(T[] a) { return a; }
                  public static String lv(long... a) { return ""; }
                  public static String dv(String s, double... a) { return ""; }
                  public static String nv(Number... a) { return ""; }
                  public static String ov(Object... a) { return ""; }
                  public static String w(long a) { return ""; }
                  public static String w(double a) { return ""; }
                  public static String r(Object[] a) { return ""; }
                  public static String r(String[] a) { return ""; }
                  public static String m(int[][] a) { return ""; }
                  public static String m(long[][] a) { return ""; }
                  public static String box(Integer[] a) { return ""; }
                }
                """);
        Files.writeString(src.resolve("U.java"), """
                package p;
                class U {
                  void t() {
                    float[] f = {1}; byte[] b = {1}; int[] i = {1};
                    S.sort(f); S.sort((float[]) null); S.sort(b); S.sort(i); S.sort(new String[0]);
                    S.lv(1, 2); S.lv(1); S.lv(); S.lv(1L, 2); S.lv(new long[0]);
                    S.dv("x", 1, 2f); S.dv("x", 1); S.nv(1, 2.0); S.ov(1, "a"); S.ov(i);
                    S.w(3); S.w(3f); S.r(new String[0]); S.r(new Object[0]); S.m(new int[0][]);
                    S.box(i); S.lv(i);
                  }
                }
                """);
        JavaAstParser astParser = new JavaAstParser();
        parser = astParser.createConfiguredParser(root);
        typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false),
                new JavaParserTypeSolver(root.resolve("src/main/java").toFile()));
        CompilationUnit cu = parser.parse(src.resolve("U.java")).getResult().orElseThrow();
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) calls.put(call.toString(), call);
        assertEquals(22, calls.size(), calls.keySet().toString());
    }

    @Test
    @DisplayName("primitive arrays are not widened: m(byte[]) is not m(int[]), m(float[]) is not m(double[])")
    void primitiveArrays() {
        assertResolves("S.sort(f)", "sort(float[])");
        assertResolves("S.sort((float[]) null)", "sort(float[])");
        assertResolves("S.sort(b)", "sort(byte[])");      // upstream: ambiguous or int[]/long[]/float[]/double[]
        assertResolves("S.sort(i)", "sort(int[])");       // upstream: ambiguous or long[]/float[]/double[]
        assertResolves("S.sort(new String[0])", "sort(T[])");
        assertResolves("S.m(new int[0][])", "m(int[][])");
    }

    @Test
    @DisplayName("variable-arity calls still convert each value (m(1, 2) for m(long...))")
    void varargsElementConversions() {
        assertResolves("S.lv(1, 2)", "lv(long...)");
        assertResolves("S.lv(1)", "lv(long...)");
        assertResolves("S.lv()", "lv(long...)");
        assertResolves("S.lv(1L, 2)", "lv(long...)");
        assertResolves("S.lv(new long[0])", "lv(long...)");
        assertResolves("S.dv(\"x\", 1, 2f)", "dv(java.lang.String, double...)");
        assertResolves("S.dv(\"x\", 1)", "dv(java.lang.String, double...)");
        assertResolves("S.nv(1, 2.0)", "nv(java.lang.Number...)");
        assertResolves("S.ov(1, \"a\")", "ov(java.lang.Object...)");
        assertResolves("S.ov(i)", "ov(java.lang.Object...)");
    }

    @Test
    @DisplayName("scalar widening and reference arrays are unchanged")
    void unchanged() {
        assertResolves("S.w(3)", "w(long)");
        assertResolves("S.w(3f)", "w(double)");
        assertResolves("S.r(new String[0])", "r(java.lang.String[])");
        assertResolves("S.r(new Object[0])", "r(java.lang.Object[])");
    }

    @Test
    @DisplayName("calls javac rejects stay unresolved instead of pointing at a wrong method")
    void notApplicable() {
        for (String call : List.of("S.box(i)", "S.lv(i)")) {   // int[] → Integer[], int[] → long...
            assertTrue(outcomes(call).equals(Set.of("UNSOLVED")), call + " → " + outcomes(call));
            assertThrows(RuntimeException.class, () -> calls.get(call).resolve(), call);
        }
    }

    private void assertResolves(String call, String signature) {
        assertEquals(signature, calls.get(call).resolve().getSignature(), call);
        assertEquals(Set.of(signature), outcomes(call), call + " over 40 candidate orders");
    }

    /** Resolves the call against S's methods in 40 shuffled orders and collects the distinct outcomes. */
    private Set<String> outcomes(String call) {
        MethodCallExpr expr = calls.get(call);
        List<ResolvedType> args = new ArrayList<>();
        for (Expression e : expr.getArguments()) args.add(e.calculateResolvedType());
        List<ResolvedMethodDeclaration> methods = new ArrayList<>(typeSolver.solveType("p.S").getDeclaredMethods());
        Set<String> outcomes = new TreeSet<>();
        Random random = new Random(42);
        for (int k = 0; k < 40; k++) {
            Collections.shuffle(methods, random);
            try {
                var ref = MethodResolutionLogic.findMostApplicable(methods, expr.getNameAsString(), args, typeSolver);
                outcomes.add(ref.isSolved() ? ref.getCorrespondingDeclaration().getSignature() : "UNSOLVED");
            } catch (RuntimeException ex) {
                outcomes.add("EXCEPTION " + ex.getClass().getSimpleName());
            }
        }
        return outcomes;
    }
}
