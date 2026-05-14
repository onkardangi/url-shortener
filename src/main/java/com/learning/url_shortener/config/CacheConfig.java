package com.learning.url_shortener.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * @author onkardangi
 * @date 5/14/26
 * @time 11:22
 */
@Configuration
@EnableCaching
public class CacheConfig {
    // @EnableCaching activates Spring's cache proxy mechanism.
    // Without this annotation, @Cacheable on your service methods
    // does absolutely nothing — the annotations are ignored.
    //
    // The actual cache behaviour (Redis, TTL, serialization) is
    // driven by application.yml + the spring-boot-starter-data-redis
    // autoconfiguration. Nothing else needed here for now.

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                // Use plain strings for both keys and values
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
