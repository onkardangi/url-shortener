package com.learning.url_shortener.service;

import com.learning.url_shortener.entity.Url;
import com.learning.url_shortener.repository.UrlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    @Mock
    UrlRepository repository;

    @Mock
    Base62Encoder encoder;

    @InjectMocks
    UrlService service;

    @Test
    void shorten_savesUrlAndReturnsCode() {
        Url saved = new Url("https://example.com");
        saved.setId(1L);
        when(repository.save(any(Url.class))).thenReturn(saved);
        when(encoder.encode(1L)).thenReturn("1");

        var result = service.shorten("https://example.com");

        assertThat(result.code()).isEqualTo("1");
        assertThat(result.longUrl()).isEqualTo("https://example.com");
        verify(repository).save(any(Url.class));
        verify(encoder).encode(1L);
    }

    @Test
    void resolveUrl_validCode_returnsLongUrl() {
        Url url = new Url("https://example.com");
        url.setId(1L);
        when(encoder.decode("1")).thenReturn(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(url));

        var result = service.resolveUrl("1");

        assertThat(result).isEqualTo("https://example.com");
    }

    @Test
    void resolveUrl_invalidCharInCode_returnsNull() {
        when(encoder.decode("!@#")).thenThrow(new IllegalArgumentException("invalid char"));

        var result = service.resolveUrl("!@#");

        assertThat(result).isNull();
        verify(repository, never()).findById(any());
    }

    @Test
    void resolveUrl_unknownId_returnsNull() {
        when(encoder.decode("xyz")).thenReturn(999L);
        when(repository.findById(999L)).thenReturn(Optional.empty());

        var result = service.resolveUrl("xyz");

        assertThat(result).isNull();
    }
}
