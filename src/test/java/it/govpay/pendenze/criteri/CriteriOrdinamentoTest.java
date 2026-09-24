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
    @DisplayName("+campo produce ordinamento ascendente sul percorso JPA mappato")
    void ordineAscendente() {
        Sort sort = CriteriOrdinamento.parse("+dataCreazione", CAMPI);

        assertThat(sort.getOrderFor("dataCreazione").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("-campo produce ordinamento discendente")
    void ordineDiscendente() {
        Sort sort = CriteriOrdinamento.parse("-dataCreazione", CAMPI);

        assertThat(sort.getOrderFor("dataCreazione").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("un campo senza segno e' ascendente per default")
    void senzaSegnoEAscendente() {
        Sort sort = CriteriOrdinamento.parse("dataCreazione", CAMPI);

        assertThat(sort.getOrderFor("dataCreazione").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("piu' campi separati da virgola producono un ordinamento composito, nel percorso JPA mappato")
    void piuCampi() {
        Sort sort = CriteriOrdinamento.parse("+dataCreazione,-importo", CAMPI);

        assertThat(sort.getOrderFor("dataCreazione").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(sort.getOrderFor("opzionePagamento.importo").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("un campo non nella mappa consentita e' rifiutato esplicitamente")
    void campoNonAmmesso() {
        assertThatThrownBy(() -> CriteriOrdinamento.parse("+campoSegreto", CAMPI))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("campoSegreto");
    }
}
