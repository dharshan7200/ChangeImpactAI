package com.changeimpact.common.logging;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback {@code %maskedEx}: stack traces (including exception messages) with credentials masked. */
public class RedactingThrowableConverter extends ExtendedThrowableProxyConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedactor.redact(super.convert(event));
    }
}
