package com.changeimpact.testing.reports.parser;

import com.changeimpact.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

/**
 * Parsers for CI quality artefacts: JaCoCo XML, JUnit/Surefire XML, PIT {@code mutations.xml} and OWASP
 * Dependency-Check JSON.
 *
 * <p><b>Security</b>: uploads are untrusted. XML is read with a streaming StAX parser that does not resolve
 * DTDs or external entities (XXE-safe); JaCoCo's {@code <!DOCTYPE ... "report.dtd">} declaration is
 * tolerated but never loaded. Input size is bounded by the upload limit.
 */
@Component
public class ReportParsers {

    // ------------------------------------------------------------------
    // Result types
    // ------------------------------------------------------------------

    public record CoverageFileData(String sourcePath, int[] coveredLines, int[] missedLines) {}

    public record CoverageMethodData(String className, String methodName, String desc, Integer firstLine,
                                     int linesCovered, int linesMissed, int branchesCovered, int branchesMissed) {}

    public record JacocoReport(List<CoverageFileData> files, List<CoverageMethodData> methods,
                               int linesCovered, int linesMissed, int branchesCovered, int branchesMissed) {}

    public record TestCaseData(String className, String methodName, String status, Long durationMs, String message) {}

    public record MutationData(String className, String methodName, String desc, Integer line, String mutator,
                               String status, String killingTest, String description) {}

    public record VulnerabilityData(String dependency, String packageId, String vulnerability, String severity,
                                    BigDecimal cvssScore, List<String> cwes, String description) {}

    // ------------------------------------------------------------------
    // Secure XML
    // ------------------------------------------------------------------

    static XMLStreamReader secureReader(InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        try {
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException ignored) {
            // property not supported by this StAX implementation; DTD support is already disabled
        }
        return f.createXMLStreamReader(in);
    }

    private static String attr(XMLStreamReader r, String name) {
        return r.getAttributeValue(null, name);
    }

    private static int intAttr(XMLStreamReader r, String name) {
        String v = attr(r, name);
        return v == null ? 0 : Integer.parseInt(v.trim());
    }

    private static BusinessException invalid(String type, Exception e) {
        return new BusinessException("INVALID_REPORT", "Could not parse " + type + " report: " + e.getMessage());
    }

    // ------------------------------------------------------------------
    // JaCoCo
    // ------------------------------------------------------------------

    public JacocoReport parseJacoco(InputStream in) {
        List<CoverageFileData> files = new ArrayList<>();
        List<CoverageMethodData> methods = new ArrayList<>();
        int[] totals = new int[4];
        String pkg = null;
        String cls = null;
        String sourceFile = null;
        List<Integer> covered = new ArrayList<>();
        List<Integer> missed = new ArrayList<>();
        // current method under construction
        String mName = null, mDesc = null;
        Integer mLine = null;
        int[] mCounters = null;
        int depth = 0;
        int reportCounterDepth = -1;
        boolean sawReport = false;
        try {
            XMLStreamReader r = secureReader(in);
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    switch (r.getLocalName()) {
                        case "report" -> {
                            sawReport = true;
                            reportCounterDepth = depth + 1;
                        }
                        case "package" -> pkg = attr(r, "name");
                        case "class" -> cls = attr(r, "name");
                        case "method" -> {
                            mName = attr(r, "name");
                            mDesc = attr(r, "desc");
                            String line = attr(r, "line");
                            mLine = line == null ? null : Integer.parseInt(line);
                            mCounters = new int[4];
                        }
                        case "sourcefile" -> {
                            sourceFile = attr(r, "name");
                            covered.clear();
                            missed.clear();
                        }
                        case "line" -> {
                            if (sourceFile != null) {
                                int nr = intAttr(r, "nr");
                                if (intAttr(r, "ci") > 0) covered.add(nr);
                                else if (intAttr(r, "mi") > 0) missed.add(nr);
                            }
                        }
                        case "counter" -> {
                            String type = attr(r, "type");
                            int c = intAttr(r, "covered");
                            int m = intAttr(r, "missed");
                            if (mCounters != null && cls != null) {
                                if ("LINE".equals(type)) { mCounters[0] = c; mCounters[1] = m; }
                                if ("BRANCH".equals(type)) { mCounters[2] = c; mCounters[3] = m; }
                            } else if (depth == reportCounterDepth) {
                                if ("LINE".equals(type)) { totals[0] = c; totals[1] = m; }
                                if ("BRANCH".equals(type)) { totals[2] = c; totals[3] = m; }
                            }
                        }
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "method" -> {
                            if (cls != null && mName != null) {
                                methods.add(new CoverageMethodData(cls.replace('/', '.'), mName, mDesc, mLine,
                                        mCounters[0], mCounters[1], mCounters[2], mCounters[3]));
                            }
                            mName = null;
                            mCounters = null;
                        }
                        case "class" -> cls = null;
                        case "sourcefile" -> {
                            String path = (pkg == null || pkg.isEmpty() ? "" : pkg + "/") + sourceFile;
                            files.add(new CoverageFileData(path,
                                    covered.stream().mapToInt(Integer::intValue).toArray(),
                                    missed.stream().mapToInt(Integer::intValue).toArray()));
                            sourceFile = null;
                        }
                        case "package" -> pkg = null;
                        default -> { }
                    }
                    depth--;
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            throw invalid("JaCoCo", e);
        }
        if (!sawReport) {
            throw new BusinessException("INVALID_REPORT", "Not a JaCoCo XML report (missing <report> element)");
        }
        return new JacocoReport(files, methods, totals[0], totals[1], totals[2], totals[3]);
    }

