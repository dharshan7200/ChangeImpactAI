package com.changeimpact.securityanalysis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Runs Semgrep (static pattern matching; it never executes the analysed code) with the bundled rule set
 * {@code security/semgrep-java-rules.yml}, offline ({@code --metrics=off}, no registry access).
 *
 * <p>The process is started without a shell (no argument injection), in the snapshot directory, with a
 * timeout. If Semgrep is not installed the result says so instead of pretending the code is clean.
 */
@Component
@Slf4j
public class SemgrepRunner {

    public record Finding(String ruleId, String severity, String message, String path, int startLine, int endLine,
                          String category, String cwe) {}

    public record Result(String status, String detail, List<Finding> findings) {
        public static Result unavailable(String why) {
            return new Result("UNAVAILABLE", why, List.of());
        }
    }

    private final String command;
    private final int timeoutSeconds;

    public SemgrepRunner(@Value("${app.security.semgrep.command:semgrep}") String command,
                         @Value("${app.security.semgrep.timeout-seconds:180}") int timeoutSeconds) {
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * @param root    directory containing the files
     * @param targets paths relative to {@code root} to scan (only Java files are relevant to the bundled rules)
     */
    public Result scan(Path root, List<String> targets) {
        long start = System.nanoTime();
        Result result = run(root, targets);
        if (!"SKIPPED".equals(result.status())) {
            com.changeimpact.common.metrics.PlatformMetrics.semgrep(result.status().toLowerCase(java.util.Locale.ROOT),
                    java.time.Duration.ofNanos(System.nanoTime() - start));
        }
        return result;
    }

    private Result run(Path root, List<String> targets) {
        if (targets.isEmpty()) {
            return new Result("SKIPPED", "No source files in scope", List.of());
        }
        Path rules = null;
        Path rules2 = null;
        Path output = null;
        try {
            rules = copyRules("security/semgrep-java-rules.yml");
            rules2 = copyRules("security/semgrep-multilang-rules.yml");
            output = Files.createTempFile("cia-semgrep-out", ".json");

            List<String> cmd = new ArrayList<>(List.of(command, "scan", "--config", rules.toString(), "--config", rules2.toString(), "--json",
                    "--metrics=off", "--disable-version-check", "--quiet", "--timeout", "30", "--output", output.toString()));
            for (String t : targets) {
                if (!t.contains("..")) cmd.add(t); // targets come from the diff; never allow escaping the snapshot
            }
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true);
            pb.environment().put("SEMGREP_ENABLE_VERSION_CHECK", "0");
            pb.environment().put("SEMGREP_SEND_METRICS", "off");
            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                return Result.unavailable("Semgrep is not installed or not executable (" + command + ")");
            }
            String console;
            try (InputStream out = p.getInputStream()) {
                console = new String(out.readAllBytes()); // drain so the process cannot block
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result("TIMEOUT", "Semgrep exceeded " + timeoutSeconds + " s", List.of());
            }
            if (p.exitValue() != 0 && Files.size(output) == 0) {
                return new Result("FAILED", "Semgrep exit code " + p.exitValue() + ": " + tail(console), List.of());
            }
            return parse(Files.readString(output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("FAILED", "Interrupted", List.of());
        } catch (Exception e) {
            log.warn("Semgrep scan failed: {}", e.getMessage());
            return new Result("FAILED", e.getMessage(), List.of());
        } finally {
            deleteQuietly(rules);
            deleteQuietly(rules2);
            deleteQuietly(output);
        }
    }

    static Result parse(String json) {
        JsonNode root = JsonMapper.builder().build().readTree(json);
        List<Finding> findings = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            JsonNode meta = r.path("extra").path("metadata");
            String id = r.path("check_id").asString("");
            findings.add(new Finding(
                    id.substring(id.lastIndexOf("cia.") >= 0 ? id.lastIndexOf("cia.") : 0),
                    r.path("extra").path("severity").asString("INFO"),
                    r.path("extra").path("message").asString(""),
                    r.path("path").asString(""),
                    r.path("start").path("line").asInt(0),
                    r.path("end").path("line").asInt(0),
                    meta.path("category").asString("CODE_PATTERN"),
                    meta.path("cwe").isArray() ? meta.path("cwe").path(0).asString(null) : meta.path("cwe").asString(null)));
        }
        int errors = root.path("errors").size();
        return new Result("COMPLETED", errors == 0 ? null : errors + " file(s) could not be fully analysed", findings);
    }

    private static String tail(String s) {
        return s.length() <= 300 ? s : s.substring(s.length() - 300);
    }

    /** A bundled rule file copied to a temporary file (Semgrep reads files, not classpath resources). */
    private static Path copyRules(String resource) throws IOException {
        Path f = Files.createTempFile("cia-semgrep-rules", ".yml");
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
        }
        return f;
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
