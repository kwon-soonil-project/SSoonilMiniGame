package com.minigame.platform.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ApiErrorWriter {
    private ApiErrorWriter() {
    }

    public static void write(
            JsonMapper jsonMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        jsonMapper.writeValue(
                response.getOutputStream(),
                new ApiError(code, message, RequestIds.correlationId(request))
        );
    }
}
