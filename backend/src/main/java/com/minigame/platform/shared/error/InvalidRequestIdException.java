package com.minigame.platform.shared.error;

public final class InvalidRequestIdException extends RuntimeException {
    public InvalidRequestIdException() {
        super("ROOM_REQUEST_ID_INVALID");
    }
}
