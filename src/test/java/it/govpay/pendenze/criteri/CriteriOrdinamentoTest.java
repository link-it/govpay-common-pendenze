package it.govpay.pendenze.criteri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import it.govpay.pendenze.exception.ValidazioneNonSuperataException;

class CriteriOrdinamentoTest {

    private static final Map<String, String> CAMPI = Map.of(
            "dataCreazione", "dataCreazione",
            "importo", "opzionePagamento.importo");

    @Test
    @DisplayName("null e vuoto restituiscono nessun ordinamento")
    void sortAssente() {
        assertThat(CriteriOrdinamento.parse(null, CAMPI).isUnsorted()).isTrue();
        assertThat(CriteriOrdinamento.parse("  ", CAMPI).isUnsorted()).isTrue();
    }

    @Test
    @DisplayName("campo:asc produce ordinamento ascendente sul percorso JPA mappato")
    void ordineAscendente() {
        Sort sort = CriteriOrdinamento.parse("dataCreazione:asc", CAMPI);

        assertThat(sort.getOrderFor("dataCreazione").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("campo:desc produce ordinamento discendente")
    void ordineDiscendente() {
        Sort sort = CriteriOrdinamento.parse("dataCreazione:desc", CAMPI);

        assertThat(sort.getOrderFor("dataCreazione").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("la direzione non e' case-sensitive")
    void direzioneCaseInsensitive() {
        Sort sort = CriteriOrdinamento.parse("dataCreazione:DESC", CAMPI);

        assertThat(sort.getOrderFor("dataCreazione").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("piu' campi separati da virgola producono un ordinamento composito, nel percorso JPA mappato")
    void piuCampi() {
        Sort sort = CriteriOrdinamento.parse("dataCreazione:asc,importo:desc", CAMPI);

        assertThat(sort.getOrderFor("dataCreazione").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(sort.getOrderFor("opzionePagamento.importo").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("un campo senza ':direzione' e' rifiutato come malformato")
    void senzaDirezioneEMalformato() {
        assertThatThrownBy(() -> CriteriOrdinamento.parse("dataCreazione", CAMPI))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("malformato");
    }

    @Test
    @DisplayName("una direzione diversa da asc/desc e' rifiutata esplicitamente")
    void direzioneNonRiconosciuta() {
        assertThatThrownBy(() -> CriteriOrdinamento.parse("dataCreazione:up", CAMPI))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("up");
    }

    @Test
    @DisplayName("un campo non nella mappa consentita e' rifiutato esplicitamente")
    void campoNonAmmesso() {
        assertThatThrownBy(() -> CriteriOrdinamento.parse("campoSegreto:asc", CAMPI))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("campoSegreto");
    }
}
