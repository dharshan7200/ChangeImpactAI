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

class CSharpLanguageParserTest {

    @TempDir Path root;
    private CSharpLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new CSharpLanguageParser(new AppProperties());
        assumeTrue(parser.available(), "tree-sitter native library not available on this platform");
    }

    private void aspNetApp() throws Exception {
        write(root, "src/Web/GlobalUsings.cs", "global using App.Core.Entities;\n");
        write(root, "src/Core/Entities/BaseEntity.cs", """
                namespace App.Core.Entities;
                public abstract class BaseEntity { public int Id { get; set; } public void Touch() {} }
                """);
        write(root, "src/Core/Entities/User.cs", """
                using System.ComponentModel.DataAnnotations.Schema;
                namespace App.Core.Entities
                {
                    [Table("Users")]
                    public class User : BaseEntity, IAggregateRoot
                    {
                        public string Email { get; private set; }
                        public Address Home { get; set; }
                        public User(string email) { Email = email; }
                        public void Rename(string n) => Touch();
                        public class Address { public string City { get; set; } public string Format() => City; }
                    }
                    public interface IAggregateRoot { }
                    public record UserDto(int Id, string Email);
                    public enum Role { Admin, Member }
                }
                """);
        write(root, "src/Core/Interfaces/IUserRepository.cs", """
                using App.Core.Entities;
                namespace App.Core.Interfaces;
                public interface IUserRepository { Task<User> FindAsync(int id); User Find(int id); }
                """);
        write(root, "src/Infrastructure/UserRepository.cs", """
                using App.Core.Entities;
                using App.Core.Interfaces;
                namespace App.Infrastructure;
                public class UserRepository : IUserRepository
                {
                    public Task<User> FindAsync(int id) => Task.FromResult(Find(id));
                    public User Find(int id) { var sql = "SELECT * FROM Users WHERE Id = @id"; return new User("x"); }
                }
                """);
        write(root, "src/Web/Controllers/UsersController.cs", """
                using App.Core.Interfaces;
                using Repo = App.Infrastructure.UserRepository;
                using static App.Web.Util;
                namespace App.Web.Controllers;
                [ApiController]
                public class UsersController : ControllerBase
                {
                    private readonly IUserRepository _repo;
                    public UsersController(IUserRepository repo) { _repo = repo; }

                    [HttpGet("{id}")]
                    public async Task<UserDto> Get(int id)
                    {
                        var user = await _repo.FindAsync(id);
                        user.Rename("n");
                        user.Home.Format();
                        User other = _repo.Find(id);
                        var direct = new Repo();
                        direct.Find(1);
                        Log(id);
                        this.Helper();
                        if (id > 0 && other != null) { return new UserDto(id, other.Email); }
                        return null;
                    }
                    private void Helper() { var r = Role.Admin; }
                }
                """);
        write(root, "src/Web/Util.cs", """
                namespace App.Web;
                public static class Util { public static void Log(int x) {} }
                """);
        write(root, "src/Web/Program.cs", """
                using App.Infrastructure;
                var repo = new UserRepository();
                repo.Find(1);
                """);
        write(root, "tests/App.Tests/UserTests.cs", """
                using Xunit;
                namespace App.Tests;
                public class UserTests { [Fact] public void Renames() { new User("a").Rename("b"); } }
                """);
        write(root, "src/Web/obj/Debug/Generated.cs", "class Generated {}");
        write(root, "src/Web/Migrations/20240101_Init.cs", "class Init {}");
    }

    @Test
    void aspNetCoreProject() throws Exception {
        aspNetApp();
        Map<String, ParsedSourceFile> files = parseAll(parser, root);
        assertThat(files).doesNotContainKeys("src/Web/obj/Debug/Generated.cs", "src/Web/Migrations/20240101_Init.cs");
        assertThat(files.get("tests/App.Tests/UserTests.cs").isTest()).isTrue();
        assertThat(files.get("src/Web/Controllers/UsersController.cs").isTest()).isFalse();
        assertThat(files.get("src/Core/Entities/User.cs").getPackageName()).isEqualTo("App.Core.Entities");

        var symbols = symbols(files);
        assertThat(symbols).containsKeys("App.Core.Entities.User", "App.Core.Entities.User#Email", "App.Core.Entities.User.Address#Format",
                "App.Core.Entities.UserDto", "App.Core.Entities.UserDto#Email", "App.Core.Entities.Role#Admin",
                "App.Web.Controllers.UsersController#Get", "src.Web.Program.cs");
        assertThat(symbols.get("App.Core.Entities.UserDto").getSymbolType()).isEqualTo(SymbolType.RECORD);
        assertThat(symbols.get("App.Core.Entities.User#User").getSymbolType()).isEqualTo(SymbolType.CONSTRUCTOR);
        assertThat(symbols.get("App.Web.Controllers.UsersController#Get").getAnnotations()).contains("HttpGet(\"{id}\")");
        assertThat(symbols.get("App.Web.Controllers.UsersController#Helper").getVisibility()).isEqualTo("private");
        assertThat(symbols.get("App.Web.Controllers.UsersController#Get").getComplexity()).isEqualTo(3);

        assertThat(deps(files, DependencyType.EXTENDS)).contains("App.Core.Entities.User -> App.Core.Entities.BaseEntity");
        assertThat(deps(files, DependencyType.IMPLEMENTS)).contains("App.Core.Entities.User -> App.Core.Entities.IAggregateRoot",
                "App.Infrastructure.UserRepository -> App.Core.Interfaces.IUserRepository");
        assertThat(deps(files, DependencyType.ANNOTATION)).contains("App.Core.Entities.User -> db:users");

        String get = "App.Web.Controllers.UsersController#Get";
        assertThat(deps(files, DependencyType.METHOD_CALL)).contains(
                get + " -> App.Core.Interfaces.IUserRepository#FindAsync",  // field type
                get + " -> App.Core.Entities.User#Rename",                  // var = await Task<User>
                get + " -> App.Core.Entities.User.Address#Format",          // property type, nested class
                get + " -> App.Core.Interfaces.IUserRepository#Find",
                get + " -> App.Infrastructure.UserRepository#Find",         // using alias
                get + " -> App.Web.Util#Log",                               // using static
                get + " -> App.Web.Controllers.UsersController#Helper",
                "App.Core.Entities.User#Rename -> App.Core.Entities.BaseEntity#Touch",
                "App.Infrastructure.UserRepository#FindAsync -> App.Infrastructure.UserRepository#Find",
                "src.Web.Program.cs -> App.Infrastructure.UserRepository#Find",
                "App.Tests.UserTests#Renames -> App.Core.Entities.User#Rename"); // global using
        assertThat(deps(files, DependencyType.CONSTRUCTOR_CALL)).contains(get + " -> App.Core.Entities.UserDto",
                get + " -> App.Infrastructure.UserRepository", "App.Infrastructure.UserRepository#Find -> App.Core.Entities.User");
        assertThat(deps(files, DependencyType.FIELD_ACCESS)).contains(get + " -> App.Web.Controllers.UsersController#_repo",
                get + " -> App.Core.Entities.User#Email", "App.Core.Entities.User#User -> App.Core.Entities.User#Email",
                "App.Web.Controllers.UsersController#Helper -> App.Core.Entities.Role#Admin",
                "App.Infrastructure.UserRepository#Find -> db:users");
        assertThat(deps(files, DependencyType.PARAMETER_TYPE)).contains("App.Web.Controllers.UsersController#UsersController -> App.Core.Interfaces.IUserRepository");
        assertThat(deps(files, DependencyType.RETURN_TYPE)).contains(get + " -> App.Core.Entities.UserDto",
                "App.Core.Interfaces.IUserRepository#FindAsync -> App.Core.Entities.User");
        assertThat(deps(files, DependencyType.FIELD_TYPE)).contains("App.Web.Controllers.UsersController#_repo -> App.Core.Interfaces.IUserRepository");
    }

    /** Visual Studio writes a UTF-8 byte-order mark; names must not shift (they did: "ceApp.ssUse"). */
    @Test
    void byteOrderMarkEmojiAndInvalidUtf8() throws Exception {
        java.nio.file.Files.createDirectories(root.resolve("src"));
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] code = "namespace App;\n// caf\u00e9 \uD83D\uDE00\npublic class User { public void Save() { Helper(); } void Helper() {} }\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(root.resolve("src/User.cs"), concat(bom, code));
        byte[] latin1 = "namespace App;\n// stra\u00dfe\npublic class Order { }\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        java.nio.file.Files.write(root.resolve("src/Order.cs"), latin1);
        Map<String, ParsedSourceFile> files = parseAll(parser, root);
        assertThat(symbols(files)).containsKeys("App.User", "App.User#Save", "App.User#Helper", "App.Order");
        assertThat(deps(files, DependencyType.METHOD_CALL)).containsExactly("App.User#Save -> App.User#Helper");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
