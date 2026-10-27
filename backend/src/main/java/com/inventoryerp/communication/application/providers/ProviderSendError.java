package com.inventoryerp.communication.application.providers;

/**
 * A provider send failure (port of {@code ProviderSendError}). {@code ambiguous} = we cannot tell whether the
 * message was actually delivered; {@code retryAfterMs} overrides the dispatcher backoff when set.
 */
public class ProviderSendError extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final boolean ambiguous;
    private final Long retryAfterMs;

    public ProviderSendError(String message, String code, boolean retryable, boolean ambiguous) {
        this(message, code, retryable, ambiguous, null);
    }

    public ProviderSendError(String message, String code, boolean retryable, boolean ambiguous, Long retryAfterMs) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.ambiguous = ambiguous;
        this.retryAfterMs = retryAfterMs;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean ambiguous() {
        return ambiguous;
    }

    public Long retryAfterMs() {
        return retryAfterMs;
    }
}
