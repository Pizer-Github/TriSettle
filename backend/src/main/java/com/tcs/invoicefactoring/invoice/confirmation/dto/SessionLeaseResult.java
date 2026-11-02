package com.tcs.invoicefactoring.invoice.confirmation.dto;

import java.util.UUID;

public record SessionLeaseResult(
        UUID sessionId,
        long fencingGeneration,
        boolean replay,
        Integer cachedStatusCode,
        String cachedBody
) {}
