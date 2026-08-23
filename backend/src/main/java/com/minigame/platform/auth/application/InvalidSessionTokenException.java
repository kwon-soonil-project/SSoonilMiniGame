package com.minigame.platform.auth.application;

public final class InvalidSessionTokenException extends RuntimeException {
    public InvalidSessionTokenException() {
        super("Invalid session token");
    }

    InvalidSessionTokenException(Throwable cause) {
        super("Invalid session token", cause);
    }
}
