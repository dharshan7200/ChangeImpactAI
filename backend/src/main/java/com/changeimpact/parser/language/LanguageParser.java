package com.changeimpact.parser.language;

import com.changeimpact.parser.model.ParsedSourceFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A source language the platform can analyse. Every parser produces the same model ({@link ParsedSourceFile}:
 * symbols and dependencies with qualified names), so storage, the dependency graph, semantic diff, impact
 * propagation and risk scoring work unchanged for every language.
 */
public interface LanguageParser {

    /** Stored in {@code source_files.language}, e.g. {@code java}, {@code python}. */
    String language();

    /** File extensions this parser reads, including the dot. */
    Set<String> extensions();

    /**
     * Version of the symbols and dependencies this parser produces. Increase it whenever the output for the same source
     * changes: commits analysed by an older version are then re-analysed instead of being compared with newer output.
     */
    default int version() { return 1; }

    /** Experimental parsers are off unless enabled ({@code app.analysis.languages}); their accuracy is not claimed. */
    boolean experimental();

    /** False when the parser cannot run here (e.g. its native library does not load); it is then skipped. */
    boolean available();

    /** Whether the parser takes part in incremental analysis (copying unaffected files from an analysed commit). */
    boolean incremental();

    /** One analysis of a source tree. */
    Session open(Path root);

    /**
     * One analysis with the project's dependency jars on the type-resolution path (languages that use them: Java).
     * {@code libraryJars} is empty when no library classpath is available.
     */
    default Session open(Path root, List<Path> libraryJars) { return open(root); }

    interface Session extends AutoCloseable {
        /** Source files of this language under the root, in a deterministic order. */
        List<Path> sourceFiles();

        Optional<ParsedSourceFile> parse(Path file);

        @Override
        default void close() {}
    }
}
