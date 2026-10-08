package com.backend.backend.auth.dto;

public record CsrfResponseDto(String headerName, String token) {
}
