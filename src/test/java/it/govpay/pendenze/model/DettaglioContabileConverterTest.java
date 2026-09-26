package it.govpay.pendenze.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DettaglioContabileConverter} non ha stato ne' dipendenze da Spring/JPA: verifica il
 * round trip di codifica/decodifica JSON per ciascuna variante, incluso il discriminatore
 * polimorfico {@code tipo}.
 */
class DettaglioContabileConverterTest {

    private final DettaglioContabileConverter converter = new DettaglioContabileConverter();

    @Test
    @DisplayName("lista null o vuota si codifica come colonna null")
    void listaAssente() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
    }

    @Test
    @DisplayName("colonna null o vuota si decodifica come lista vuota, non null")
    void colonnaAssente() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }

    @Test
    @DisplayName("round trip di ciascuna variante scrivibile, incluso il discriminatore")
    void roundTripOgniVariante() {
        List<DettaglioContabile> originale = List.of(
                new DettaglioContabile.CorrispettivoDl118("2026", "UFF1", "CAP1", null, "ART1", null,
                        new BigDecimal("10.00")),
                new DettaglioContabile.IncassoTipico("2026", "UFF1", "DIRITTI", "SI", null, new BigDecimal("5.00")),
                new DettaglioContabile.Civilistico("2026", "UFF1", "14.01.03", null, null, new BigDecimal("3.00")),
                new DettaglioContabile.SpeseNotifica(new BigDecimal("1.20")));

        String json = converter.convertToDatabaseColumn(originale);
        List<DettaglioContabile> decodificato = converter.convertToEntityAttribute(json);

        assertThat(decodificato).isEqualTo(originale);
    }

    @Test
    @DisplayName("round trip della variante di sola lettura UNKNOWN_ENTRIES")
    void roundTripSconosciuto() {
        List<DettaglioContabile> originale = List.of(
                new DettaglioContabile.Sconosciuto(List.of(new DettaglioContabile.Sconosciuto.Voce("K", "V"))));

        String json = converter.convertToDatabaseColumn(originale);
        List<DettaglioContabile> decodificato = converter.convertToEntityAttribute(json);

        assertThat(decodificato).isEqualTo(originale);
    }
}
