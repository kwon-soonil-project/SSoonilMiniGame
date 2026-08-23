package com.minigame.platform.shared.error;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class RequestIds {
    private static final String ATTRIBUTE = RequestIds.class.getName() + ".value";
    private static final String HEADER = "X-Request-Id";

    private RequestIds() {
    }

    public static String correlationId(HttpServletRequest request) {
        var existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String requestId) {
            return requestId;
        }
        var supplied = request.getHeader(HEADER);
        var requestId = isCanonicalUuid(supplied) ? supplied : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        return requestId;
    }

    public static String commandId(HttpServletRequest request) {
        var supplied = request.getHeader(HEADER);
        if (supplied == null || supplied.isBlank()) {
            return correlationId(request);
        }
        if (!isCanonicalUuid(supplied)) {
            throw new InvalidRequestIdException();
        }
        request.setAttribute(ATTRIBUTE, supplied);
        return supplied;
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
