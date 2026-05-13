package com.learning.url_shortener.service;

import com.learning.url_shortener.entity.Url;
import com.learning.url_shortener.repository.UrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UrlService {

    private final UrlRepository repository;
    private final Base62Encoder encoder;

    public UrlService(UrlRepository repository, Base62Encoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @Transactional
    public ShortenResult shorten(String longUrl) {
        Url url = new Url(longUrl);
        Url saved = repository.save(url);  // After save(), saved.getId() is populated
        String code = encoder.encode(saved.getId());
        return new ShortenResult(code, saved.getLongUrl());
    }

    @Transactional(readOnly = true)
    public Optional<String> resolve(String code) {
        long id;
        try {
            id = encoder.decode(code);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return repository.findById(id).map(Url::getLongUrl);
    }

    public record ShortenResult(String code, String longUrl) {
    }
}