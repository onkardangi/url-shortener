package com.learning.url_shortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShortenRequest(
        @NotBlank
        @Size(max = 2048)
        String url
) {
}