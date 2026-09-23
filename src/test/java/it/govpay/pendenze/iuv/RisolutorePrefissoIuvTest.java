package it.govpay.pendenze.iuv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RisolutorePrefissoIuv} non ha stato ne' dipendenze: verifica la sostituzione dei
 * placeholder {@code %(chiave)} del prefisso IUV di dominio (porting di
 * {@code CustomIuv.buildPrefix}), inclusi i casi segnalati dal lead — prefisso letto
 * letteralmente senza risolvere i placeholder dinamici.
 */
class RisolutorePrefissoIuvTest {

    @Test
    @DisplayName("null e vuoto restano vuoti, senza placeholder da risolvere")
    void prefissoAssente() {
        assertThat(RisolutorePrefissoIuv.risolvi(null, Map.of())).isEmpty();
        assertThat(RisolutorePrefissoIuv.risolvi("", Map.of())).isEmpty();
    }

    @Test
    @DisplayName("un prefisso senza placeholder resta invariato")
    void prefissoLetterale() {
        assertThat(RisolutorePrefissoIuv.risolvi("12345", Map.of())).isEqualTo("12345");
    }

    @Test
    @DisplayName("sostituisce %(Y) e %(y) con i valori forniti")
    void sostituisceAnno() {
        Map<String, String> valori = new HashMap<>();
        valori.put("Y", "2026");
        valori.put("y", "26");

        assertThat(RisolutorePrefissoIuv.risolvi("%(Y)001", valori)).isEqualTo("2026001");
        assertThat(RisolutorePrefissoIuv.risolvi("%(y)001", valori)).isEqualTo("26001");
    }

    @Test
    @DisplayName("sostituisce piu' placeholder diversi nello stesso prefisso")
    void sostituiscePiuPlaceholder() {
        Map<String, String> valori = Map.of("Y", "2026", "a", "007");

        assertThat(RisolutorePrefissoIuv.risolvi("%(a)%(Y)", valori)).isEqualTo("0072026");
    }

    @Test
    @DisplayName("un placeholder senza valore noto solleva un errore esplicito, non lo lascia nel testo")
    void placeholderNonRisolvibile() {
        assertThatThrownBy(() -> RisolutorePrefissoIuv.risolvi("%(p)001", Map.of("Y", "2026")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("%(p)");
    }
}
