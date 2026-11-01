package com.tcs.invoicefactoring.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard API response envelope for all successful responses.
 * Errors use {@link ApiError} instead.
 *
 * @param <T> the payload type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String message,
        T data
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static ApiResponse<Void> ok(String message) {
        return new ApiResponse<>(true, message, null);
    }
}
