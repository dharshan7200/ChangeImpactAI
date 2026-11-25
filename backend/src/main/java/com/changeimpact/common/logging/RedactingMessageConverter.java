package com.changeimpact.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback {@code %maskedMsg}: the formatted message with credentials masked. */
public class RedactingMessageConverter extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedactor.redact(super.convert(event));
    }
}
