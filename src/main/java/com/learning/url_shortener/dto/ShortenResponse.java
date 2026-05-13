package com.learning.url_shortener.dto;

public record ShortenResponse(
        String shortUrl,
        String code,
        String longUrl
) {}
