package it.govpay.pendenze.entity.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImportoConverterTest {

    private final ImportoConverter converter = new ImportoConverter();

    @ParameterizedTest
    @ValueSource(strings = {"10.20", "0.01", "1234567.89", "0.00", "999999999999.99"})
    @DisplayName("il giro attraverso la colonna restituisce lo stesso importo")
    void roundTripConservaLImporto(String valore) {
        BigDecimal importo = new BigDecimal(valore);

        Double inColonna = converter.convertToDatabaseColumn(importo);
        BigDecimal riletto = converter.convertToEntityAttribute(inColonna);

        assertThat(riletto).isEqualByComparingTo(importo);
        assertThat(riletto.scale()).isEqualTo(ImportoConverter.SCALA);
    }

    @Test
    @DisplayName("i valori assenti restano assenti in entrambe le direzioni")
    void gestisceIValoriAssenti() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    @DisplayName("un importo con piu' di due decimali viene arrotondato HALF_UP")
    void arrotondaAHalfUp() {
        assertThat(converter.convertToDatabaseColumn(new BigDecimal("9.995"))).isEqualTo(10.00);
        assertThat(converter.convertToDatabaseColumn(new BigDecimal("9.994"))).isEqualTo(9.99);
    }

    @Test
    @DisplayName("in lettura si usa BigDecimal.valueOf e non il costruttore da double")
    void nonUsaIlCostruttoreDaDouble() {
        // new BigDecimal(0.1) vale 0.1000000000000000055511151231257827...: se il
        // converter lo usasse, un importo di 0.10 non sarebbe piu' confrontabile.
        assertThat(new BigDecimal(0.1)).isNotEqualByComparingTo(new BigDecimal("0.1"));

        assertThat(converter.convertToEntityAttribute(0.1d))
                .isEqualByComparingTo(new BigDecimal("0.10"));
    }

    @Test
    @DisplayName("la somma delle voci resta confrontabile con il totale")
    void sommaDelleVociConfrontabileConIlTotale() {
        // E' il confronto che fa la validazione semantica del caricamento: con i double
        // 0.10 + 0.20 non fa 0.30.
        BigDecimal prima = converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(new BigDecimal("0.10")));
        BigDecimal seconda = converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(new BigDecimal("0.20")));
        BigDecimal totale = converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(new BigDecimal("0.30")));

        assertThat(prima.add(seconda)).isEqualByComparingTo(totale);
    }
}
