package it.govpay.pendenze.criteri;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import it.govpay.pendenze.exception.ValidazioneNonSuperataException;

/**
 * Codifica/decodifica del cursore opaco per la paginazione keyset (stesso schema di
 * govpay-console-api: base64 URL-safe, senza padding, di {@code "<timestamp ISO_8601>|<id>"}
 * — es. {@code "2026-06-12T10:15:30.123Z|42"}).
 *
 * <p><b>Non firmato</b>: il cursore e' un hint di paginazione, non un token di sicurezza —
 * la query resta comunque scoped dai filtri di autorizzazione/appartenenza della richiesta
 * (es. {@code idApplicazione}), quindi una manomissione al piu' fa saltare
 * ordinamento/pagine, non permette accessi non autorizzati.</p>
 */
public final class CursorCodec {

    private static final String SEPARATORE = "|";

    private CursorCodec() {
    }

    public static String encode(OffsetDateTime dataCreazione, long id) {
        String raw = dataCreazione.toString() + SEPARATORE + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursore decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new ValidazioneNonSuperataException(
                    "cursore vuoto: rispedire il valore ricevuto in 'nextCursor'");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new ValidazioneNonSuperataException("cursore malformato: codifica base64 non valida");
        }
        String raw = new String(decoded, StandardCharsets.UTF_8);
        int separatore = raw.lastIndexOf(SEPARATORE);
        if (separatore <= 0 || separatore == raw.length() - 1) {
            throw new ValidazioneNonSuperataException(
                    "cursore malformato: formato \"<timestamp>|<id>\" atteso");
        }
        OffsetDateTime dataCreazione;
        try {
            dataCreazione = OffsetDateTime.parse(raw.substring(0, separatore));
        } catch (DateTimeParseException e) {
            throw new ValidazioneNonSuperataException("cursore malformato: timestamp non interpretabile");
        }
        long id;
        try {
            id = Long.parseLong(raw.substring(separatore + 1));
        } catch (NumberFormatException e) {
            throw new ValidazioneNonSuperataException("cursore malformato: id non numerico");
        }
        return new Cursore(dataCreazione, id);
    }

    public record Cursore(OffsetDateTime dataCreazione, long id) {
    }
}
