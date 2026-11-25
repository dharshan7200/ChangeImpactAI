package com.changeimpact.parser.language.ts;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tables referenced by SQL written inside code (string literals passed to JDBC, PDO, database/sql, ADO.NET, …). A
 * literal counts as SQL only when it starts like a statement ({@code SELECT … FROM}, {@code INSERT INTO},
 * {@code UPDATE … SET}, {@code DELETE FROM}, {@code WITH …}); table names follow {@code FROM}, {@code JOIN},
 * {@code INTO}, {@code UPDATE} and {@code DELETE FROM}. The links are kept only for tables the repository's own SQL
 * defines (CodeAnalysisService drops the rest). ORM query languages (JPQL, HQL) name entities, so they link only where a table has the entity's name — under the default naming that is its table.
 */
public final class EmbeddedSql {

    /** Qualified-name prefix of database objects: {@code db:owners}, {@code db:owners#id} (a colon, so no path-based module name can collide). */
    public static final String PREFIX = "db:";

    private static final Pattern STATEMENT = Pattern.compile(
            "(?is)^\\s*(select\\s.+\\sfrom\\s|insert\\s+into\\s|update\\s+[\\w.\"`\\[\\]]+\\s+set\\s|delete\\s+from\\s|with\\s+\\w+\\s+as\\s*\\(|merge\\s+into\\s)");
    private static final Pattern TABLE = Pattern.compile(
            "(?i)\\b(?:from|join|into|update|table)\\s+((?:[\"`\\[]?[A-Za-z_][\\w$]*[\"`\\]]?\\.)?[\"`\\[]?[A-Za-z_][\\w$]*[\"`\\]]?)");
    private static final Set<String> NOT_TABLES = Set.of("select", "where", "set", "values", "lateral", "unnest", "dual", "only");

    private EmbeddedSql() {}

    /** Table names (as {@link #name}) referenced by {@code literal} if it is a SQL statement, otherwise empty. */
    public static Set<String> tables(String literal) {
        if (literal == null || literal.length() < 12 || !STATEMENT.matcher(literal).find()) return Set.of();
        Set<String> out = new TreeSet<>();
        Matcher m = TABLE.matcher(literal);
        while (m.find()) {
            String t = name(m.group(1));
            if (!NOT_TABLES.contains(t.substring(PREFIX.length()))) out.add(t);
        }
        return out;
    }

    /**
     * Qualified name of a database object: unquoted, lower case, default schemas dropped
     * ({@code public.Owners} → {@code db:owners}, {@code billing.invoices} → {@code db:billing.invoices}).
     */
    public static String name(String raw) {
        String n = raw.replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT).strip();
        for (String schema : List.of("public.", "dbo.", "main.")) if (n.startsWith(schema)) n = n.substring(schema.length());
        return PREFIX + n;
    }
}
