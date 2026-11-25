package com.changeimpact.common.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * The AI provider (Google Gemini) is optional. When {@code GEMINI_API_KEY} is set to an empty value — as
 * docker-compose.full.yml does when .env leaves it blank — Spring AI's Gemini client would refuse to be created and
 * take the whole application down. This listener runs before any bean is created and, in that case, switches the
 * Spring AI chat, embedding, image and pgvector-store auto-configurations off (excluded) and sets
 * {@code app.ai.enabled=false}, so every deterministic analysis keeps working and AI features report themselves as
 * not configured. An unset variable keeps the application.yml default (the key is then injected by a network proxy).
 */
public class AiAvailability implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String SOURCE = "changeimpactAiAvailability";

    /** Spring AI auto-configurations that need a configured provider (connection details fail without a key). */
    static final String EXCLUDES = String.join(",",
            "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration",
            "org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration",
            "org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration",
            "org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageConnectionAutoConfiguration",
            "org.springframework.ai.model.google.genai.autoconfigure.image.GoogleGenAiImageAutoConfiguration",
            "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        apply(event.getEnvironment());
    }

    static void apply(ConfigurableEnvironment env) {
        String key;
        try {
            key = env.getProperty("spring.ai.google.genai.api-key");
        } catch (IllegalArgumentException e) {
            key = null;
        }
        boolean enabled = key != null && !key.isBlank();
        Map<String, Object> props = enabled
                ? Map.of("app.ai.enabled", "true")
                : Map.of("app.ai.enabled", "false",
                        "spring.ai.model.chat", "none",
                        "spring.ai.model.embedding.text", "none",
                        "spring.ai.model.image", "none",
                        "spring.autoconfigure.exclude", mergedExcludes(env));
        env.getPropertySources().addFirst(new MapPropertySource(SOURCE, props));
    }

    private static String mergedExcludes(ConfigurableEnvironment env) {
        String existing = env.getProperty("spring.autoconfigure.exclude", "");
        return existing.isBlank() ? EXCLUDES : existing + "," + EXCLUDES;
    }
}
