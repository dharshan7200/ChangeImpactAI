package com.changeimpact.parser.language;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PythonLanguageParserTest {

    @TempDir Path root;
    private PythonLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new PythonLanguageParser(new AppProperties());
        assumeTrue(parser.available(), "tree-sitter native library not available on this platform");
    }

    private void write(String path, String content) throws Exception {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private Map<String, ParsedSourceFile> parseAll() {
        try (var session = parser.open(root)) {
            Map<String, ParsedSourceFile> out = new TreeMap<>();
            for (Path f : session.sourceFiles()) session.parse(f).ifPresent(p -> out.put(p.getRelativePath(), p));
            return out;
        }
    }

    private static Set<String> deps(ParsedSourceFile f, DependencyType type) {
        return f.getDependencies().stream().filter(d -> d.getDependencyType() == type)
                .map(d -> d.getSourceQualifiedName() + " -> " + d.getTargetQualifiedName()).collect(Collectors.toCollection(TreeSet::new));
    }

    private void shop() throws Exception {
        write("src/shop/__init__.py", "");
        write("src/shop/models.py", """
                \"\"\"Domain model.\"\"\"
                from abc import ABC, abstractmethod

                TAX = 0.2

                class Entity(ABC):
                    @abstractmethod
                    def key(self):
                        ...

                class Order(Entity):
                    def __init__(self, items):
                        self.items = items

                    def key(self):
                        return id(self)

                    def total(self):
                        return sum(i.price for i in self.items if i.price > 0) * (1 + TAX)
                """);
        write("src/shop/service.py", """
                import logging
                from .models import Order
                from . import models as m
                import shop.util as u

                log = logging.getLogger(__name__)

                def cached(fn):
                    return fn

                class OrderService:
                    @cached
                    def place(self, items, repo):
                        order = Order(items)        # constructor call
                        self._check(order)          # self call
                        u.audit("placed", order)    # module function through an alias
                        repo.save(order)            # receiver of unknown type: not resolved
                        return m.Order(items).total()

                    def _check(self, order):
                        if not order.items:
                            raise ValueError("empty")
                """);
        write("src/shop/util.py", "def audit(event, obj):\n    print(event, obj)\n");
        write("tests/test_service.py", "from shop.service import OrderService\n\ndef test_place():\n    assert OrderService().place([], None)\n");
        write(".venv/lib/site.py", "def ignored(): pass\n");
    }

    @Test
    void symbolsOfAPackage() throws Exception {
        shop();
        Map<String, ParsedSourceFile> files = parseAll();
        assertThat(files.keySet()).containsExactly("src/shop/__init__.py", "src/shop/models.py", "src/shop/service.py",
                "src/shop/util.py", "tests/test_service.py"); // .venv skipped

        ParsedSourceFile models = files.get("src/shop/models.py");
        assertThat(models.getLanguage()).isEqualTo("python");
        assertThat(models.getPackageName()).isEqualTo("shop");
        Map<String, ExtractedSymbol> s = models.getSymbols().stream().collect(Collectors.toMap(ExtractedSymbol::getQualifiedName, x -> x));
        assertThat(s.keySet()).containsExactlyInAnyOrder("shop.models", "shop.models.Entity", "shop.models.Entity#key",
                "shop.models.Order", "shop.models.Order#__init__", "shop.models.Order#key", "shop.models.Order#total");
        assertThat(s.get("shop.models").getSymbolType()).isEqualTo(SymbolType.MODULE);
        assertThat(s.get("shop.models.Order").getSymbolType()).isEqualTo(SymbolType.CLASS);
        assertThat(s.get("shop.models.Order").getParentQualifiedName()).isEqualTo("shop.models");
        assertThat(s.get("shop.models.Order#__init__").getSymbolType()).isEqualTo(SymbolType.CONSTRUCTOR);
        assertThat(s.get("shop.models.Order#__init__").getParameters()).isEqualTo("items"); // self dropped
        assertThat(s.get("shop.models.Entity#key").isAbstract()).isTrue();
        assertThat(s.get("shop.models.Order#total").getComplexity()).isEqualTo(3); // base + comprehension for + if
        assertThat(s.get("shop.models.Order#total").getStartLine()).isEqualTo(18);

        assertThat(files.get("src/shop/__init__.py").getSymbols().get(0).getQualifiedName()).isEqualTo("shop");
        assertThat(files.get("tests/test_service.py").isTest()).isTrue();
        assertThat(files.get("src/shop/service.py").isTest()).isFalse();
        assertThat(files.get("src/shop/service.py").getSymbols()).extracting(ExtractedSymbol::getQualifiedName)
                .contains("shop.service#cached", "shop.service.OrderService#place", "shop.service.OrderService#_check");
        assertThat(files.get("src/shop/service.py").getSymbols().stream()
                .filter(x -> x.getQualifiedName().equals("shop.service#cached")).findFirst().orElseThrow().getSymbolType())
                .isEqualTo(SymbolType.FUNCTION);
    }

    @Test
    void dependenciesAreResolvedThroughImportsAndScopes() throws Exception {
        shop();
        Map<String, ParsedSourceFile> files = parseAll();
        ParsedSourceFile service = files.get("src/shop/service.py");
        assertThat(deps(service, DependencyType.IMPORT)).containsExactlyInAnyOrder(
                "shop.service -> logging", "shop.service -> shop.models.Order", "shop.service -> shop.models",
                "shop.service -> shop.util");
        assertThat(deps(service, DependencyType.CONSTRUCTOR_CALL)).containsExactlyInAnyOrder(
                "shop.service.OrderService#place -> shop.models.Order");
        assertThat(deps(service, DependencyType.METHOD_CALL)).containsExactlyInAnyOrder(
                "shop.service.OrderService#place -> shop.service.OrderService#_check",
                "shop.service.OrderService#place -> shop.util#audit",
                "shop.service.OrderService#place -> shop.models.Order#total", // m.Order(items).total(): receiver constructed
                "shop.service -> logging.getLogger");                      // external, kept by name
        assertThat(deps(service, DependencyType.METHOD_CALL)).noneMatch(d -> d.contains("save")); // repo.save: unknown type
        assertThat(deps(service, DependencyType.ANNOTATION)).containsExactly("shop.service.OrderService#place -> shop.service#cached");
        assertThat(deps(files.get("src/shop/models.py"), DependencyType.EXTENDS))
                .containsExactlyInAnyOrder("shop.models.Entity -> abc.ABC", "shop.models.Order -> shop.models.Entity");
        assertThat(deps(files.get("tests/test_service.py"), DependencyType.CONSTRUCTOR_CALL))
                .containsExactly("tests.test_service#test_place -> shop.service.OrderService");
    }

    @Test
    void receiverTypesAreInferredOnlyWhenCertain() throws Exception {
        write("app/models.py", "class Repo:\n    def save(self, x): ...\n\nclass Cache:\n    def save(self, x): ...\n");
        write("app/use.py", """
                from app.models import Repo, Cache

                def typed(r: Repo, c):
                    r.save(1)            # annotated parameter
                    c.save(2)            # unannotated: unknown

                def local():
                    repo = Repo()
                    repo.save(3)         # always a Repo

                def conflicting(flag):
                    store = Repo()
                    if flag:
                        store = Cache()
                    store.save(4)        # Repo or Cache: not guessed

                def loop(items):
                    for item in items:
                        item.save(5)     # unknown
                """);
        Set<String> calls = deps(parseAll().get("app/use.py"), DependencyType.METHOD_CALL);
        assertThat(calls).containsExactlyInAnyOrder("app.use#typed -> app.models.Repo#save", "app.use#local -> app.models.Repo#save");
    }

    @Test
    void contentHashIgnoresCommentsDocstringsAndFormatting() throws Exception {
        write("a.py", "def f(x):\n    \"\"\"Doc.\"\"\"\n    return x + 1\n");
        String before = hash(parseAll().get("a.py"), "a#f");
        write("a.py", "# comment\ndef f(x):\n    \"\"\"Another doc.\"\"\"\n    # why\n    return   x + 1\n");
        assertThat(hash(parseAll().get("a.py"), "a#f")).isEqualTo(before);
        write("a.py", "def f(x):\n    return x + 2\n");
        assertThat(hash(parseAll().get("a.py"), "a#f")).isNotEqualTo(before);
    }

    @Test
    void moduleNames() {
        assertThat(PythonLanguageParser.moduleName("src/pkg/sub/mod.py")).isEqualTo("pkg.sub.mod");
        assertThat(PythonLanguageParser.moduleName("pkg/__init__.py")).isEqualTo("pkg");
        assertThat(PythonLanguageParser.moduleName("setup.py")).isEqualTo("setup");
    }

    private static String hash(ParsedSourceFile f, String qn) {
        return f.getSymbols().stream().filter(s -> s.getQualifiedName().equals(qn)).findFirst().orElseThrow().getContentHash();
    }
}
