package com.changeimpact.parser.libraries;

import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Resolves the dependency jars of an analysed Java project so the symbol solver can type library calls. Jars are only
 * read (class names and signatures, by JavaParser's {@code JarTypeSolver}); nothing from them is loaded or executed,
 * and no build tool or plugin of the project is run.
 * <ul>
 *   <li><b>Maven</b>: the project's POMs (reactor modules included) are read with the Maven model builder — parents,
 *       properties ({@code ${revision}}) and imported BOMs resolved as Maven does; reactor modules are excluded (their
 *       sources are analysed directly).</li>
 *   <li><b>Gradle</b>: declarations read as text ({@link GradleBuildReader}).</li>
 *   <li>Only the configured repository (Maven Central by default) is contacted: repositories declared in the build are
 *       mirrored to it, so a build file cannot make the server fetch from arbitrary hosts. Checksums must match.</li>
 *   <li>Limits: {@code max-artifacts}, {@code max-total-mb}, {@code timeout-seconds}. Jars are cached on disk and
 *       shared between repositories.</li>
 * </ul>
 */
@Component
@Slf4j
public class LibraryResolver {

    public enum Status { RESOLVED, PARTIAL, FAILED, NO_BUILD_FILE, DISABLED }

    /** Outcome for one commit. {@code jars} empty unless RESOLVED/PARTIAL. */
    public record Classpath(Status status, String buildSystem, List<Path> jars, List<String> artifacts, long totalBytes,
                            String fingerprint, String message, long millis) {
        public boolean usable() { return status == Status.RESOLVED || status == Status.PARTIAL; }

        static Classpath of(Status status, String buildSystem, String message) {
            return new Classpath(status, buildSystem, List.of(), List.of(), 0, status.name().toLowerCase(Locale.ROOT), message, 0);
        }
    }

    private static final int MAX_POMS = 200;

    private final boolean enabled;
    private final String repositoryUrl;
    private final Path cachePath;
    private final int maxArtifacts;
    private final long maxTotalBytes;
    private final int timeoutSeconds;
    private final RepositorySystem system;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "library-resolver");
        t.setDaemon(true);
        return t;
    });

    public LibraryResolver(@Value("${app.analysis.libraries.enabled:true}") boolean enabled,
                           @Value("${app.analysis.libraries.repository-url:https://repo.maven.apache.org/maven2}") String repositoryUrl,
                           @Value("${app.analysis.libraries.cache-path:${java.io.tmpdir}/changeimpact/libraries}") String cachePath,
                           @Value("${app.analysis.libraries.max-artifacts:400}") int maxArtifacts,
                           @Value("${app.analysis.libraries.max-total-mb:1024}") long maxTotalMb,
                           @Value("${app.analysis.libraries.timeout-seconds:300}") int timeoutSeconds) {
        this.enabled = enabled;
        this.repositoryUrl = repositoryUrl;
        this.cachePath = Path.of(cachePath);
        this.maxArtifacts = maxArtifacts;
        this.maxTotalBytes = maxTotalMb * 1024 * 1024;
        this.timeoutSeconds = timeoutSeconds;
        this.system = enabled ? new RepositorySystemSupplier().get() : null;
        if (enabled) log.info("Library resolution for Java analysis: on ({}; cache {})", repositoryUrl, cachePath);
    }

    public boolean enabled() { return enabled; }

    /** Dependency jars of the project checked out at {@code root}; never throws. */
    public Classpath resolve(Path root) {
        if (!enabled) return Classpath.of(Status.DISABLED, null, "Library resolution is switched off (LIBRARY_RESOLUTION_ENABLED=false)");
        boolean maven = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradle = !maven && GradleBuildReader.isGradleBuild(root);
        if (!maven && !gradle) return Classpath.of(Status.NO_BUILD_FILE, null, "No pom.xml or Gradle build at the repository root");
        String buildSystem = maven ? "MAVEN" : "GRADLE";
        long start = System.nanoTime();
        Future<Classpath> f = executor.submit(() -> maven ? resolveMaven(root) : resolveGradle(root));
        try {
            Classpath c = f.get(timeoutSeconds, TimeUnit.SECONDS);
            return new Classpath(c.status(), c.buildSystem(), c.jars(), c.artifacts(), c.totalBytes(), c.fingerprint(), c.message(),
                    (System.nanoTime() - start) / 1_000_000);
        } catch (TimeoutException e) {
            f.cancel(true);
            return Classpath.of(Status.FAILED, buildSystem, "Dependency resolution took longer than " + timeoutSeconds + " s");
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log.warn("Library resolution failed for {}: {}", root, cause.toString());
            return Classpath.of(Status.FAILED, buildSystem, "Dependency resolution failed: " + abbreviate(String.valueOf(cause.getMessage())));
        }
    }

    // ------------------------------------------------------------------ Maven

    record Module(String groupId, String artifactId, String version, Path pom) {
        String key() { return groupId + ":" + artifactId; }
    }

    private Classpath resolveMaven(Path root) throws Exception {
        List<Module> reactor = reactor(root);
        if (reactor.isEmpty()) return Classpath.of(Status.FAILED, "MAVEN", "pom.xml could not be read");
        Set<String> reactorKeys = new HashSet<>();
        reactor.forEach(m -> reactorKeys.add(m.key()));
        RepositorySystemSession session = session(new ReactorReader(reactor));
        List<RemoteRepository> repos = List.of(central());

        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        LinkedHashMap<String, Dependency> managed = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Module m : reactor) {
            ArtifactDescriptorRequest req = new ArtifactDescriptorRequest(new DefaultArtifact(m.groupId(), m.artifactId(), "pom", m.version()), repos, null);
            ArtifactDescriptorResult d;
            try {
                d = system.readArtifactDescriptor(session, req);
            } catch (ArtifactDescriptorException e) {
                problems.add(m.key() + ": " + abbreviate(e.getMessage()));
                continue;
            }
            for (Dependency dep : d.getManagedDependencies()) managed.putIfAbsent(key(dep.getArtifact()), dep);
            for (Dependency dep : d.getDependencies()) {
                if (reactorKeys.contains(dep.getArtifact().getGroupId() + ":" + dep.getArtifact().getArtifactId())) continue;
                if (JavaScopes.SYSTEM.equals(dep.getScope()) || dep.isOptional() && !JavaScopes.COMPILE.equals(dep.getScope())) continue;
                deps.putIfAbsent(key(dep.getArtifact()), dep.setOptional(false));
            }
        }
        if (deps.isEmpty() && !problems.isEmpty()) return Classpath.of(Status.FAILED, "MAVEN", "Could not read the build: " + String.join("; ", problems));
        return collect(session, repos, "MAVEN", new ArrayList<>(deps.values()), new ArrayList<>(managed.values()), reactorKeys, problems);
    }

    /** Root POM and its modules (recursively), with coordinates (inherited from the parent where omitted). */
    static List<Module> reactor(Path root) {
        List<Module> out = new ArrayList<>();
        Deque<Path> todo = new ArrayDeque<>(List.of(root.resolve("pom.xml")));
        Set<Path> seen = new HashSet<>();
        Map<String, String> rootProps = null;
        while (!todo.isEmpty() && out.size() < MAX_POMS) {
            Path pom = todo.poll().normalize();
            if (!pom.startsWith(root.normalize()) || !seen.add(pom) || !Files.isRegularFile(pom)) continue;
            Model model;
            try (Reader r = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
                model = new MavenXpp3Reader().read(r, false);
            } catch (Exception e) {
                continue;
            }
            Map<String, String> props = new HashMap<>();
            if (rootProps != null) props.putAll(rootProps);
            model.getProperties().forEach((k, v) -> props.put(String.valueOf(k), String.valueOf(v)));
            if (rootProps == null) rootProps = props;
            String g = model.getGroupId() != null ? model.getGroupId() : model.getParent() != null ? model.getParent().getGroupId() : null;
            String v = model.getVersion() != null ? model.getVersion() : model.getParent() != null ? model.getParent().getVersion() : null;
            if (v != null) v = GradleBuildReader.interpolate(v.replace("${project.version}", "${revision}"), props);
            if (g != null && v != null && !v.contains("${") && model.getArtifactId() != null) {
                out.add(new Module(g, model.getArtifactId(), v, pom));
            }
            for (String module : model.getModules()) todo.add(pom.getParent().resolve(module).resolve("pom.xml"));
            model.getProfiles().forEach(p -> p.getModules().forEach(module -> todo.add(pom.getParent().resolve(module).resolve("pom.xml"))));
        }
        return out;
    }

    /** Lets the model builder find the reactor's own POMs (parents, modules) without them being on any repository. */
    private record ReactorReader(List<Module> reactor) implements WorkspaceReader {
        @Override public WorkspaceRepository getRepository() { return new WorkspaceRepository("reactor"); }

        @Override public File findArtifact(Artifact artifact) {
            if (!"pom".equals(artifact.getExtension())) return null;
            for (Module m : reactor) {
                if (m.groupId().equals(artifact.getGroupId()) && m.artifactId().equals(artifact.getArtifactId())
                        && m.version().equals(artifact.getBaseVersion())) return m.pom().toFile();
            }
            return null;
        }

        @Override public List<String> findVersions(Artifact artifact) {
            return reactor.stream().filter(m -> m.groupId().equals(artifact.getGroupId()) && m.artifactId().equals(artifact.getArtifactId()))
                    .map(Module::version).toList();
        }
    }

    // ------------------------------------------------------------------ Gradle

    private Classpath resolveGradle(Path root) throws Exception {
        GradleBuildReader.Result build = GradleBuildReader.read(root);
        RepositorySystemSession session = session(null);
        List<RemoteRepository> repos = List.of(central());
        LinkedHashMap<String, Dependency> managed = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (GradleBuildReader.Coordinate bom : build.boms()) {
            try {
                ArtifactDescriptorResult d = system.readArtifactDescriptor(session, new ArtifactDescriptorRequest(
                        new DefaultArtifact(bom.groupId(), bom.artifactId(), "pom", bom.version()), repos, null));
                d.getManagedDependencies().forEach(dep -> managed.putIfAbsent(key(dep.getArtifact()), dep));
            } catch (ArtifactDescriptorException e) {
                problems.add("BOM " + bom.key() + ":" + bom.version() + " not found");
            }
        }
        Map<String, String> managedVersions = new HashMap<>();
        managed.values().forEach(d -> managedVersions.putIfAbsent(d.getArtifact().getGroupId() + ":" + d.getArtifact().getArtifactId(), d.getArtifact().getVersion()));
        List<Dependency> deps = new ArrayList<>();
        for (GradleBuildReader.Coordinate c : build.dependencies()) {
            String v = c.version() != null ? c.version() : managedVersions.get(c.key());
            if (v == null) { problems.add(c.key() + " has no version (no BOM provides one)"); continue; }
            deps.add(new Dependency(new DefaultArtifact(c.groupId(), c.artifactId(), "jar", v),
                    "test".equals(c.scope()) ? JavaScopes.TEST : "provided".equals(c.scope()) ? JavaScopes.PROVIDED : JavaScopes.COMPILE));
        }
        if (!build.unparsed().isEmpty()) problems.add("declarations not understood (computed in code): " + String.join(" | ", build.unparsed()));
        if (deps.isEmpty()) return Classpath.of(Status.FAILED, "GRADLE", "No dependency with a version found in the Gradle build"
                + (problems.isEmpty() ? "" : "; " + String.join("; ", problems)));
        return collect(session, repos, "GRADLE", deps, new ArrayList<>(managed.values()), Set.of(), problems);
    }

    // ------------------------------------------------------------------ shared

    private Classpath collect(RepositorySystemSession session, List<RemoteRepository> repos, String buildSystem, List<Dependency> deps,
                              List<Dependency> managed, Set<String> reactorKeys, List<String> problems) throws Exception {
        CollectRequest collect = new CollectRequest(deps, managed, repos);
        DependencyRequest request = new DependencyRequest(collect, DependencyFilterUtils.andFilter(
                DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE, JavaScopes.PROVIDED, JavaScopes.RUNTIME, JavaScopes.TEST),
                (node, parents) -> node.getArtifact() == null
                        || !reactorKeys.contains(node.getArtifact().getGroupId() + ":" + node.getArtifact().getArtifactId())));
        List<ArtifactResult> results;
        boolean partial = !problems.isEmpty();
        try {
            results = system.resolveDependencies(session, request).getArtifactResults();
        } catch (DependencyResolutionException e) {
            partial = true;
            results = e.getResult() == null ? List.of() : e.getResult().getArtifactResults();
            problems.add(abbreviate(e.getMessage()));
        }
        TreeMap<String, Path> jars = new TreeMap<>();
        long total = 0;
        for (ArtifactResult r : results) {
            if (!r.isResolved() || r.getArtifact().getFile() == null || !"jar".equals(r.getArtifact().getExtension())) continue;
            Artifact a = r.getArtifact();
            String id = a.getGroupId() + ":" + a.getArtifactId() + (a.getClassifier().isEmpty() ? "" : ":" + a.getClassifier()) + ":" + a.getVersion();
            if (jars.putIfAbsent(id, a.getFile().toPath()) == null) total += a.getFile().length();
            if (jars.size() > maxArtifacts) {
                return Classpath.of(Status.FAILED, buildSystem, "More than " + maxArtifacts + " dependency jars (LIBRARY_MAX_ARTIFACTS)");
            }
            if (total > maxTotalBytes) {
                return Classpath.of(Status.FAILED, buildSystem, "Dependency jars exceed " + (maxTotalBytes >> 20) + " MB (LIBRARY_MAX_TOTAL_MB)");
            }
        }
        if (jars.isEmpty()) return Classpath.of(Status.FAILED, buildSystem, "No dependency jar could be resolved" + (problems.isEmpty() ? "" : ": " + String.join("; ", problems)));
        List<String> artifacts = List.copyOf(jars.keySet());
        return new Classpath(partial ? Status.PARTIAL : Status.RESOLVED, buildSystem, List.copyOf(jars.values()), artifacts, total,
                fingerprint(artifacts), problems.isEmpty() ? null : abbreviate(String.join("; ", problems)), 0);
    }

    private RepositorySystemSession session(WorkspaceReader reader) {
        // Maven's own session defaults: transitive test/provided/optional dependencies are not followed, exclusions and
        // dependency management apply, version conflicts are resolved nearest-wins — the classpath Maven would build.
        DefaultRepositorySystemSession s = org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession();
        s.setLocalRepositoryManager(system.newLocalRepositoryManager(s, new LocalRepository(cachePath.toFile())));
        s.setWorkspaceReader(reader);
        // every repository a build declares is served by the configured one: no fetching from hosts chosen by the build
        s.setMirrorSelector(new DefaultMirrorSelector().add("changeimpact-mirror", repositoryUrl, "default", false, false, "*", "default"));
        s.setProxySelector(proxies());
        s.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        s.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_NEVER);
        s.setIgnoreArtifactDescriptorRepositories(true);
        s.setConfigProperty("aether.connector.connectTimeout", 15_000);
        s.setConfigProperty("aether.connector.requestTimeout", 60_000);
        s.setConfigProperty("aether.checksums.forSignature", false);
        s.setSystemProperties(System.getProperties());
        s.setConfigProperty("java.version", System.getProperty("java.version"));
        return s;
    }

    private RemoteRepository central() {
        return new RemoteRepository.Builder("central", "default", repositoryUrl)
                .setReleasePolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                .setSnapshotPolicy(new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                .build();
    }

    /** HTTPS proxy from the JVM settings ({@code https.proxyHost}) or {@code HTTPS_PROXY}, as other outbound clients use. */
    private static ProxySelector proxies() {
        DefaultProxySelector selector = new DefaultProxySelector();
        String host = System.getProperty("https.proxyHost"), port = System.getProperty("https.proxyPort");
        if ((host == null || host.isBlank()) && System.getenv("HTTPS_PROXY") != null) {
            URI u = URI.create(System.getenv("HTTPS_PROXY"));
            host = u.getHost();
            port = String.valueOf(u.getPort() > 0 ? u.getPort() : 3128);
        }
        if (host != null && !host.isBlank()) {
            String nonProxy = System.getProperty("http.nonProxyHosts", "localhost|127.0.0.1");
            selector.add(new Proxy(Proxy.TYPE_HTTP, host, Integer.parseInt(port == null ? "3128" : port)), nonProxy);
        }
        return selector;
    }

    private static String key(Artifact a) {
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getExtension() + ":" + a.getClassifier();
    }

    static String fingerprint(List<String> artifacts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String a : artifacts) md.update((a + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }
}
