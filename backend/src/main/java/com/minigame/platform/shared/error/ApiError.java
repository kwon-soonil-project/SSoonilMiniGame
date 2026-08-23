package com.minigame.platform.shared.error;

public record ApiError(String code, String message, String requestId) {
}
