package it.govpay.pendenze.criteri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.pendenze.exception.ValidazioneNonSuperataException;

class CursorCodecTest {

    @Test
    @DisplayName("encode/decode fanno round-trip su dataCreazione e id")
    void roundTrip() {
        OffsetDateTime dataCreazione = OffsetDateTime.parse("2026-06-12T10:15:30.123Z");

        String encoded = CursorCodec.encode(dataCreazione, 42L);
        CursorCodec.Cursore decoded = CursorCodec.decode(encoded);

        assertThat(decoded.dataCreazione()).isEqualTo(dataCreazione);
        assertThat(decoded.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("il cursore codificato e' un base64 URL-safe opaco, non il testo in chiaro")
    void encodedEOpaco() {
        String encoded = CursorCodec.encode(OffsetDateTime.parse("2026-06-12T10:15:30.123Z"), 42L);

        assertThat(encoded).doesNotContain("|", "2026", "42");
    }

    @Test
    @DisplayName("null o vuoto sono rifiutati esplicitamente")
    void vuotoRifiutato() {
        assertThatThrownBy(() -> CursorCodec.decode(null))
                .isInstanceOf(ValidazioneNonSuperataException.class);
        assertThatThrownBy(() -> CursorCodec.decode(""))
                .isInstanceOf(ValidazioneNonSuperataException.class);
    }

    @Test
    @DisplayName("una codifica base64 non valida e' rifiutata esplicitamente")
    void base64NonValidoRifiutato() {
        assertThatThrownBy(() -> CursorCodec.decode("non e' base64 valido!!"))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("base64");
    }

    @Test
    @DisplayName("un contenuto decodificato senza separatore e' rifiutato esplicitamente")
    void senzaSeparatoreRifiutato() {
        String senzaSeparatore = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("nessunseparatore".getBytes());

        assertThatThrownBy(() -> CursorCodec.decode(senzaSeparatore))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("formato");
    }

    @Test
    @DisplayName("un timestamp non interpretabile e' rifiutato esplicitamente")
    void timestampNonInterpretabileRifiutato() {
        String malformato = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("non-e-un-timestamp|42".getBytes());

        assertThatThrownBy(() -> CursorCodec.decode(malformato))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    @DisplayName("un id non numerico e' rifiutato esplicitamente")
    void idNonNumericoRifiutato() {
        String malformato = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026-06-12T10:15:30.123Z|non-numerico".getBytes());

        assertThatThrownBy(() -> CursorCodec.decode(malformato))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("numerico");
    }
}
