package com.learning.url_shortener.service;

import org.springframework.stereotype.Component;

@Component
public class Base62Encoder {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length(); // 62

    public String encode(long id) {
        if (id < 0) throw new IllegalArgumentException("ID must be non-negative");
        if (id == 0) return String.valueOf(ALPHABET.charAt(0));

        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(ALPHABET.charAt((int) (id % BASE)));
            id /= BASE;
        }
        return sb.reverse().toString();
    }

    public long decode(String code) {
        long id = 0;
        for (char c : code.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value == -1) {
                throw new IllegalArgumentException("Invalid character in code: " + c);
            }
            id = id * BASE + value;
        }
        return id;
    }
}