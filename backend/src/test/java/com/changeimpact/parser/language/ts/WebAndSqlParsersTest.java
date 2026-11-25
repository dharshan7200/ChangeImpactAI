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

/** CSS, HTML/templates and SQL. */
class WebAndSqlParsersTest {

    @TempDir Path root;
    private CssLanguageParser css;
    private HtmlLanguageParser html;
    private SqlLanguageParser sql;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        css = new CssLanguageParser(props);
        html = new HtmlLanguageParser(props, css);
        sql = new SqlLanguageParser(props);
        assumeTrue(css.available() && html.available(), "tree-sitter native library not available on this platform");
    }

    @Test
    void stylesheets() throws Exception {
        write(root, "static/css/base.css", ".btn { color: blue }\n");
        write(root, "static/css/site.css", """
                @import url("base.css");
                /* cards */
                .card, .card > .title:hover { color: red; }
                #main .big { margin: 0 }
                @media (max-width: 600px) { .card { display: none } }
                """);
        Map<String, ParsedSourceFile> files = parseAll(css, root);
        var symbols = symbols(files);
        assertThat(symbols).containsKeys("static.css.site.css", "static.css.site.css#.card", "static.css.site.css#.title",
                "static.css.site.css##main", "static.css.site.css#.big", "static.css.base.css#.btn");
        assertThat(symbols.get("static.css.site.css#.card").getStartLine()).isEqualTo(3);
        assertThat(symbols.get("static.css.site.css#.card").getEndLine()).isEqualTo(5); // both rules mentioning it
        assertThat(deps(files, DependencyType.IMPORT)).containsExactly("static.css.site.css -> static.css.base.css");

        String before = symbols.get("static.css.site.css#.card").getContentHash();
        String bigBefore = symbols.get("static.css.site.css#.big").getContentHash();
        write(root, "static/css/site.css", """
                @import url("base.css");
                /* cards */
                .card, .card > .title:hover { color: green; }
                #main .big { margin: 0 }
                @media (max-width: 600px) { .card { display: none } }
                """);
        var after = symbols(parseAll(css, root));
        assertThat(after.get("static.css.site.css#.card").getContentHash()).isNotEqualTo(before);
        assertThat(after.get("static.css.site.css#.big").getContentHash()).isEqualTo(bigBefore);
    }

    @Test
    void frameworkSizedStylesheetsAreOneModule() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= CssLanguageParser.MAX_SELECTORS; i++) big.append(".c").append(i).append(" { x: 1 }\n");
        write(root, "static/bootstrap.css", big.toString());
        write(root, "static/app.css", ".mine { x: 1 }\n");
        var symbols = symbols(parseAll(css, root));
        assertThat(symbols.keySet()).containsExactlyInAnyOrder("static.bootstrap.css", "static.app.css", "static.app.css#.mine");
        // links into the bundle still name its selectors; CodeAnalysisService attaches them to the stylesheet
        assertThat(css.index(root).targets(".c7")).containsExactly("static.bootstrap.css#.c7");
    }

    @Test
    void thymeleafAndJinjaTemplates() throws Exception {
        write(root, "src/main/resources/static/resources/css/petclinic.css", ".navbar { x: 1 }\n.table-striped { y: 2 }\n#owners { z: 3 }\n");
        write(root, "src/main/resources/static/js/app.js", "export function init() {}\n");
        write(root, "src/main/resources/templates/fragments/layout.html", """
                <!DOCTYPE html>
                <html th:fragment="layout (template, menu)">
                <head>
                  <link rel="stylesheet" th:href="@{/resources/css/petclinic.css}" />
                  <link rel="icon" href="/favicon.png" />
                  <script src="https://cdn.example.com/x.js"></script>
                  <script th:src="@{/js/app.js}"></script>
                </head>
                <body><nav class="navbar navbar-expand"></nav><div th:replace="${template}"></div></body>
                </html>
                """);
        write(root, "src/main/resources/templates/owners/ownersList.html", """
                <html th:replace="~{fragments/layout :: layout (~{::body},'owners')}">
                <body>
                  <table id="owners" class="table table-striped"></table>
                </body>
                </html>
                """);
        write(root, "app/templates/base.html", "<html><body>{% block content %}{% endblock %}</body></html>\n");
        write(root, "app/templates/shop/list.html", """
                {% extends "base.html" %}
                {% block content %}<ul class="navbar"></ul>{% include 'shop/item.html' %}{% endblock %}
                """);
        write(root, "app/templates/shop/item.html", "<li>{{ item }}</li>\n");

        Map<String, ParsedSourceFile> files = parseAll(html, root);
        var symbols = symbols(files);
        String layout = "src.main.resources.templates.fragments.layout.html";
        String list = "src.main.resources.templates.owners.ownersList.html";
        assertThat(symbols).containsKeys(layout, layout + "#layout", "app.templates.base.html#content", "app.templates.shop.list.html#content");
        assertThat(symbols.get(layout + "#layout").getParameters()).isEqualTo("template, menu");

        assertThat(deps(files, DependencyType.IMPORT)).contains(
                layout + "#layout -> src.main.resources.static.resources.css.petclinic.css",
                layout + "#layout -> src.main.resources.static.js.app",
                list + " -> " + layout,
                "app.templates.shop.list.html -> app.templates.base.html",
                "app.templates.shop.list.html -> app.templates.shop.item.html");
        assertThat(deps(files, DependencyType.IMPORT)).noneMatch(d -> d.contains("favicon") || d.contains("cdn"));
        assertThat(deps(files, DependencyType.METHOD_CALL)).contains(list + " -> " + layout + "#layout");
        assertThat(deps(files, DependencyType.FIELD_ACCESS)).contains(
                layout + "#layout -> src.main.resources.static.resources.css.petclinic.css#.navbar",
                list + " -> src.main.resources.static.resources.css.petclinic.css#.table-striped",
                list + " -> src.main.resources.static.resources.css.petclinic.css##owners",
                "app.templates.shop.list.html -> src.main.resources.static.resources.css.petclinic.css#.navbar");
    }

    @Test
    void sqlSchemaAndMigrations() throws Exception {
        write(root, "src/main/resources/db/migration/V1__init.sql", """
                -- owners and pets
                CREATE TABLE IF NOT EXISTS public.owners (
                  id         INTEGER NOT NULL PRIMARY KEY,
                  first_name VARCHAR(30),
                  "Last_Name" VARCHAR(30) DEFAULT 'x; y',
                  CONSTRAINT uq UNIQUE (first_name)
                );
                CREATE TABLE pets (
                  id       SERIAL PRIMARY KEY,
                  owner_id INTEGER REFERENCES owners (id),
                  type_id  INTEGER,
                  FOREIGN KEY (type_id) REFERENCES types(id)
                );
                CREATE INDEX pets_name ON pets (owner_id);
                /* block comment; with a semicolon */
                CREATE OR REPLACE VIEW owner_pets AS SELECT o.id, p.id FROM owners o JOIN pets p ON p.owner_id = o.id;
                """);
        write(root, "src/main/resources/db/migration/V2__more.sql", """
                CREATE TABLE types (id INT PRIMARY KEY, name VARCHAR(80));
                ALTER TABLE owners ADD COLUMN email VARCHAR(100), ADD CONSTRAINT fk_t FOREIGN KEY (id) REFERENCES types (id);
                CREATE OR REPLACE FUNCTION touch() RETURNS trigger AS $$
                BEGIN
                  UPDATE owners SET first_name = first_name; -- inside the body
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER owners_touch BEFORE UPDATE ON owners FOR EACH ROW EXECUTE FUNCTION touch();
                INSERT INTO types (id, name) VALUES (1, 'cat');
                """);
        write(root, "mssql/procs.sql", """
                CREATE PROCEDURE dbo.ListOwners AS
                BEGIN
                  SELECT * FROM [dbo].[Owners]
                END
                GO
                DELIMITER //
                """);
        Map<String, ParsedSourceFile> files = parseAll(sql, root);
        var symbols = symbols(files);
        assertThat(symbols).containsKeys("src.main.resources.db.migration.V1__init.sql", "db:owners", "db:owners#id", "db:owners#first_name",
                "db:owners#last_name", "db:pets#owner_id", "db:owner_pets", "db:types#name", "db:owners#email", "db:touch()",
                "db:owners_touch()", "db:listowners()");
        assertThat(symbols).doesNotContainKey("db:owners#constraint");
        assertThat(symbols.get("db:owners").getAnnotations()).containsExactly("table");
        assertThat(symbols.get("db:owners").getStartLine()).isEqualTo(2);
        assertThat(symbols.get("db:owners#first_name").getStartLine()).isEqualTo(4);
        assertThat(symbols.get("db:owners#first_name").getReturnType()).isEqualTo("VARCHAR(30)");
        assertThat(symbols.get("db:owner_pets").getAnnotations()).containsExactly("view");
        assertThat(symbols.get("db:listowners()").getAnnotations()).containsExactly("procedure");

        assertThat(deps(files, DependencyType.FIELD_TYPE)).contains("db:pets#owner_id -> db:owners", "db:pets -> db:types",
                "db:pets#type_id -> db:types", "src.main.resources.db.migration.V2__more.sql -> db:types");
        assertThat(deps(files, DependencyType.FIELD_ACCESS)).contains("db:owner_pets -> db:owners", "db:owner_pets -> db:pets",
                "src.main.resources.db.migration.V1__init.sql -> db:pets", "db:touch() -> db:owners", "db:owners_touch() -> db:owners",
                "src.main.resources.db.migration.V2__more.sql -> db:types", "db:listowners() -> db:owners");
        assertThat(deps(files, DependencyType.METHOD_CALL)).contains("db:owners_touch() -> db:touch()");
    }

    @Test
    void statementSplitting() {
        var st = SqlLanguageParser.split("select ';' from a; -- x;\n/* ; */ select 1 from b;\nCREATE FUNCTION f() AS $body$ a; b; $body$;");
        assertThat(st).hasSize(3);
        assertThat(st.get(1).line()).isEqualTo(2);
        assertThat(st.get(2).text()).contains("$body$ a; b; $body$");
    }
}