    // ------------------------------------------------------------------
    // JUnit / Surefire (a single file may hold <testsuite> or <testsuites>)
    // ------------------------------------------------------------------

    public List<TestCaseData> parseJunit(InputStream in) {
        List<TestCaseData> cases = new ArrayList<>();
        boolean sawSuite = false;
        String suiteName = null;
        String cName = null, mName = null, status = null, message = null;
        Long duration = null;
        try {
            XMLStreamReader r = secureReader(in);
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "testsuites" -> sawSuite = true;
                        case "testsuite" -> {
                            sawSuite = true;
                            suiteName = attr(r, "name");
                        }
                        case "testcase" -> {
                            // With @DisplayName, Surefire writes the display name into classname; the
                            // enclosing <testsuite name> still holds the Java class name.
                            String classname = attr(r, "classname");
                            cName = isJavaClassName(classname) ? classname : suiteName;
                            mName = attr(r, "name");
                            String t = attr(r, "time");
                            duration = t == null ? null : Math.round(Double.parseDouble(t.replace(",", "")) * 1000);
                            status = "PASSED";
                            message = null;
                        }
                        case "failure" -> { if (mName != null) { status = "FAILED"; message = attr(r, "message"); } }
                        case "error" -> { if (mName != null) { status = "ERROR"; message = attr(r, "message"); } }
                        case "skipped" -> { if (mName != null) { status = "SKIPPED"; message = attr(r, "message"); } }
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && "testcase".equals(r.getLocalName())) {
                    if (mName != null) {
                        cases.add(new TestCaseData(cName == null ? "" : cName, mName, status, duration,
                                message == null ? null : truncate(message, 2000)));
                    }
                    mName = null;
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            throw invalid("JUnit", e);
        }
        if (!sawSuite) {
            throw new BusinessException("INVALID_REPORT", "Not a JUnit XML report (missing <testsuite>)");
        }
        return cases;
    }

    // ------------------------------------------------------------------
    // PIT mutations.xml
    // ------------------------------------------------------------------

    public List<MutationData> parsePit(InputStream in) {
        List<MutationData> out = new ArrayList<>();
        boolean sawRoot = false;
        Map<String, String> fields = null;
        String status = null;
        String current = null;
        StringBuilder text = new StringBuilder();
        try {
            XMLStreamReader r = secureReader(in);
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if ("mutations".equals(n)) {
                        sawRoot = true;
                    } else if ("mutation".equals(n)) {
                        fields = new HashMap<>();
                        status = attr(r, "status");
                    } else if (fields != null) {
                        current = n;
                        text.setLength(0);
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    if (current != null) text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if ("mutation".equals(n) && fields != null) {
                        String line = fields.get("lineNumber");
                        out.add(new MutationData(fields.getOrDefault("mutatedClass", ""), fields.getOrDefault("mutatedMethod", ""),
                                fields.get("methodDescription"), line == null || line.isBlank() ? null : Integer.parseInt(line.trim()),
                                shortMutator(fields.get("mutator")), status == null ? "UNKNOWN" : status,
                                blankToNull(fields.get("killingTest")), blankToNull(fields.get("description"))));
                        fields = null;
                    } else if (fields != null && n.equals(current)) {
                        fields.put(current, text.toString().trim());
                        current = null;
                    }
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            throw invalid("PIT", e);
        }
        if (!sawRoot) {
            throw new BusinessException("INVALID_REPORT", "Not a PIT mutations.xml report (missing <mutations>)");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // OWASP Dependency-Check JSON
    // ------------------------------------------------------------------

    public List<VulnerabilityData> parseDependencyCheck(InputStream in) {
        List<VulnerabilityData> out = new ArrayList<>();
        try {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            JsonNode deps = root.path("dependencies");
            if (!deps.isArray()) {
                throw new BusinessException("INVALID_REPORT", "Not a Dependency-Check JSON report (missing dependencies[])");
            }
            for (JsonNode dep : deps) {
                String file = dep.path("fileName").asString("");
                String pkg = null;
                for (JsonNode p : dep.path("packages")) {
                    String id = p.path("id").asString(null);
                    if (id != null) { pkg = id; break; }
                }
                for (JsonNode v : dep.path("vulnerabilities")) {
                    BigDecimal score = null;
                    if (v.path("cvssv3").has("baseScore")) score = v.path("cvssv3").path("baseScore").decimalValue();
                    else if (v.path("cvssv2").has("score")) score = v.path("cvssv2").path("score").decimalValue();
                    List<String> cwes = new ArrayList<>();
                    v.path("cwes").forEach(c -> cwes.add(c.asString()));
                    out.add(new VulnerabilityData(truncate(file, 500), pkg, v.path("name").asString("UNKNOWN"),
                            v.path("severity").asString(null), score, cwes, truncate(v.path("description").asString(""), 4000)));
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid("Dependency-Check", e);
        }
        return out;
    }

    // ------------------------------------------------------------------

    private static final java.util.regex.Pattern JAVA_CLASS =
            java.util.regex.Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*$");

    static boolean isJavaClassName(String s) {
        return s != null && JAVA_CLASS.matcher(s).matches();
    }

    private static String shortMutator(String mutator) {
        if (mutator == null) return null;
        return mutator.substring(mutator.lastIndexOf('.') + 1);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || "none".equalsIgnoreCase(s) ? null : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
