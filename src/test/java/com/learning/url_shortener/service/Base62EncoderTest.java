package com.learning.url_shortener.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class Base62EncoderTest {

    private final Base62Encoder encoder = new Base62Encoder();

    @Test
    void encode_zero_returnsZeroChar() {
        assertThat(encoder.encode(0)).isEqualTo("0");
    }

    @ParameterizedTest
    @CsvSource({
        "1,  1",
        "61, Z",
        "62, 10",
        "3844, 100"
    })
    void encode_knownValues(long id, String expected) {
        assertThat(encoder.encode(id)).isEqualTo(expected.trim());
    }

    @Test
    void encode_negative_throwsIllegalArgument() {
        assertThatThrownBy(() -> encoder.encode(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decode_invalidCharacter_throwsIllegalArgument() {
        assertThatThrownBy(() -> encoder.decode("!@#"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeDecodeRoundtrip() {
        for (long id : new long[]{1, 62, 1000, 99999, 999999}) {
            assertThat(encoder.decode(encoder.encode(id))).isEqualTo(id);
        }
    }
}
