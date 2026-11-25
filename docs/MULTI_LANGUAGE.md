# Multi-language analysis

ChangeImpact AI analyses every language below **by default**. Each language plugs into the same pipeline, so impact
analysis, risk, test selection, reports, the architecture twin, code search and the code browser work the same
way for all of them.

| Language | Parser | Files | Typical projects |
|---|---|---|---|
| Java | JavaParser + symbol solver + the project's dependency jars | `.java` | Spring, Jakarta EE, libraries |
| Python | tree-sitter | `.py` | Django, Flask, FastAPI, libraries |
| JavaScript / TypeScript, incl. React | tree-sitter (JS, TS, TSX grammars) | `.js .jsx .mjs .cjs .ts .tsx .mts .cts` | React, Next.js, Node/Express, Angular services |
| PHP | tree-sitter | `.php` (not `.blade.php`) | Laravel, Symfony, WordPress plugins |
| C# | tree-sitter | `.cs` (not generated `*.Designer.cs`, `*.g.cs`, `Migrations/`) | ASP.NET Core, class libraries |
| Go | tree-sitter + `go.mod` | `.go` (not `*.pb.go`, `testdata/`) | services, CLIs |
| Ruby | tree-sitter | `.rb .rake` | Rails, Sinatra, gems |
| HTML and templates | tree-sitter | `.html .htm .jinja .j2 .twig .njk .liquid` | Thymeleaf, Jinja/Django, Twig, static sites |
| CSS | tree-sitter | `.css` | stylesheets, CSS Modules |
| SQL | own statement reader | `.sql` | schemas, Flyway/Liquibase migrations, stored code |

## Configuration

```bash
ANALYSIS_LANGUAGES=all                    # default: every language above
ANALYSIS_LANGUAGES=java,python,javascript # only these (aliases: typescript, ts, react, c#, golang, py, rb)
ANALYSIS_LANGUAGES=all,-sql,-css          # all but these
```

* Java is always analysed. Unknown names are ignored, with a warning.
* The backend logs what is active at startup: `Languages analysed (10): [java@5, csharp@1, css@2, go@1, html@1,
  javascript@1, php@1, python@3, ruby@1, sql@1]` (verified by a start with `ANALYSIS_LANGUAGES` unset).
