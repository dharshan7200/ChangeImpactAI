package com.changeimpact.diff.semantic;

import com.changeimpact.parser.model.SymbolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Configuration key changes and bindings")
class ConfigChangeAnalyzerTest {

    private final ConfigChangeAnalyzer analyzer = new ConfigChangeAnalyzer();

    private static SymbolSnapshot symbol(String qn, String annotations) {
        return new SymbolSnapshot(UUID.randomUUID(), qn, qn.replaceAll(".*[.#]", ""), SymbolType.FIELD, null, "PRIVATE",
                "String", null, false, false, false, annotations, "h", Set.of(), "src/main/java/X.java", 1, 1, false);
    }

    @Test
    @DisplayName("YAML and properties keys are flattened and diffed; secrets are masked")
    void diffKeys() {
        var yml = analyzer.diffKeys("application.yml",
                "shop:\n  discount-rate: 5\n  db:\n    password: a\n", "shop:\n  discount-rate: 10\n  db:\n    password: b\n  new: x\n");
        Map<String, ConfigChangeAnalyzer.ConfigKeyChange> byKey = yml.stream()
                .collect(Collectors.toMap(ConfigChangeAnalyzer.ConfigKeyChange::key, c -> c));
        assertEquals("MODIFIED", byKey.get("shop.discount-rate").changeType());
        assertEquals("10", byKey.get("shop.discount-rate").after());
        assertEquals("******", byKey.get("shop.db.password").after());
        assertEquals("ADDED", byKey.get("shop.new").changeType());

        var props = analyzer.diffKeys("application.properties", "a.b=1\nc=2\n", "a.b=1\n");
        assertEquals(List.of("c"), props.stream().map(ConfigChangeAnalyzer.ConfigKeyChange::key).toList());
    }

    @Test
    @DisplayName("@Value, @ConfigurationProperties and relaxed names are linked to changed keys")
    void bindings() {
        var changes = analyzer.diffKeys("application.yml", "shop:\n  discount-rate: 5\n", "shop:\n  discount-rate: 7\n");
        SymbolSnapshot value = symbol("x.Pricing#rate", "@Value(\"${shop.discountRate:3}\")");
        SymbolSnapshot props = symbol("x.ShopProperties", "@ConfigurationProperties(prefix = \"shop\")");
        SymbolSnapshot unrelated = symbol("x.Other#name", "@Value(\"${other.name}\")");
        var bound = analyzer.bindingSymbols(List.of(value, props, unrelated), changes);
        assertEquals(Set.of(value, props), bound.keySet());
    }
}
