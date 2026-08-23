package com.minigame.platform.shared.abuse;

public final class AbuseLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public AbuseLimitExceededException(long retryAfterSeconds) {
        super("RATE_LIMITED");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
