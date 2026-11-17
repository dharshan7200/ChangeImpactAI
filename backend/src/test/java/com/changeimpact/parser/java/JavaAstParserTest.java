package com.changeimpact.parser.java;

import com.changeimpact.parser.model.DependencyType;
import com.changeimpact.parser.model.ParsedSourceFile;
import com.changeimpact.parser.model.SymbolType;
import com.github.javaparser.JavaParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JavaAstParser & SymbolExtractionVisitor Tests")
class JavaAstParserTest {

    private JavaAstParser astParser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        astParser = new JavaAstParser();
    }

    @Test
    @DisplayName("Should parse Java class, extract symbols, line ranges, and method calls")
    void shouldParseClassAndExtractSymbols() throws IOException {
        String javaCode = """
                package com.example.service;

                import java.util.List;

                public class OrderService implements BaseService {
                    private final PaymentGateway paymentGateway;

                    public OrderService(PaymentGateway paymentGateway) {
                        this.paymentGateway = paymentGateway;
                    }

                    public boolean processOrder(String orderId, double amount) {
                        return paymentGateway.charge(orderId, amount);
                    }
                }

                interface BaseService {}
                """;

        Path srcFile = tempDir.resolve("OrderService.java");
        Files.writeString(srcFile, javaCode);

        JavaParser parser = astParser.createConfiguredParser(tempDir);
        Optional<ParsedSourceFile> resultOpt = astParser.parseSourceFile(parser, tempDir, srcFile);

        assertTrue(resultOpt.isPresent());
        ParsedSourceFile parsed = resultOpt.get();

        assertEquals("com.example.service", parsed.getPackageName());
        assertEquals("OrderService.java", parsed.getRelativePath());

        // Verify symbols: OrderService class, field, constructor, method, BaseService interface
        assertTrue(parsed.getSymbols().stream().anyMatch(s ->
                s.getSimpleName().equals("OrderService") && s.getSymbolType() == SymbolType.CLASS));
        assertTrue(parsed.getSymbols().stream().anyMatch(s ->
                s.getSimpleName().equals("BaseService") && s.getSymbolType() == SymbolType.INTERFACE));
        assertTrue(parsed.getSymbols().stream().anyMatch(s ->
                s.getSimpleName().equals("processOrder") && s.getSymbolType() == SymbolType.METHOD));
        assertTrue(parsed.getSymbols().stream().anyMatch(s ->
                s.getSimpleName().equals("paymentGateway") && s.getSymbolType() == SymbolType.FIELD));

        // Verify dependencies: IMPLEMENTS BaseService
        assertTrue(parsed.getDependencies().stream().anyMatch(d ->
                d.getDependencyType() == DependencyType.IMPLEMENTS && d.getTargetQualifiedName().contains("BaseService")));

        // Verify dependencies: METHOD_CALL charge()
        assertTrue(parsed.getDependencies().stream().anyMatch(d ->
                d.getDependencyType() == DependencyType.METHOD_CALL && d.getTargetQualifiedName().contains("charge")));
    }

    @Test
    @DisplayName("Should extract Java 17 record declarations correctly")
    void shouldExtractRecordDeclarations() throws IOException {
        String javaCode = """
                package com.example.dto;

                public record OrderDto(String id, double total) {}
                """;

        Path srcFile = tempDir.resolve("OrderDto.java");
        Files.writeString(srcFile, javaCode);

        JavaParser parser = astParser.createConfiguredParser(tempDir);
        Optional<ParsedSourceFile> resultOpt = astParser.parseSourceFile(parser, tempDir, srcFile);

        assertTrue(resultOpt.isPresent());
        ParsedSourceFile parsed = resultOpt.get();

        assertTrue(parsed.getSymbols().stream().anyMatch(s ->
                s.getSimpleName().equals("OrderDto") && s.getSymbolType() == SymbolType.RECORD));
    }
}

