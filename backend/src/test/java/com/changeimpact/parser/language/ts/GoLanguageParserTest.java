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

class GoLanguageParserTest {

    @TempDir Path root;
    private GoLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new GoLanguageParser(new AppProperties());
        assumeTrue(parser.available(), "tree-sitter native library not available on this platform");
    }

    private void service() throws Exception {
        write(root, "go.mod", "module github.com/acme/shop\n\ngo 1.22\n");
        write(root, "internal/store/store.go", """
                package store

                import "database/sql"

                type Store interface {
                    FindUser(id int) (*User, error)
                }

                type User struct {
                    ID   int    `json:"id"`
                    Name string
                }

                func (u *User) Greeting() string { return "hi " + u.Name }

                type Base struct{}

                func (b *Base) Close() error { return nil }

                type SQLStore struct {
                    Base
                    db *sql.DB
                }

                var DefaultLimit = 10

                func New(db *sql.DB) *SQLStore { return &SQLStore{db: db} }

                func (s *SQLStore) FindUser(id int) (*User, error) {
                    row := s.db.QueryRow("SELECT id, name FROM users WHERE id = $1", id)
                    _ = row
                    return &User{ID: id}, nil
                }
                """);
        write(root, "internal/api/handler.go", """
                package api

                import (
                    "fmt"
                    st "github.com/acme/shop/internal/store"
                )

                type Handler struct {
                    store st.Store
                    impl  *st.SQLStore
                }

                func NewHandler(s *st.SQLStore) *Handler { return &Handler{store: s, impl: s} }

                func (h *Handler) Get(id int) string {
                    u, err := h.store.FindUser(id)
                    if err != nil && id > 0 {
                        return ""
                    }
                    h.impl.Close()
                    other := st.New(nil)
                    other.FindUser(st.DefaultLimit)
                    h.log(u.Greeting())
                    var x st.User
                    x.Greeting()
                    fmt.Println(x.Name)
                    return u.Name
                }

                func (h *Handler) log(m string) { fmt.Println(m) }
                """);
        write(root, "internal/api/handler_test.go", """
                package api

                import "testing"

                func TestGet(t *testing.T) { NewHandler(nil).Get(1) }
                """);
        write(root, "main.go", """
                package main

                import "github.com/acme/shop/internal/api"

                func main() { api.NewHandler(nil) }
                """);
        write(root, "vendor/x/y.go", "package y\nfunc Z() {}\n");
    }

    @Test
    void goModule() throws Exception {
        service();
        Map<String, ParsedSourceFile> files = parseAll(parser, root);
        assertThat(files).containsOnlyKeys("internal/store/store.go", "internal/api/handler.go", "internal/api/handler_test.go", "main.go");
        assertThat(files.get("internal/api/handler_test.go").isTest()).isTrue();
        assertThat(files.get("internal/api/handler.go").getPackageName()).isEqualTo("internal.api");

        var symbols = symbols(files);
        assertThat(symbols).containsKeys("internal.store", "internal.store.Store", "internal.store.Store#FindUser", "internal.store.User#ID",
                "internal.store.SQLStore#FindUser", "internal.store#New", "internal.store#DefaultLimit", "internal.api.Handler#Get",
                "internal.api.Handler#log", "main", "main#main", "internal.api#TestGet");
        assertThat(symbols.get("internal.store.Store").getSymbolType()).isEqualTo(SymbolType.INTERFACE);
        assertThat(symbols.get("internal.api.Handler#log").getVisibility()).isEqualTo("package");
        assertThat(symbols.get("internal.store.User#ID").getAnnotations()).containsExactly("json:\"id\"");
        assertThat(symbols.get("internal.api.Handler#Get").getComplexity()).isEqualTo(3);
        // the package MODULE symbol is emitted once
        assertThat(files.values().stream().flatMap(f -> f.getSymbols().stream()).filter(x -> x.getQualifiedName().equals("internal.api")).count()).isEqualTo(1);

        assertThat(deps(files, DependencyType.IMPORT)).contains("internal.api -> internal.store", "internal.api -> go:fmt",
                "internal.store -> go:database/sql", "main -> internal.api");
        assertThat(deps(files, DependencyType.EXTENDS)).contains("internal.store.SQLStore -> internal.store.Base");
        assertThat(deps(files, DependencyType.IMPLEMENTS)).contains("internal.store.SQLStore -> internal.store.Store");
        assertThat(deps(files, DependencyType.IMPLEMENTS)).doesNotContain("internal.store.User -> internal.store.Store");

        String get = "internal.api.Handler#Get";
        assertThat(deps(files, DependencyType.METHOD_CALL)).contains(
                get + " -> internal.store.Store#FindUser",    // field of interface type
                get + " -> internal.store.Base#Close",        // promoted from embedded struct
                get + " -> internal.store#New",
                get + " -> internal.store.SQLStore#FindUser", // other := st.New(...) returns *SQLStore
                get + " -> internal.store.User#Greeting",     // u from (*User, error); var x st.User
                get + " -> internal.api.Handler#log",
                get + " -> go:fmt#Println",
                "main#main -> internal.api#NewHandler", "internal.api#TestGet -> internal.api.Handler#Get");
        assertThat(deps(files, DependencyType.CONSTRUCTOR_CALL)).contains("internal.store#New -> internal.store.SQLStore",
                "internal.api#NewHandler -> internal.api.Handler");
        assertThat(deps(files, DependencyType.FIELD_ACCESS)).contains(get + " -> internal.api.Handler#store",
                get + " -> internal.store#DefaultLimit", get + " -> internal.store.User#Name",
                "internal.store.SQLStore#FindUser -> db:users");
        assertThat(deps(files, DependencyType.PARAMETER_TYPE)).contains("internal.api#NewHandler -> internal.store.SQLStore");
        assertThat(deps(files, DependencyType.RETURN_TYPE)).contains("internal.store.SQLStore#FindUser -> internal.store.User");
        assertThat(deps(files, DependencyType.FIELD_TYPE)).contains("internal.api.Handler#store -> internal.store.Store");
    }
}
