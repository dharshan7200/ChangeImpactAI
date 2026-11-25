package com.changeimpact.parser.language.ts;

import com.changeimpact.parser.language.LanguageParser;
import com.changeimpact.parser.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Helpers shared by the tree-sitter language tests. */
final class TreeSitterFixture {

    private TreeSitterFixture() {}

    static void write(Path root, String path, String content) throws Exception {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    static Map<String, ParsedSourceFile> parseAll(LanguageParser parser, Path root) {
        try (var session = parser.open(root)) {
            Map<String, ParsedSourceFile> out = new TreeMap<>();
            for (Path f : session.sourceFiles()) session.parse(f).ifPresent(p -> out.put(p.getRelativePath(), p));
            return out;
        }
    }

    static Set<String> deps(Map<String, ParsedSourceFile> files, DependencyType type) {
        return files.values().stream().flatMap(f -> f.getDependencies().stream()).filter(d -> d.getDependencyType() == type)
                .map(d -> d.getSourceQualifiedName() + " -> " + d.getTargetQualifiedName()).collect(Collectors.toCollection(TreeSet::new));
    }

    static Map<String, ExtractedSymbol> symbols(Map<String, ParsedSourceFile> files) {
        Map<String, ExtractedSymbol> out = new TreeMap<>();
        files.values().forEach(f -> f.getSymbols().forEach(s -> out.putIfAbsent(s.getQualifiedName(), s)));
        return out;
    }
}
