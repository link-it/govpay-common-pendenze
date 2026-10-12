package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.common.entity.TipoContabilita;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;

/**
 * {@code getTipoRiferimento()}/{@code getTassonomia()}/{@code setTassonomia(String)} non
 * hanno stato ne' dipendenze da Spring/JPA (derivati, non colonne — vedi Javadoc di classe di
 * {@link VocePendenza}): verificati come logica pura, senza bisogno di un contesto Spring.
 */
class VocePendenzaTest {

    @Test
    @DisplayName("getTipoRiferimento() e' BOLLO se tipoBollo e' valorizzato, a prescindere da cos'altro lo e'")
    void tipoRiferimentoBollo() {
        VocePendenza voce = new VocePendenza();
        voce.setTipoBollo("01");

        assertThat(voce.getTipoRiferimento()).isEqualTo(TipoRiferimentoVocePendenza.BOLLO);
    }

    @Test
    @DisplayName("getTipoRiferimento() e' RIFERIMENTO_ENTRATA se idTributo e' valorizzato (e tipoBollo no)")
    void tipoRiferimentoRiferimentoEntrata() {
        VocePendenza voce = new VocePendenza();
        voce.setIdTributo(42L);

        assertThat(voce.getTipoRiferimento()).isEqualTo(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
    }

    @Test
    @DisplayName("getTipoRiferimento() e' ENTRATA se idIbanAccredito e' valorizzato (e tipoBollo/idTributo no)")
    void tipoRiferimentoEntrata() {
        VocePendenza voce = new VocePendenza();
        voce.setIdIbanAccredito(7L);

        assertThat(voce.getTipoRiferimento()).isEqualTo(TipoRiferimentoVocePendenza.ENTRATA);
    }

    @Test
    @DisplayName("getTipoRiferimento() e' null se nessuna delle tre colonne discriminanti e' valorizzata")
    void tipoRiferimentoAssente() {
        assertThat(new VocePendenza().getTipoRiferimento()).isNull();
    }

    @Test
    @DisplayName("getTassonomia() concatena tipoContabilita.getCodifica() + \"/\" + codiceContabilita")
    void tassonomiaConcatenaInLettura() {
        VocePendenza voce = new VocePendenza();
        voce.setTipoContabilita(TipoContabilita.SIOPE);
        voce.setCodiceContabilita("1234");

        assertThat(voce.getTassonomia()).isEqualTo("2/1234");
    }

    @Test
    @DisplayName("getTassonomia() e' null se tipoContabilita o codiceContabilita non sono entrambi valorizzati")
    void tassonomiaAssenteSeIncompleta() {
        VocePendenza soloTipo = new VocePendenza();
        soloTipo.setTipoContabilita(TipoContabilita.SIOPE);
        assertThat(soloTipo.getTassonomia()).isNull();

        VocePendenza soloCodice = new VocePendenza();
        soloCodice.setCodiceContabilita("1234");
        assertThat(soloCodice.getTassonomia()).isNull();
    }

    @Test
    @DisplayName("setTassonomia(String) spacchetta su tipoContabilita/codiceContabilita, round trip con getTassonomia()")
    void setTassonomiaRoundTrip() {
        VocePendenza voce = new VocePendenza();
        voce.setTassonomia("2/1234");

        assertThat(voce.getTipoContabilita()).isEqualTo(TipoContabilita.SIOPE);
        assertThat(voce.getCodiceContabilita()).isEqualTo("1234");
        assertThat(voce.getTassonomia()).isEqualTo("2/1234");
    }

    @Test
    @DisplayName("setTassonomia(String) splitta sulla PRIMA occorrenza di '/': codiceContabilita puo' "
            + "contenere a sua volta '/' (chiarimento del lead, 2026-09-28) senza essere troncato")
    void setTassonomiaSplittaSullaPrimaOccorrenza() {
        VocePendenza voce = new VocePendenza();
        voce.setTassonomia("9/CONTO/SOTTOCONTO/42");

        assertThat(voce.getTipoContabilita()).isEqualTo(TipoContabilita.ALTRO);
        assertThat(voce.getCodiceContabilita()).isEqualTo("CONTO/SOTTOCONTO/42");
    }

    @Test
    @DisplayName("setTassonomia(null) azzera sia tipoContabilita sia codiceContabilita")
    void setTassonomiaNullAzzeraEntrambi() {
        VocePendenza voce = new VocePendenza();
        voce.setTassonomia("2/1234");

        voce.setTassonomia(null);

        assertThat(voce.getTipoContabilita()).isNull();
        assertThat(voce.getCodiceContabilita()).isNull();
        assertThat(voce.getTassonomia()).isNull();
    }

    @Test
    @DisplayName("setTassonomia(String) rifiuta un valore senza '/'")
    void setTassonomiaRifiutaSenzaSeparatore() {
        VocePendenza voce = new VocePendenza();

        assertThatThrownBy(() -> voce.setTassonomia("senza-separatore"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("senza-separatore");
    }
}
