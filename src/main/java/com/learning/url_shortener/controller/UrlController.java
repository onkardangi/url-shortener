package com.learning.url_shortener.controller;

import com.learning.url_shortener.dto.ShortenRequest;
import com.learning.url_shortener.dto.ShortenResponse;
import com.learning.url_shortener.service.UrlService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
public class UrlController {

    private final UrlService service;
    private final String baseUrl;

    public UrlController(UrlService service, @Value("${app.base-url}") String baseUrl) {
        this.service = service;
        this.baseUrl = baseUrl;
    }

    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest req) {
        var result = service.shorten(req.url());
        var response = new ShortenResponse(
                baseUrl + "/" + result.code(),
                result.code(),
                result.longUrl()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String longUrl = service.resolveUrl(code);
        if (longUrl == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity
                .status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create(longUrl))
                .build();
    }
}