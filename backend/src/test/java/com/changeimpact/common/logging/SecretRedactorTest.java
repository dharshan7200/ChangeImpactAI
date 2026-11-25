package com.changeimpact.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Credential masking in logs")
class SecretRedactorTest {

    @Test
    @DisplayName("tokens, JWTs, API keys, authorization values, passwords, URL credentials and PEM keys are masked")
    void masks() {
        String in = "token ghp_abcdefghijklmnopqrstuvwxyz0123456789 and ghs_ABCDEFGHIJKLMNOPQRSTUVWX github_pat_11ABCDEFG0123456789_abcdefghij "
                + "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U "
                + "key AIzaSyA-1234567890abcdefghijklmnopqrstu Authorization: Bearer abc.def-ghijklmnop "
                + "password=hunter2secret client_secret: s3cr3tvalue https://x-access-token:ghs_tok@github.com/a/b.git "
                + "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----";
        String out = SecretRedactor.redact(in);
        for (String leak : new String[]{"ghp_abc", "ghs_ABC", "github_pat_11", "eyJhbGci", "AIzaSy", "abc.def-ghijklmnop",
                "hunter2secret", "s3cr3tvalue", "ghs_tok", "MIIEow"}) {
            assertFalse(out.contains(leak), "leaked " + leak + " in: " + out);
        }
        assertTrue(out.contains("password=[REDACTED]") && out.contains("Bearer [REDACTED]") && out.contains("https://[REDACTED]@github.com"));
    }

    @Test
    @DisplayName("ordinary log lines are unchanged")
    void untouched() {
        String line = "Change analysis 0cd79ad2-da1e-4540-bc38-e518f078a3b9 completed: 21 files, 33 symbol changes (commit 500158f732419217507c7656904b8e6aa1bcc0d6)";
        assertEquals(line, SecretRedactor.redact(line));
        assertNull(SecretRedactor.redact(null));
    }
}
