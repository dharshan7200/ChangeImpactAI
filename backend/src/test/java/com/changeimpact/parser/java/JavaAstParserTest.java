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
    @Test
    @DisplayName("JPA entities link to their tables and columns; SQL in strings links to its tables")
    void jpaEntitiesAndEmbeddedSql() throws IOException {
        String javaCode = """
                package shop;

                import jakarta.persistence.*;

                @Entity
                @Table(name = "owners")
                public class Owner {
                    @Id private Integer id;
                    @Column(name = "first_name") private String firstName;
                    private String lastName;
                    @ManyToOne private PetType favouriteType;
                    @OneToMany(mappedBy = "owner") private java.util.List<Pet> pets;
                    @Transient private String cache;
                    private static final long serialVersionUID = 1L;

                    String query() { return "SELECT o.id FROM owners o JOIN pets p ON p.owner_id = o.id"; }
                    String jpql() { return "select o from Owner o"; }
                }

                @Entity class PetType { @Id Integer id; }
                class Pet {}
                """;
        Path srcFile = tempDir.resolve("Owner.java");
        Files.writeString(srcFile, javaCode);
        ParsedSourceFile parsed = astParser.parseSourceFile(astParser.createConfiguredParser(tempDir), tempDir, srcFile).orElseThrow();

        var links = parsed.getDependencies().stream()
                .filter(d -> d.getTargetQualifiedName().startsWith("db:"))
                .map(d -> d.getDependencyType() + " " + d.getSourceQualifiedName() + " -> " + d.getTargetQualifiedName())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(new java.util.TreeSet<>(java.util.List.of(
                "ANNOTATION shop.Owner -> db:owners",
                "ANNOTATION shop.Owner#id -> db:owners#id",
                "ANNOTATION shop.Owner#firstName -> db:owners#first_name",
                "ANNOTATION shop.Owner#lastName -> db:owners#last_name",
                "ANNOTATION shop.Owner#favouriteType -> db:owners#favourite_type_id",
                "ANNOTATION shop.PetType -> db:pet_type",
                "ANNOTATION shop.PetType#id -> db:pet_type#id",
                // JPQL names the entity: kept only if the repository's SQL defines a table "owner" (then it is that table)
                "FIELD_ACCESS shop.Owner#jpql() -> db:owner",
                "FIELD_ACCESS shop.Owner#query() -> db:owners",
                "FIELD_ACCESS shop.Owner#query() -> db:pets")), links);
    }

    @Test
    @DisplayName("fields inherited from @MappedSuperclasses link to the entity's columns (petclinic: Owner → Person → BaseEntity)")
    void mappedSuperclassColumns() throws IOException {
        Path pkg = tempDir.resolve("shop");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("BaseEntity.java"), """
                package shop;
                import jakarta.persistence.*;
                @MappedSuperclass
                public class BaseEntity { @Id protected Integer id; }
                """);
        Files.writeString(pkg.resolve("Person.java"), """
                package shop;
                import jakarta.persistence.*;
                @MappedSuperclass
                public class Person extends BaseEntity {
                    @Column(name = "first_name") protected String firstName;
                    protected String lastName;
                    @Transient protected String display;
                }
                """);
        Files.writeString(pkg.resolve("Owner.java"), """
                package shop;
                import jakarta.persistence.*;
                @Entity @Table(name = "owners")
                public class Owner extends Person { private String city; }
                """);
        Files.writeString(pkg.resolve("Vet.java"), """
                package shop;
                import jakarta.persistence.*;
                @Entity @Table(name = "vets")
                public class Vet extends Person { }
                """);
        var parser = astParser.createConfiguredParser(tempDir);
        java.util.Set<String> links = new java.util.TreeSet<>();
        for (String f : java.util.List.of("Owner.java", "Vet.java")) {
            ParsedSourceFile parsed = astParser.parseSourceFile(parser, tempDir, pkg.resolve(f)).orElseThrow();
            parsed.getDependencies().stream().filter(d -> d.getTargetQualifiedName().startsWith("db:"))
                    .forEach(d -> links.add(d.getSourceQualifiedName() + " -> " + d.getTargetQualifiedName()));
        }
        assertEquals(new java.util.TreeSet<>(java.util.List.of(
                "shop.Owner -> db:owners", "shop.Owner -> db:owners#first_name", "shop.Owner -> db:owners#last_name", "shop.Owner -> db:owners#id",
                "shop.Owner#city -> db:owners#city",
                "shop.Vet -> db:vets", "shop.Vet -> db:vets#first_name", "shop.Vet -> db:vets#last_name", "shop.Vet -> db:vets#id")), links);
    }
}
