package com.learning.url_shortener.service;

import com.learning.url_shortener.entity.Url;
import com.learning.url_shortener.repository.UrlRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UrlService {

    // Cache name — used as the Redis key prefix.
    // Keys will look like: urls::1, urls::2, urls::abc
    private final String CACHE_NAME = "urls";

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

    @Cacheable(value = CACHE_NAME, key = "#code")
    @Transactional(readOnly = true)
    public String resolveUrl(String code) {
        long id;
        try {
            id = encoder.decode(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return repository.findById(id)
                .map(Url::getLongUrl)
                .orElse(null);
    }

    @CacheEvict(value = CACHE_NAME, key = "#code")
    public void evict(String code) {
        // Called explicitly if we ever need to invalidate a cache entry.
        // Not used yet — will be needed when we add URL update/delete
    }

    public record ShortenResult(String code, String longUrl) {
    }
}