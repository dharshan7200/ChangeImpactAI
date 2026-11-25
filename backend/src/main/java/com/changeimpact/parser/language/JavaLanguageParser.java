package com.changeimpact.parser.language;

import com.changeimpact.ingestion.service.JavaFileMetadata;
import com.changeimpact.ingestion.service.JavaFileScanner;
import com.changeimpact.parser.java.JavaAstParser;
import com.changeimpact.parser.model.ParsedSourceFile;
import com.github.javaparser.JavaParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Java: JavaParser with the symbol solver (the platform's primary, verified language). */
@Component
@RequiredArgsConstructor
public class JavaLanguageParser implements LanguageParser {

    private final JavaFileScanner scanner;
    private final JavaAstParser astParser;
    private final com.changeimpact.parser.libraries.LibraryResolver libraries;

    @Override public String language() { return "java"; }
    @Override public Set<String> extensions() { return Set.of(".java"); }
    @Override public boolean experimental() { return false; }
    /**
     * 1: types resolved without the project's libraries; 2: with them (library resolution on); 3 (with libraries) and
     * 4 (without): plus links to database tables (JPA entities and columns, SQL in strings); 5 / 6: plus columns of
     * fields inherited from @MappedSuperclasses.
     */
    @Override public int version() { return libraries.enabled() ? 5 : 6; }
    @Override public boolean available() { return true; }
    @Override public boolean incremental() { return true; }

    @Override
    public Session open(Path root) {
        return open(root, List.of());
    }

    @Override
    public Session open(Path root, List<Path> libraryJars) {
        JavaParser parser = astParser.createConfiguredParser(root, libraryJars);
        return new Session() {
            @Override
            public List<Path> sourceFiles() {
                return scanner.scanJavaFiles(root).stream().map(JavaFileMetadata::absolutePath).toList();
            }

            @Override
            public Optional<ParsedSourceFile> parse(Path file) {
                return astParser.parseSourceFile(parser, root, file);
            }
        };
    }
}
