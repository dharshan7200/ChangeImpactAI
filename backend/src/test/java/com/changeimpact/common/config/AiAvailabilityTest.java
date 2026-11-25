package com.changeimpact.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiAvailabilityTest {

    private static StandardEnvironment env(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }

    @Test
    void blankKeyDisablesSpringAiModels() {
        var env = env(Map.of("spring.ai.google.genai.api-key", ""));
        AiAvailability.apply(env);
        assertThat(env.getProperty("app.ai.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.embedding.text")).isEqualTo("none");
        assertThat(env.getProperty("spring.ai.model.image")).isEqualTo("none");
        assertThat(env.getProperty("spring.autoconfigure.exclude")).contains("GoogleGenAiEmbeddingConnectionAutoConfiguration", "PgVectorStoreAutoConfiguration");
    }

    @Test
    void placeholderResolvingToBlankDisables() {
        var env = env(Map.of("spring.ai.google.genai.api-key", "${GEMINI_API_KEY:}"));
        AiAvailability.apply(env);
        assertThat(env.getProperty("app.ai.enabled")).isEqualTo("false");
    }

    @Test
    void configuredKeyKeepsModels() {
        var env = env(Map.of("spring.ai.google.genai.api-key", "some-key"));
        AiAvailability.apply(env);
        assertThat(env.getProperty("app.ai.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.ai.model.chat")).isNull();
        assertThat(env.getProperty("spring.autoconfigure.exclude")).isNull();
    }
}
