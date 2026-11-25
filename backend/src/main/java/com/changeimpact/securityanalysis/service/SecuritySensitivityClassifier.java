package com.changeimpact.securityanalysis.service;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic classification of security-relevant code. A symbol is flagged when its <b>resolved call
 * targets</b> (e.g. {@code javax.crypto.Cipher#getInstance(String)}), annotations or names match explicit
 * rules. Every match is returned as evidence; no guessing.
 */
@Component
public class SecuritySensitivityClassifier {

    public record Subject(String qualifiedName, String simpleName, String ownerSimpleName, String symbolType,
                          String annotationText, String ownerAnnotationText, String parameters, String returnType,
                          Collection<String> callTargets) {}

    public record Classification(String category, String severity, List<String> evidence) {}

    private record Rule(String category, String severity, Pattern targets, Pattern annotations, Pattern names) {}

    private static Pattern p(String regex) {
        return regex == null ? null : Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private static final List<Rule> RULES = List.of(
            new Rule("AUTHENTICATION", "HIGH",
                    p("org\\.springframework\\.security\\.authentication\\.|AuthenticationManager|UserDetailsService|PasswordEncoder"
                            + "|SecurityContextHolder|io\\.jsonwebtoken\\.|oauth2|\\.security\\.web\\.authentication"),
                    p("@EnableWebSecurity"),
                    p("^(auth|authenticate|authentication|login|logout|signin|signup|password|credentials?|jwt|session)$|"
                            + "(Authentication|Login|Password|Jwt|Credential|Token)(Service|Provider|Filter|Manager|Controller|Handler|Encoder)$")),
            new Rule("AUTHORIZATION", "HIGH",
                    p("HttpSecurity#(authorizeHttpRequests|authorizeRequests)|AuthorizationManager|AccessDecision|#hasRole|#hasAuthority|#hasAnyRole|#permitAll"),
                    p("@(PreAuthorize|PostAuthorize|Secured|RolesAllowed|PermitAll|DenyAll|PreFilter|PostFilter|EnableMethodSecurity)"),
                    p("(Permission|Authori[sz]ation|AccessControl|Acl)(Service|Manager|Evaluator|Filter)?$|^(isAllowed|canAccess|hasPermission|checkAccess)$")),
            new Rule("CRYPTOGRAPHY", "HIGH",
                    p("^java\\.security\\.|^javax\\.crypto\\.|MessageDigest|Cipher#|KeyGenerator|SecureRandom|KeyStore|KeyPairGenerator|Signature#|Mac#|BCrypt|org\\.bouncycastle|TokenEncryption"),
                    null,
                    p("(encrypt|decrypt|hash|digest|cipher|signature|hmac)")),
            new Rule("SQL", "MEDIUM",
                    p("^java\\.sql\\.|^javax\\.sql\\.|JdbcTemplate|EntityManager#create(Native)?Query|jakarta\\.persistence\\.Query|org\\.hibernate\\.query|#(executeQuery|executeUpdate|prepareStatement|createStatement)"),
                    p("@(Query|NamedQuery|NamedNativeQuery|Modifying)\\b"),
                    null),
            new Rule("SENSITIVE_DATA", "MEDIUM",
                    null,
                    null,
                    p("(password|passwd|secret|token|api_?key|ssn|socialsecurity|creditcard|cardnumber|cvv|iban|accountnumber|dateofbirth|salary|privatekey)")),
            new Rule("INPUT_HANDLING", "HIGH",
                    p("ObjectInputStream#readObject|Runtime#exec|ProcessBuilder#(<init>|start)|DocumentBuilderFactory|SAXParserFactory|XMLInputFactory|ScriptEngine#eval"),
                    null,
                    null)
    );

    private static final Pattern REST = p("@(Request|Get|Post|Put|Delete|Patch)Mapping");
    private static final Pattern WEB_INPUT = p("MultipartFile|HttpServletRequest|@RequestBody|@RequestParam|@PathVariable");

    public List<Classification> classify(Subject s) {
        List<Classification> out = new ArrayList<>();
        String annotations = (s.annotationText() == null ? "" : s.annotationText()) + "\n"
                + (s.ownerAnnotationText() == null ? "" : s.ownerAnnotationText());
        for (Rule rule : RULES) {
            List<String> evidence = new ArrayList<>();
            if (rule.targets() != null) {
                s.callTargets().stream().filter(t -> rule.targets().matcher(t).find()).distinct().limit(5)
                        .forEach(t -> evidence.add("calls " + t));
            }
            if (rule.annotations() != null) {
                for (String line : annotations.split("\n")) {
                    if (!line.isBlank() && rule.annotations().matcher(line).find()) evidence.add("annotated " + line.trim());
                }
            }
            if (rule.names() != null) {
                for (String name : names(s)) {
                    if (rule.names().matcher(name).find()) {
                        evidence.add("name '" + name + "'");
                        break;
                    }
                }
            }
            if (!evidence.isEmpty()) {
                out.add(new Classification(rule.category(), rule.severity(), evidence.stream().distinct().limit(6).toList()));
            }
        }
        // REST endpoints whose input contract or handling changed
        if (s.annotationText() != null && REST.matcher(s.annotationText()).find()) {
            List<String> ev = new ArrayList<>(List.of("HTTP endpoint " + s.annotationText().lines().filter(l -> REST.matcher(l).find()).findFirst().orElse("")));
            if (s.parameters() != null && WEB_INPUT.matcher(s.parameters()).find()) ev.add("takes web input: " + s.parameters());
            if (out.stream().noneMatch(c -> c.category().equals("INPUT_HANDLING"))) {
                out.add(new Classification("INPUT_HANDLING", "MEDIUM", ev));
            }
        }
        return out;
    }

    private static List<String> names(Subject s) {
        List<String> n = new ArrayList<>();
        if (s.simpleName() != null) n.add(s.simpleName());
        if (s.ownerSimpleName() != null) n.add(s.ownerSimpleName());
        return n;
    }
}