* A language whose parser cannot run on the machine (tree-sitter's native library does not load) is skipped, with
  a warning, and the platform keeps working.
* Directories that never hold first-party source are skipped: `node_modules`, `vendor`, `dist`, `build`, `out`,
  `target`, `bin`, `obj`, `coverage`, `.next`, `__pycache__`, virtualenvs, … Minified/bundled files and files over
  1 MB are skipped too.
* **Already analysed commits are re-analysed automatically** when a language is enabled or a parser changes: each
  commit records the languages and parser versions it was analysed with (`java@3`, `python@3`, `javascript@1`, …).

## How names look

All languages share one model: `MODULE`, `CLASS`, `INTERFACE`, `ENUM`, `RECORD`, `METHOD`, `CONSTRUCTOR`, `FIELD`,
`FUNCTION`. Members follow `#`.

| Language | Example qualified names |
|---|---|
| Python | `shop.service` (module), `shop.service.OrderService#place`, `shop.util#helper` |
| JavaScript / TypeScript | `src.components.Button` (file), `src.components.Button#Button` (function component), `src.api.client.ApiClient#get`, `src.types.UserProps` (interface); `index` files are their folder |
| PHP | `App.Models.User`, `App.Models.User#save`, `App.Models.User#email` (property), `App.Support#format_name`; file `routes.web.php` |
| C# | `App.Core.Entities.User`, `App.Core.Entities.User#Email` (property), nested `App.Core.Entities.User.Address`; file with top-level statements `src.Web.Program.cs` |
| Go | package `internal.store` (directory), `internal.store.SQLStore#FindUser`, `internal.store#New`, root package by its name (`main#main`) |
| Ruby | `Billing.Invoice#charge!` (`Billing::Invoice`), `Payable` (module); file `app.models.billing.invoice.rb` |
| HTML | `templates.owners.ownersList.html`; Thymeleaf fragment / Jinja block `templates.fragments.layout.html#layout` |
| CSS | `static.css.site.css`, selectors `static.css.site.css#.card`, `static.css.site.css##main` |
| SQL | file `db.migration.V1__init.sql`; table `db:owners`, column `db:owners#first_name`, view `db:owner_pets`, function/procedure/trigger `db:touch()` |

External targets keep a prefix so they never collide with repository names: `npm:react#useState`,
`go:fmt#Println`, `gem:json`; PHP/C# library classes keep their namespace (`Illuminate.Http.Request`).

Every symbol has lines, parameters and return types as written, visibility, static/abstract flags, annotations
(decorators, attributes, React `component`/`hook`, `trait`, Rails `model`, SQL `table`/`view`/`procedure`/
`trigger`), cyclomatic complexity, and a **content hash over tokens without comments** — reformatting or commenting
code is not a change. A class's hash covers its header (name, bases, annotations); its members change on their own.

## How dependencies are found

All tree-sitter parsers work in two passes. First every declaration of the repository is indexed (types, members,
field and return types, supertypes, exports); then each file is linked against that index. **A link is made only
when the target is certain**: the name is imported or in scope, or the receiver's type is declared. Nothing is
guessed from a matching name elsewhere.

| Language | Linked | Not linked |
|---|---|---|
| JavaScript / TypeScript / React | ES imports (named, default, namespace), `require`, re-exports (`export … from`, `export *`, barrel `index` files), `tsconfig`/`jsconfig` `paths` and `baseUrl`, `.js` → `.ts` ESM paths; calls to imported/local functions, `ns.f()`, `this.m()`, static `Cls.m()`; `new C()`; **JSX elements** (`<Button/>` uses `Button`); `extends`/`implements`; TS parameter and return types; `className="…"` and CSS Modules (`styles.card`) to selectors | calls on values of unknown type (`user.save()` on a parameter without a repository type), dynamic `import()` paths, `.d.ts` declarations |
| PHP | namespaces (both syntaxes), `use` (aliases, groups, `use function`), `extends`/`implements`/traits; `new X`; `X::m()`, `self::`, `static::`, `parent::`; `$this->m()`, `$this->prop->m()`, `$var->m()` from typed parameters, typed/promoted properties, `$x = new X`, declared return types; Laravel callables `[X::class, 'm']`; `X::CONST`; types | magic methods (`__call`, Eloquent `User::where`), untyped receivers, Blade |
| C# | namespaces (file-scoped and block), `using`, `global using` (from any file), aliases, `using static`; base types (class → `EXTENDS`, interface → `IMPLEMENTS`); `new T()`; `this.`/`base.`; static calls; receivers typed by parameter, field, property, `var x = new T()`, `await` of `Task<T>`; field/property access; attributes; `[Table("x")]` | extension methods, LINQ lambdas over unknown types, `dynamic`, reflection, overload choice (one symbol per name) |
| Go | imports through `go.mod` (internal packages vs `go:` externals, aliases); `f()`, `pkg.F()`; method calls on receivers, parameters, struct fields, `x := NewX()`, `&X{}`, `var x X`, methods **promoted from embedded structs**; composite literals; embedding (`EXTENDS`); **implicit interface satisfaction** (`IMPLEMENTS` when a type's method set covers an interface's methods, by name); package variables | calls through function values, generics' type arguments, reflection |
| Ruby | nesting (`module A; class B` and `A::B`), lexical constant lookup; `require`/`require_relative`; superclass, `include`/`extend`/`prepend`; `X.new`; `X.m`; `self.m`, bare `m`, `super`; locals and `@ivars` assigned `X.new`; `attr_*`; Rails `has_many`/`belongs_to`/… (with `class_name:`); ActiveRecord model → table; `create_table` in `db/schema.rb` (or migrations when there is no schema) | `send`, `method_missing`, metaprogramming, calls on values of unknown class |
| HTML / templates | `<script src>`, `<link rel=stylesheet>`, Thymeleaf `@{…}`, root-relative paths under the usual static roots; `th:replace`/`th:insert`/`th:include`/`layout:decorate` (template and fragment), `{% extends/include/import/from/embed %}`; `class` and `id` to selectors | inline `<script>`/`<style>`, navigation links, external URLs, Vue/Svelte SFCs, Blade, Razor, Handlebars |
| CSS | `@import`; each class/id selector is a symbol whose content is every rule mentioning it | SCSS/Less sources |
| SQL | `CREATE TABLE` (columns, inline and table-level `REFERENCES`, `INHERITS`/`PARTITION OF`), `ALTER TABLE … ADD COLUMN / ADD CONSTRAINT`, views, functions, procedures, triggers (`ON` table, `EXECUTE FUNCTION`), indexes; tables read or written by DML, views and stored code; calls to repository functions. Quoting, comments, `$$` bodies, `DELIMITER` and `GO` are understood | dialect-specific syntax beyond DDL basics (read only for table names) |

### Across languages

* **Pages → scripts, stylesheets, templates.** A Thymeleaf page that includes `fragments/layout :: layout` depends
  on that fragment; a change to the layout impacts every page using it.
* **Markup → CSS.** `class="card"` in HTML, `className="card"` in JSX/TSX and `styles.card` from a CSS Module link
  to the selectors that define them (at most 5 stylesheets per class). Changing `.card` impacts the components and
  pages that use it.
* **Code → database.** These become links to `db:` symbols:
  * JPA `@Entity`/`@Table(name)` and fields (`@Column(name)`, `@JoinColumn`, Spring Boot's default snake_case
    naming, `_id` for to-one associations);
  * EF Core `[Table("…")]`;
  * Rails models (table by Rails convention), `has_many`/`belongs_to`;
  * SQL in string literals of every language (`SELECT … FROM owners`, `INSERT INTO`, `UPDATE`, `DELETE FROM`,
    `WITH`; text blocks, heredocs, template strings).

  A change to a column in a migration then impacts the entity field, its repositories and services, and their tests.
  **Database links are kept only when the repository's SQL (or `db/schema.rb`) defines the table or column**; the
  rest are dropped, because they come from conventions and strings and say nothing certain on their own.

## Architecture

```
LanguageRegistry (ANALYSIS_LANGUAGES=all) ── enabled & available parsers, Java first
   ├── JavaLanguageParser      JavaParser + symbol solver (incremental analysis)
   ├── PythonLanguageParser    tree-sitter
   ├── ts/TreeSitterParser     shared base: scanning, parsing, two-pass analysis, Emitter, TypeIndex
   │     ├── JavaScriptLanguageParser (JS/TS/TSX)   ├── PhpLanguageParser     ├── CSharpLanguageParser
   │     ├── GoLanguageParser                       ├── RubyLanguageParser
   │     └── HtmlLanguageParser, CssLanguageParser (CssIndex for selector links)
   └── ts/SqlLanguageParser    statement reader (no tree-sitter SQL grammar on Maven Central)
            │
            ▼  ParsedSourceFile: symbols + dependencies with qualified names, language
CodeAnalysisService ── source_files (language), symbols, dependencies; unmatched db: links dropped
            │
            ▼  unchanged for every language
dependency graph · semantic diff · impact propagation · risk · tests · reports · architecture twin · code search
```

* Adding a language means one class implementing `LanguageParser` (`language()`, `extensions()`, `version()`,
  `experimental()`, `available()`, `incremental()`, `open(root)` → `Session` with `sourceFiles()` and `parse(file)`).
* Incremental analysis (copying unaffected files from an analysed commit) is Java-only; the other languages are
  parsed in full, which takes seconds (no symbol solver).
* Changed files are classified `JAVA_*`, `PYTHON_*` or `SOURCE`/`TEST` (all other languages) for reports.
* The code browser highlights every language (Monaco syntax modes bundled locally) and shows the analysis's links on
  hover / Ctrl-click for all of them.

## Python details

| Python | Symbol | Qualified name |
|---|---|---|
| `pkg/sub/mod.py` (`src/` and `lib/` prefixes dropped) | `MODULE` | `pkg.sub.mod` |
| `pkg/__init__.py` | `MODULE` | `pkg` |
| `class Order` (nested: `Order.Line`) | `CLASS` | `pkg.sub.mod.Order` |
| method / `__init__` | `METHOD` / `CONSTRUCTOR` | `pkg.sub.mod.Order#total` |
| module-level `def` | `FUNCTION` | `pkg.sub.mod#helper` |

Links: `IMPORT` (absolute, relative, aliased), `EXTENDS`, decorators (`ANNOTATION`), `Cls(...)`
(`CONSTRUCTOR_CALL`), `f()`, `module.f()`, `self.m()`, `cls.m()`, `Cls(...).m()`, `x.m()` for locals always
assigned `Cls(...)`, `p.m()` for parameters annotated `p: Cls`, and tables in SQL strings. Tests: `test_*.py`,
`*_test.py`, `conftest.py`, `tests/`, `test/`.

**Semantic diff for languages without overloading.** A same-name callable whose parameter list changed is
`PARAMETERS_CHANGED` (breaking: a parameter removed, renamed, reordered, or a required one added),
`PARAMETERS_EXTENDED` (only optional/variadic parameters added) or `PARAMETER_ANNOTATIONS_CHANGED` (type hints only).

## Verification

**Fixture tests** (one per language, each a small realistic project): `JavaScriptLanguageParserTest` (React + TS
with `tsconfig` paths, barrel re-exports, hooks, class components, CSS Modules, CommonJS), `PhpLanguageParserTest`
(Laravel: promoted properties, traits, `self`/`parent`, `use function`, route callables, braced namespaces),
`CSharpLanguageParserTest` (ASP.NET Core: global usings, aliases, `using static`, `Task<T>` awaits, nested types,
records, `[Table]`; BOM/emoji/Latin-1 files), `GoLanguageParserTest` (go.mod imports, embedding, implicit interfaces,
multi-value returns), `RubyLanguageParserTest` (Rails models, associations, mixins, schema vs migrations, RSpec),
`WebAndSqlParsersTest` (CSS selectors and hashes, Thymeleaf fragments, Jinja inheritance, SQL DDL/DML/functions/
triggers, statement splitting), `JavaAstParserTest.jpaEntitiesAndEmbeddedSql`, `LanguageRegistryTest`,
`FileCategoryTest`. Each asserts that calls on values of unknown type are **not** linked.

**Real repositories** (2026-11-23, shallow clones; parse statistics from all parsers on each repository; "linked" =
the target is a symbol of the same repository):

| Repository | Language | Files (tests) | Symbols | Dependencies | Linked inside the repository | Parse time |
|---|---|---|---|---|---|---|
| alan2207/bulletproof-react @ 9506629 | JS/TS/React | 440 (82) | 1,299 | 4,312 | 2,784 (imports 975, JSX/`new` 766, calls 749, types 294) | 5.2 s |
| dotnet-architecture/eShopOnWeb @ 4da8212 | C# | 244 (45) | 1,198 | 1,664 | 1,660 | 2.2 s |
| go-chi/chi @ c64ed7d | Go | 90 (35) | 717 | 3,669 | 2,587 (calls 1,486, field access 753, `IMPLEMENTS` 18) | 7.3 s |
| sinatra/sinatra @ 2e3f791 | Ruby | 147 (91) | 1,201 | 3,007 | 2,835 (calls 2,086) | 4.6 s |
| laravel/pulse @ 33cc6fc | PHP | 92 (34) | 509 | 2,821 | 956 (calls 370, `new` 143) | 3.1 s |
| spring-projects/spring-petclinic @ 500158f | HTML / CSS / SQL | 12 / 1 / 7 | 16 / 1 / 100 | 117 / 0 / 193 | 117 (fragments 19, stylesheets/scripts 20, CSS classes 78) / – / 193 | 1.2 / 4.1 / 0.04 s |

The remaining dependencies point outside the repository (npm packages, the Go standard library, Laravel, gems) and
are kept as external targets.

**Spot checks.** 25 random linked dependencies per repository were reviewed against the declarations they name,
and none looked wrong. These were also checked line by line in the source and are correct:
`DeleteComment → Button` (JSX), `useInfiniteComments → getInfiniteCommentsQueryOptions`,
`CachedCatalogItemServiceDecorator#Edit → CatalogItemService#Edit` (through a typed field), `Mux#Match → Mux#Find`,
`HelpersTest → Minitest.Test#mock_app` (a method added by reopening `Minitest::Test` in the test helper),
`DatabaseStorage#store → DatabaseStorage#requiresManualKeyHash`, `createOrUpdateOwnerForm.html →
fragments/inputField.html#input`, `db:vet_specialties#vet_id → db:vets`.

**Bugs found by the real runs and fixed:** C# files with a UTF-8 byte-order mark had every name shifted by three
characters (`ceMicrosoft.eShopWeb…`). The tree-sitter binding drops the BOM, so node offsets did not match the file's
bytes. Files are now decoded once, without BOM, and offsets refer to that text (regression test with BOM, emoji and
Latin-1 files). Go's blank identifier `_` was linked as a package variable; it no longer is.

A third finding: petclinic's only stylesheet is compiled Bootstrap, which produced 2,072 selector symbols and would
have flooded the symbol count and the member graph. Stylesheets with more than 500 selectors are now one module; links
into them attach to the stylesheet (PARTIAL).

**Through the platform** (dev stack, `ANALYSIS_LANGUAGES` unset, so `all`): the six repositories were connected,
indexed and read back through the API.

| Repository | Indexing | Labelled | Symbols | Linked / external dependencies | Graph (packages / classes) | Code browser (largest non-Java file) |
|---|---|---|---|---|---|---|
| spring-petclinic | 51 s (re-analysis: `java@2` → `java@3 … sql@1`) | Java | 440 (Java 323, HTML 16, CSS 1, SQL 100) | 1,122 / 1,998 | 15 / 91 nodes | `petclinic.css` 200 OK |
| bulletproof-react | 18 s | JavaScript/TypeScript | 1,310 | 2,785 / 1,528 | 172 / 714 | `mockServiceWorker.js`: 9 of 9 references linked |
| eShopOnWeb | 14 s | C# | 1,716 | 1,661 / 4 | 90 / 275 | `ManageController.cs`: 78 of 78 |
| go-chi/chi | 18 s | Go | 717 | 2,587 / 1,082 | 18 / 78 | `mux_test.go`: 1 of 1 |
| sinatra | 14 s | Ruby | 1,203 | 2,835 / 172 | 16 / 392 | `lib/sinatra/base.rb`: 394 of 415 |
| laravel/pulse | 10 s | PHP | 518 | 956 / 1,860 | 30 / 183 | `DatabaseStorage.php`: 59 of 136 |

* Every commit recorded `java@3, csharp@1, css@2, go@1, html@1, javascript@1, php@1, python@3, ruby@1, sql@1`.
* No unmatched `db:` link was stored.
* **petclinic database links:** 13 links from JPA entities to `schema.sql` objects were kept, all matching the schema:
  * tables: `Owner → db:owners`, `Pet → db:pets`, `PetType → db:types`, `Visit → db:visits`,
    `Specialty → db:specialties`, `Vet → db:vets`;
  * columns: `Visit#date → db:visits#visit_date` (`@Column(name)`), `Pet#type → db:pets#type_id` (`@JoinColumn`),
    `Pet#birthDate → db:pets#birth_date` (snake_case), and `Owner` `address`, `city`, `telephone`, `Visit#description`.

  Fields inherited from `@MappedSuperclass`es (`Person#firstName`, `BaseEntity#id`) are now linked from the entity
  as well (`Owner → db:owners#first_name`, `Vet → db:vets#id`; Java parser version 5).

## Limitations

* **No full type inference** in the tree-sitter languages: calls on values whose type is not declared or
  constructed in view stay unlinked. The graph under-approximates dynamic dispatch; it does not invent links.
* **C# overloads** share one symbol per name; **Go interfaces** are matched by method names, not signatures.
* **Several schemas for the same table** (one per database vendor, as in spring-petclinic) are all analysed;
  links go to the first by path.
* **`@MappedSuperclass` fields** are linked from the entity (`Owner → db:owners#first_name`), not from the field
  declaration in the superclass: the link must live in the entity's file so incremental analysis stays identical to a
  full one.
* **Framework-sized stylesheets** (over 500 selectors) are one symbol: a change anywhere in them is one change.
* **Database links** depend on naming: JPQL/HQL entity names, custom naming strategies, or tables created only at
  runtime lose links (never gain wrong ones beyond a same-named table).
* **Security:** bundled Semgrep rules cover Java, Python, JavaScript/TypeScript, PHP, C#, Go and Ruby
  (`security/semgrep-multilang-rules.yml`, 39 rules, every rule with positive and negative fixture lines). The
  sensitivity classifier is Java/Spring-only. Architecture rules work on every language's qualified names.
* **Accuracy** has been spot-checked on the real repositories above, not measured systematically.
* **Platforms:** tree-sitter's bundled natives are for glibc Linux, macOS and Windows on x86-64 and arm64; the
  Docker image is Debian-based.
