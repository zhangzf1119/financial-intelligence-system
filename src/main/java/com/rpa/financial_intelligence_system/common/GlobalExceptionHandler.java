package com.rpa.financial_intelligence_system.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e, HttpServletRequest request) {
        var errors = e.getBindingResult().getFieldErrors().stream().collect(java.util.stream.Collectors.toMap(f -> f.getField(), f -> f.getDefaultMessage(), (a,b)->a));
        return failure(HttpStatus.BAD_REQUEST, "参数校验失败", request, errors);
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> denied(AccessDeniedException e, HttpServletRequest request) { return failure(HttpStatus.FORBIDDEN, "无权执行此操作", request, null); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> bad(IllegalArgumentException e, HttpServletRequest request) { return failure(HttpStatus.BAD_REQUEST, e.getMessage(), request, null); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> error(Exception e, HttpServletRequest request) { return failure(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), request, null); }

    /** SSE 响应已经锁定为 text/event-stream 时，必须返回合法 SSE 帧，不能再写普通 JSON。 */
    private ResponseEntity<?> failure(HttpStatus status, String message, HttpServletRequest request, Object data) {
        String safe = message == null || message.isBlank() ? "服务器处理失败" : message;
        if (isSse(request)) {
            String escaped = safe.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\r", " ").replace("\n", " ");
            return ResponseEntity.status(status).contentType(MediaType.TEXT_EVENT_STREAM)
                    .body("event: error\ndata: {\"message\":\"" + escaped + "\"}\n\n");
        }
        return ResponseEntity.status(status).body(new ApiResponse<>(status.value(), safe, data));
    }

    private boolean isSse(HttpServletRequest request) {
        if (request == null) return false;
        String uri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        return (uri != null && uri.endsWith("/api/ai/chat/stream"))
                || (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE));
    }
}
