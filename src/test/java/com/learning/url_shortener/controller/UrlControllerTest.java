package com.learning.url_shortener.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.url_shortener.dto.ShortenRequest;
import com.learning.url_shortener.service.UrlService;
import com.learning.url_shortener.service.UrlService.ShortenResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UrlController.class)
@TestPropertySource(properties = "app.base-url=http://localhost:8080")
class UrlControllerTest {

    @Autowired
    MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    UrlService service;

    @Test
    void shorten_validRequest_returns201WithBody() throws Exception {
        when(service.shorten("https://example.com"))
                .thenReturn(new ShortenResult("1", "https://example.com"));

        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShortenRequest("https://example.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("1"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/1"))
                .andExpect(jsonPath("$.longUrl").value("https://example.com"));
    }

    @Test
    void shorten_blankUrl_returns400() throws Exception {
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShortenRequest(""))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void shorten_urlExceedsMaxLength_returns400() throws Exception {
        String tooLong = "https://example.com/" + "a".repeat(2048);
        mockMvc.perform(post("/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShortenRequest(tooLong))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void redirect_validCode_returns301WithLocation() throws Exception {
        when(service.resolve("1")).thenReturn(Optional.of("https://example.com"));

        mockMvc.perform(get("/1"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void redirect_unknownCode_returns404() throws Exception {
        when(service.resolve("xyz")).thenReturn(Optional.empty());

        mockMvc.perform(get("/xyz"))
                .andExpect(status().isNotFound());
    }
}
