package com.changeimpact.parser.language.ts;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.parser.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static com.changeimpact.parser.language.ts.TreeSitterFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JavaScriptLanguageParserTest {

    @TempDir Path root;
    private JavaScriptLanguageParser parser;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        parser = new JavaScriptLanguageParser(props, new CssLanguageParser(props));
        assumeTrue(parser.available(), "tree-sitter native library not available on this platform");
    }

    private void reactApp() throws Exception {
        write(root, "tsconfig.json", """
                {
                  // comments and trailing commas are allowed in tsconfig
                  "compilerOptions": { "baseUrl": ".", "paths": { "@/*": ["src/*"], }, },
                }
                """);
        write(root, "src/components/Button.module.css", ".primary { color: red; }\n.ghost { color: gray; }\n");
        write(root, "src/App.css", ".card { padding: 4px; }\n#root { margin: 0; }\n");
        write(root, "src/components/Button.tsx", """
                import React from 'react';
                import styles from './Button.module.css';

                export interface ButtonProps { label: string; onClick?: () => void }

                export function Button({ label, onClick }: ButtonProps) {
                  return <button className={styles.primary} onClick={onClick}>{label}</button>;
                }

                export default Button;
                """);
        write(root, "src/components/index.ts", """
                export { Button } from './Button';
                export * from './Card';
                """);
        write(root, "src/components/Card.tsx", """
                import { Component } from 'react';
                export class Card extends Component<{ title: string }> {
                  render() { return <div className="card">{this.title()}</div>; }
                  title() { return this.props.title; }
                }
                """);
        write(root, "src/hooks/useCounter.ts", """
                import { useState } from 'react';
                export const useCounter = (start: number) => {
                  const [n, setN] = useState(start);
                  return { n, inc: () => setN(n + 1) };
                };
                """);
        write(root, "src/api/client.ts", """
                export class ApiClient {
                  static create(): ApiClient { return new ApiClient(); }
                  get(url: string) { return fetch(url); }
                }
                export const loadUsers = (c: ApiClient) => c.get('/users');
                export const SQL = "SELECT id, name FROM users u JOIN orders o ON o.user_id = u.id";
                """);
        write(root, "src/App.tsx", """
                import './App.css';
                import { Button, Card } from '@/components';
                import { useCounter } from './hooks/useCounter';
                import * as api from './api/client';
                import { ApiClient } from './api/client';

                export const App = () => {
                  const { n, inc } = useCounter(0);
                  const client = ApiClient.create();
                  api.loadUsers(client);
                  return (
                    <main className="card unknown">
                      <Card title="x" />
                      <Button label={String(n)} onClick={inc} />
                    </main>
                  );
                };
                """);
        write(root, "src/legacy/util.js", """
                const path = require('path');
                function helper(x) { return path.join(x, 'a'); }
                module.exports = { helper };
                """);
        write(root, "src/legacy/main.js", """
                const { helper } = require('./util');
                exports.run = function () { return helper('b'); };
                """);
        write(root, "src/App.test.tsx", """
                import { App } from './App';
                test('renders', () => { App(); });
                """);
        write(root, "node_modules/react/index.js", "module.exports = {};");
        write(root, "src/types.d.ts", "declare const x: number;");
    }

    @Test
    void reactTypescriptProject() throws Exception {
        reactApp();
        Map<String, ParsedSourceFile> files = parseAll(parser, root);

        assertThat(files.keySet()).containsExactlyInAnyOrder("src/App.test.tsx", "src/App.tsx", "src/api/client.ts",
                "src/components/Button.tsx", "src/components/Card.tsx", "src/components/index.ts", "src/hooks/useCounter.ts",
                "src/legacy/main.js", "src/legacy/util.js");
        assertThat(files.get("src/App.test.tsx").isTest()).isTrue();
        assertThat(files.get("src/App.tsx").isTest()).isFalse();
        assertThat(files.get("src/App.tsx").getLanguage()).isEqualTo("javascript");

        var symbols = symbols(files);
        assertThat(symbols).containsKeys("src.App", "src.App#App", "src.components", "src.components.Button#Button",
                "src.components.Button.ButtonProps", "src.components.Card.Card", "src.components.Card.Card#render",
                "src.hooks.useCounter#useCounter", "src.api.client.ApiClient#create", "src.api.client#loadUsers",
                "src.legacy.util#helper", "src.legacy.main#run");
        assertThat(symbols.get("src.App#App").getAnnotations()).contains("component");
        assertThat(symbols.get("src.components.Button#Button").getAnnotations()).contains("component");
        assertThat(symbols.get("src.components.Card.Card").getAnnotations()).contains("component");
        assertThat(symbols.get("src.hooks.useCounter#useCounter").getAnnotations()).contains("hook");
        assertThat(symbols.get("src.api.client.ApiClient#create").getSymbolType()).isEqualTo(SymbolType.METHOD);
        assertThat(symbols.get("src.api.client.ApiClient#create").isStatic()).isTrue();

        assertThat(deps(files, DependencyType.IMPORT)).contains(
                "src.App -> src.components", "src.App -> src.hooks.useCounter", "src.App -> src.api.client",
                "src.App -> src.App.css", "src.components.Button -> src.components.Button.module.css",
                "src.components -> src.components.Button", "src.components -> src.components.Card",
                "src.legacy.main -> src.legacy.util", "src.legacy.util -> npm:path", "src.components.Button -> npm:react");

        // tsconfig alias + barrel re-exports (named and star) lead to the declarations
        assertThat(deps(files, DependencyType.CONSTRUCTOR_CALL)).contains(
                "src.App#App -> src.components.Button#Button", "src.App#App -> src.components.Card.Card",
                "src.api.client.ApiClient#create -> src.api.client.ApiClient");
        assertThat(deps(files, DependencyType.METHOD_CALL)).contains(
                "src.App#App -> src.hooks.useCounter#useCounter", "src.App#App -> src.api.client#loadUsers",
                "src.App#App -> src.api.client.ApiClient#create", "src.components.Card.Card#render -> src.components.Card.Card#title",
                "src.legacy.main#run -> src.legacy.util#helper", "src.hooks.useCounter#useCounter -> npm:react#useState",
                "src.App.test -> src.App#App");
        // c.get(...) on a parameter is not guessed
        assertThat(deps(files, DependencyType.METHOD_CALL)).noneMatch(d -> d.endsWith("ApiClient#get"));
        assertThat(deps(files, DependencyType.EXTENDS)).contains("src.components.Card.Card -> npm:react#Component");
        assertThat(deps(files, DependencyType.PARAMETER_TYPE)).contains(
                "src.components.Button#Button -> src.components.Button.ButtonProps", "src.api.client#loadUsers -> src.api.client.ApiClient");
        assertThat(deps(files, DependencyType.RETURN_TYPE)).contains("src.api.client.ApiClient#create -> src.api.client.ApiClient");

        // styles: className strings and CSS Modules link to selectors; unknown classes link to nothing
        assertThat(deps(files, DependencyType.FIELD_ACCESS)).contains(
                "src.App#App -> src.App.css#.card", "src.components.Card.Card#render -> src.App.css#.card",
                "src.components.Button#Button -> src.components.Button.module.css#.primary",
                "src.api.client -> db:users", "src.api.client -> db:orders");
        assertThat(deps(files, DependencyType.FIELD_ACCESS)).noneMatch(d -> d.contains("unknown"));
    }

    @Test
    void contentHashesIgnoreFormattingAndComments() throws Exception {
        write(root, "a.ts", "export function f(x: number) { return x + 1; }\n");
        String before = symbols(parseAll(parser, root)).get("a#f").getContentHash();
        write(root, "a.ts", "// note\nexport function f(x: number) {\n  return x + 1; // same\n}\n");
        assertThat(symbols(parseAll(parser, root)).get("a#f").getContentHash()).isEqualTo(before);
        write(root, "a.ts", "export function f(x: number) { return x + 2; }\n");
        assertThat(symbols(parseAll(parser, root)).get("a#f").getContentHash()).isNotEqualTo(before);
    }

    @Test
    void deterministic() throws Exception {
        reactApp();
        assertThat(deps(parseAll(parser, root), DependencyType.METHOD_CALL)).isEqualTo(deps(parseAll(parser, root), DependencyType.METHOD_CALL));
    }
}
