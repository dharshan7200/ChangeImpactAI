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

class PhpLanguageParserTest {

    @TempDir Path root;
    private PhpLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new PhpLanguageParser(new AppProperties());
        assumeTrue(parser.available(), "tree-sitter native library not available on this platform");
    }

    private void laravelApp() throws Exception {
        write(root, "app/Models/Model.php", """
                <?php
                namespace App\\Models;
                abstract class Model {
                    public function save(): bool { return true; }
                    public static function create(array $a): static { return new static(); }
                }
                """);
        write(root, "app/Models/User.php", """
                <?php
                namespace App\\Models;
                use App\\Concerns\\Notifiable;
                class User extends Model implements \\JsonSerializable {
                    use Notifiable;
                    public const ROLE_ADMIN = 'admin';
                    protected string $email = '';
                    public function jsonSerialize(): mixed { return ['email' => $this->email]; }
                }
                """);
        write(root, "app/Concerns/Notifiable.php", """
                <?php
                namespace App\\Concerns;
                trait Notifiable {
                    public function notify(string $m): void {}
                }
                """);
        write(root, "app/Repositories/UserRepository.php", """
                <?php
                namespace App\\Repositories;
                use App\\Models\\User;
                class UserRepository {
                    public function find(int $id): ?User { $sql = "SELECT * FROM users WHERE id = ?"; return null; }
                }
                """);
        write(root, "app/Http/Controllers/UserController.php", """
                <?php
                namespace App\\Http\\Controllers;
                use App\\Models\\User;
                use App\\Repositories\\UserRepository as Repo;
                use Illuminate\\Http\\Request;
                use function App\\Support\\format_name;
                class UserController extends Controller {
                    public function __construct(private Repo $repo) {}
                    #[Route('/users')]
                    public function show(Request $r, int $id) {
                        $user = $this->repo->find($id);
                        $user->notify('hi');
                        $u = new User();
                        $u->save();
                        User::create([]);
                        self::helper();
                        if ($id > 0 && $u) { $r->input('x'); }
                        return format_name(User::ROLE_ADMIN);
                    }
                    private static function helper(): void { parent::boot(); }
                }
                """);
        write(root, "app/Http/Controllers/Controller.php", """
                <?php
                namespace App\\Http\\Controllers;
                abstract class Controller { public static function boot(): void {} }
                """);
        write(root, "app/Support/helpers.php", """
                <?php
                namespace App\\Support;
                function format_name(string $n): string { return ucfirst($n); }
                """);
        write(root, "routes/web.php", """
                <?php
                use App\\Http\\Controllers\\UserController;
                use Illuminate\\Support\\Facades\\Route;
                Route::get('/users/{id}', [UserController::class, 'show']);
                """);
        write(root, "tests/Feature/UserTest.php", """
                <?php
                namespace Tests\\Feature;
                use App\\Models\\User;
                class UserTest { public function testSave() { (new User())->save(); } }
                """);
        write(root, "resources/views/home.blade.php", "<div>{{ $x }}</div>");
        write(root, "vendor/laravel/framework/src/Foo.php", "<?php class Foo {}");
    }

    @Test
    void laravelProject() throws Exception {
        laravelApp();
        Map<String, ParsedSourceFile> files = parseAll(parser, root);
        assertThat(files).doesNotContainKeys("resources/views/home.blade.php", "vendor/laravel/framework/src/Foo.php");
        assertThat(files.get("tests/Feature/UserTest.php").isTest()).isTrue();
        assertThat(files.get("app/Models/User.php").getPackageName()).isEqualTo("App.Models");

        var symbols = symbols(files);
        assertThat(symbols).containsKeys("App.Models.User", "App.Models.User#jsonSerialize", "App.Models.User#email",
                "App.Models.User#ROLE_ADMIN", "App.Concerns.Notifiable", "App.Http.Controllers.UserController#__construct",
                "App.Support#format_name", "routes.web.php");
        assertThat(symbols.get("App.Http.Controllers.UserController#__construct").getSymbolType()).isEqualTo(SymbolType.CONSTRUCTOR);
        assertThat(symbols.get("App.Http.Controllers.UserController#show").getAnnotations()).contains("Route('/users')");
        assertThat(symbols.get("App.Http.Controllers.UserController#helper").getVisibility()).isEqualTo("private");
        assertThat(symbols.get("App.Concerns.Notifiable").getAnnotations()).contains("trait");
        assertThat(symbols.get("App.Http.Controllers.UserController#show").getComplexity()).isEqualTo(3);

        assertThat(deps(files, DependencyType.EXTENDS)).contains("App.Models.User -> App.Models.Model",
                "App.Models.User -> App.Concerns.Notifiable", "App.Http.Controllers.UserController -> App.Http.Controllers.Controller");
        assertThat(deps(files, DependencyType.IMPLEMENTS)).contains("App.Models.User -> JsonSerializable");
        assertThat(deps(files, DependencyType.IMPORT)).contains("routes.web.php -> App.Http.Controllers.UserController",
                "app.Http.Controllers.UserController.php -> App.Repositories.UserRepository");

        String show = "App.Http.Controllers.UserController#show";
        assertThat(deps(files, DependencyType.METHOD_CALL)).contains(
                show + " -> App.Repositories.UserRepository#find",       // promoted property type
                show + " -> App.Concerns.Notifiable#notify",             // declared return type ?User, method from trait
                show + " -> App.Models.Model#save",                      // $u = new User(), inherited
                show + " -> App.Models.Model#create",                    // static, inherited
                show + " -> App.Http.Controllers.UserController#helper", // self::
                show + " -> App.Support#format_name",                    // use function
                "App.Http.Controllers.UserController#helper -> App.Http.Controllers.Controller#boot", // parent::
                "routes.web.php -> App.Http.Controllers.UserController#show",                         // [X::class, 'm']
                "routes.web.php -> Illuminate.Support.Facades.Route#get",
                "Tests.Feature.UserTest#testSave -> App.Models.Model#save");
        // $r is a library type: method calls on it are not invented
        assertThat(deps(files, DependencyType.METHOD_CALL)).noneMatch(d -> d.endsWith("#input"));
        assertThat(deps(files, DependencyType.CONSTRUCTOR_CALL)).contains(show + " -> App.Models.User");
        assertThat(deps(files, DependencyType.FIELD_ACCESS)).contains(show + " -> App.Models.User#ROLE_ADMIN",
                "App.Models.User#jsonSerialize -> App.Models.User#email", "App.Repositories.UserRepository#find -> db:users");
        assertThat(deps(files, DependencyType.PARAMETER_TYPE)).contains(show + " -> Illuminate.Http.Request");
        assertThat(deps(files, DependencyType.RETURN_TYPE)).contains("App.Repositories.UserRepository#find -> App.Models.User");
    }

    @Test
    void bracedNamespacesAndGlobalFunctions() throws Exception {
        write(root, "lib/a.php", """
                <?php
                namespace Shop { class Cart { public function total() { return tax(1); } } }
                namespace { function tax($x) { return $x; } }
                """);
        Map<String, ParsedSourceFile> files = parseAll(parser, root);
        assertThat(symbols(files)).containsKeys("Shop.Cart#total", "lib.a.php#tax");
        assertThat(deps(files, DependencyType.METHOD_CALL)).contains("Shop.Cart#total -> lib.a.php#tax");
    }
}
