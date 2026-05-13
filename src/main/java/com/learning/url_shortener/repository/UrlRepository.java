package com.learning.url_shortener.repository;

import com.learning.url_shortener.entity.Url;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UrlRepository extends JpaRepository<Url, Long> {
    // JpaRepository gives us save(), findById(), etc. for free.
    // We'll add custom queries later when we need them.
}
