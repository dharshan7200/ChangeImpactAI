# Library resolution

When ChangeImpact AI analyses a Java commit, it resolves the project's dependency jars from its Maven or Gradle
build. JavaParser's symbol solver can then type calls into libraries, and calls that pass library-typed arguments.
Without the jars, such calls stay `UNRESOLVED`. That also hides some calls between the repository's own classes,
because the parser can't pick an overload when an argument's type is unknown.

## How it works

1. **Read the build.** The build file is read; it is never run, and no plugin is executed.
   * **Maven** (`pom.xml` at the root): every module of the reactor is read with the Maven model builder. That covers
     parent POMs, properties such as `${revision}`, `dependencyManagement` and imported BOMs, as Maven would. The
     project's own modules are excluded, because their sources are analysed directly.
   * **Gradle** (`build.gradle[.kts]` / `settings.gradle[.kts]`): dependency declarations are read as text
     (`GradleBuildReader`), because a Gradle script is code. Understood: Groovy and Kotlin DSL string notation,
     version catalogs (`gradle/libs.versions.toml`, incl. bundles), `platform(...)`/`enforcedPlatform(...)`,
     `mavenBom`, the Spring Boot plugin version (its BOM) and `$var` placeholders from `gradle.properties`.
     Anything computed in code (convention plugins, loops, functions) is not seen, and is reported in the message.
2. **Resolve the dependencies.** Maven Resolver resolves the declared dependencies with Maven's own session
   defaults:
   * scopes compile, provided, runtime and test;
   * transitive test and optional dependencies not followed;
   * exclusions and nearest-wins conflict resolution applied.
3. **Fetch from one repository only.** Only `LIBRARY_REPOSITORY_URL` is contacted (Maven Central by default).
   Repositories declared in a build are mirrored to it, so a build file can't make the server fetch from a host of
   its choosing. Checksums must match.
4. **Type-check against the jars.** Each jar is added to the symbol solver as a `JarTypeSolver`: class files are
   parsed with javassist, never loaded or executed. With a project classpath, reflection resolves JDK classes only,
   so library types come from the versions the project really uses (before, they came from the platform's own
   classpath).
5. **Record the result per commit** (`commit_libraries`):
   * status, build system, jar count and size, the exact artifact list and a fingerprint.
   * **Incremental analysis** reuses rows of a base commit only when its fingerprint is the same.
   * **Re-analysis.** The Java parser version is `java@2` while resolution is on, so commits analysed without
     libraries are re-analysed once.

| Status | Meaning | Effect |
|---|---|---|
| `RESOLVED` | All dependencies resolved | Library calls typed |
| `PARTIAL` | Some could not be resolved | Library calls typed where possible |
| `FAILED` | Network, timeout, limit or unreadable build | Previous behaviour; an explicit **re-index retries** |
| `NO_BUILD_FILE` | No `pom.xml` or Gradle build at the root | Previous behaviour |
| `DISABLED` | `LIBRARY_RESOLUTION_ENABLED=false` | Previous behaviour |

The repository page (Architecture → Rules & coupling → Analysis coverage) shows the status of the indexed commit.

## Measured (2026-11-23, same commits as before)

| Repository | Jars | Resolution time | Unresolved references | Repository-internal edges | Index time |
|---|---|---|---|---|---|
| spring-petclinic @ 500158f (Maven, Spring Boot 4) | 171 | 21 s cold cache | 897 → **6** | 748 → 758 | 35 s |
| apache/commons-lang @ 33a58e5 (Maven) | 22 | 2.5 s | 30,171 → **659** | 62,181 → **64,428** | ~110 s → 146 s |

**What changed in the results:**
* **New internal edges.** These are repository calls whose arguments are library-typed. Before, they were lost:
  in petclinic, test calls such as `owners.findByLastNameStartingWith(eq(...), any(Pageable.class))`; in
  commons-lang, about 2,200 test calls to its own `assertIllegalArgumentException(() -> ...)` helpers. Test
  selection now finds the tests that reach a method only through such calls.
* **The remaining unresolved references** are long generic call chains, such as AssertJ chains with method
  references, which JavaParser's type inference does not handle.
* **Determinism.** A forced re-analysis of petclinic produced identical dependency rows: 2,797 rows, same hash.
* **Gradle.** The Gradle build of spring-petclinic, read on its own, resolved 162 jars, all also in the Maven set.
  The 9 jars that only the Maven build gets are test starters and devtools that `build.gradle` doesn't declare (or
  declares as `developmentOnly`).

## Configuration

| Variable | Default | |
|---|---|---|
| `LIBRARY_RESOLUTION_ENABLED` | `true` | Off = library calls stay unresolved |
| `LIBRARY_REPOSITORY_URL` | `https://repo.maven.apache.org/maven2` | The only repository contacted (e.g. an internal Nexus/Artifactory mirror of Central) |
| `LIBRARY_CACHE_PATH` | `/var/lib/changeimpact/libraries` (volume `libraries`) | Shared jar cache |
| `LIBRARY_MAX_ARTIFACTS` | `400` | More jars → `FAILED` |
| `LIBRARY_MAX_TOTAL_MB` | `1024` | Larger → `FAILED` |
| `LIBRARY_TIMEOUT_SECONDS` | `300` | Per commit |

The backend needs outbound HTTPS to `LIBRARY_REPOSITORY_URL` at runtime (through `HTTPS_PROXY`/`https.proxyHost`
if set).

## Limits

* **Private dependencies.** Only one repository is used. Dependencies published only in a private repository are
  missing (`PARTIAL`) unless `LIBRARY_REPOSITORY_URL` points to a mirror that also serves them. Repository
  credentials are not supported.
* **Gradle builds** are read, not evaluated: dependencies declared in code or convention plugins are not seen.
* **Build location.** Only a build at the repository root is used. Monorepos with several independent builds at
  other paths get `NO_BUILD_FILE`.
* **Version ranges and `LATEST`** resolve to the newest matching version at resolution time; the recorded artifact
  list shows what was used.
* **Disk.** The jar cache grows with the dependencies of all analysed repositories, so give the `libraries` volume
  room. It can be deleted at any time and is refilled on demand.
