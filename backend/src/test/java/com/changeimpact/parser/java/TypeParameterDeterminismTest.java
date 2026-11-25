package com.changeimpact.parser.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserTypeParameter;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionTypeParameter;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the patched type-parameter hash codes (see the headers of the patched {@code JavaParserTypeParameter} and
 * {@code ReflectionTypeParameter}): upstream mixed identity hashes into them, so maps keyed by type parameters
 * iterated in a different order in every analysis and generic calls with same-named type variables resolved
 * differently from run to run.
 */
@DisplayName("JavaParser type parameters (patched): hash codes and resolution are the same in every analysis")
class TypeParameterDeterminismTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("hash codes do not depend on the type-solver instance or the JVM run")
    void hashCodes() {
        TypeParameter node = new TypeParameter("O");
        assertEquals(new JavaParserTypeParameter(node, new ReflectionTypeSolver()).hashCode(),
                new JavaParserTypeParameter(node, new ReflectionTypeSolver()).hashCode());
        assertEquals(node.hashCode(), new JavaParserTypeParameter(node, new ReflectionTypeSolver()).hashCode());

        var tv = java.util.function.Function.class.getTypeParameters()[0];  // Function<T, R>
        int expected = "java.util.function.Function.T".hashCode();          // qualified name, as compared by equals()
        assertEquals(expected, new ReflectionTypeParameter(tv, true, new ReflectionTypeSolver()).hashCode());
        assertEquals(expected, new ReflectionTypeParameter(tv, true, new ReflectionTypeSolver()).hashCode());
    }

    @Test
    @DisplayName("a generic call mixing two type variables named O has the same outcome in 12 fresh analyses")
    void sameNamedTypeVariables() throws Exception {
        Path src = root.resolve("src/main/java/q");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Functions.java"), """
                package q;

                import java.util.function.Function;

                public class Functions {
                    @FunctionalInterface
                    public interface FailableFunction<I, R, T extends Throwable> { R apply(I input) throws T; }

                    public static <I, O> Function<I, O> asFunction(final FailableFunction<I, O, ?> function) {
                        return input -> {
                            try { return function.apply(input); } catch (Throwable t) { throw new IllegalStateException(t); }
                        };
                    }
                }
                """);
        Files.writeString(src.resolve("Streams.java"), """
                package q;

                import java.util.stream.Stream;

                public class Streams {
                    public static class FailableStream<O> {
                        private final Stream<O> stream;

                        public FailableStream(final Stream<O> stream) { this.stream = stream; }

                        public <R> FailableStream<R> map(final Functions.FailableFunction<O, R, ?> mapper) {
                            return new FailableStream<>(stream.map(Functions.asFunction(mapper)));
                        }
                    }
                }
                """);
        Map<String, Integer> outcomes = new TreeMap<>();
        for (int i = 0; i < 12; i++) {
            JavaParser parser = new JavaAstParser().createConfiguredParser(root);   // a new type solver, as per analysis
            CompilationUnit cu = parser.parse(src.resolve("Streams.java")).getResult().orElseThrow();
            ObjectCreationExpr call = cu.findFirst(ObjectCreationExpr.class).orElseThrow();
            String outcome;
            try {
                outcome = call.resolve().getQualifiedSignature();
            } catch (RuntimeException e) {
                outcome = "unresolved: " + e.getClass().getSimpleName();
            }
            outcomes.merge(outcome, 1, Integer::sum);
        }
        // upstream: resolved in some analyses and unresolved in others (6/6 here). Patched: one outcome. That outcome is
        // "unresolved" for this minimal reproduction — a JavaParser inference limitation for same-named type variables;
        // the extractor then records the call as PARTIAL (linked to the type), the same way in every analysis.
        assertEquals(1, outcomes.size(), "one outcome across analyses: " + outcomes);
        assertEquals(12, outcomes.values().iterator().next());
    }
}
