package com.rpa.financial_intelligence_system.common;

public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "success", data); }
    public static ApiResponse<Void> ok() { return new ApiResponse<>(0, "success", null); }
}
