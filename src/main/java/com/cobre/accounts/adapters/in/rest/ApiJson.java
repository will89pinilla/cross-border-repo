package com.cobre.accounts.adapters.in.rest;

import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON writer of the API bodies (report, balance, transactions). The controllers return its
 * bytes instead of letting Spring's mapper serialize, so the report AC11 compares without Spring
 * ({@code LoadReportDeterminismTest}) is byte for byte the one that goes out through HTTP. Every
 * amount and date is already text in the DTOs; nulls are written ({@code "detail": null}, §8.1).
 */
public final class ApiJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ApiJson() {}

    public static byte[] write(Object body) {
        return MAPPER.writeValueAsBytes(body);
    }
}
