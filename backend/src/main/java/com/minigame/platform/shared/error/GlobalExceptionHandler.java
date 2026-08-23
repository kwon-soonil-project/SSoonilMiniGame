package com.minigame.platform.shared.error;

import com.minigame.platform.room.domain.RoomRuleViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Map<String, HttpStatus> ROOM_STATUSES = Map.of(
            "ROOM_NOT_FOUND", HttpStatus.NOT_FOUND,
            "ROOM_PASSWORD_INVALID", HttpStatus.FORBIDDEN,
            "ROOM_PARTICIPANT_NOT_FOUND", HttpStatus.FORBIDDEN,
            "ROOM_FULL", HttpStatus.CONFLICT,
            "ROOM_MAX_PLAYERS_TOO_SMALL", HttpStatus.CONFLICT,
            "ROOM_REQUEST_ID_INVALID", HttpStatus.BAD_REQUEST
    );
    private static final Map<String, String> MESSAGES = Map.of(
            "ROOM_NOT_FOUND", "방을 찾을 수 없습니다.",
            "ROOM_PASSWORD_INVALID", "방 비밀번호가 올바르지 않습니다.",
            "ROOM_PARTICIPANT_NOT_FOUND", "방 참가자만 요청할 수 있습니다.",
            "ROOM_FULL", "방의 참가 인원이 가득 찼습니다.",
            "ROOM_MAX_PLAYERS_TOO_SMALL", "현재 참가 인원보다 최대 인원을 작게 설정할 수 없습니다.",
            "ROOM_REQUEST_ID_INVALID", "요청 ID는 UUID 형식이어야 합니다."
    );

    @ExceptionHandler(RoomRuleViolation.class)
    ResponseEntity<ApiError> roomRule(RoomRuleViolation exception, HttpServletRequest request) {
        var status = ROOM_STATUSES.getOrDefault(exception.code(), HttpStatus.CONFLICT);
        var message = MESSAGES.getOrDefault(exception.code(), "방 요청을 처리할 수 없습니다.");
        return response(status, exception.code(), message, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.", request);
    }

    @ExceptionHandler(InvalidRequestIdException.class)
    ResponseEntity<ApiError> invalidRequestId(
            InvalidRequestIdException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "ROOM_REQUEST_ID_INVALID",
                "요청 ID는 UUID 형식이어야 합니다.",
                request
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식을 확인해 주세요.", request);
    }

    private static ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(new ApiError(code, message, RequestIds.correlationId(request)));
    }
}
