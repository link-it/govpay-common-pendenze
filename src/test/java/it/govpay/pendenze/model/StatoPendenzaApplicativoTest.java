package it.govpay.pendenze.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatoPendenzaApplicativoTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 7, 29, 10, 0, 0, 0, ZoneOffset.ofHours(2));

    @Test
    @DisplayName("mappature dirette dallo stato persistito")
    void mappatureDirette() {
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.ESEGUITO, null, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.PAGATA);
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.ESEGUITO_ALTRO_CANALE, null, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.PAGATA);
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.ESEGUITO_SENZA_RPT, null, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.PAGATA);
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.PARZIALMENTE_ESEGUITO, null, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.PAGATA_PARZIALE);
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.INCASSATO, null, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.RICONCILIATA);
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.ANNULLATO, null, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.ANNULLATA);
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.ANOMALO, null, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.ANOMALA);
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.NON_ESEGUITO, null, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.NON_PAGATA);
    }

    @Test
    @DisplayName("non eseguita con scadenza passata: SCADUTA")
    void scadenzaPassata() {
        assertThat(StatoPendenzaApplicativo.da(
                StatoVersamento.NON_ESEGUITO, ADESSO.minusDays(1), ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.SCADUTA);
    }

    @Test
    @DisplayName("non eseguita con scadenza futura: NON_PAGATA")
    void scadenzaFutura() {
        assertThat(StatoPendenzaApplicativo.da(
                StatoVersamento.NON_ESEGUITO, ADESSO.plusDays(1), ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.NON_PAGATA);
    }

    @Test
    @DisplayName("scadenza esattamente al riferimento: non ancora scaduta")
    void scadenzaAlRiferimento() {
        assertThat(StatoPendenzaApplicativo.da(StatoVersamento.NON_ESEGUITO, ADESSO, ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.NON_PAGATA);
    }

    @Test
    @DisplayName("la scadenza non altera gli stati diversi da NON_ESEGUITO")
    void scadenzaIrrilevanteSeGiaPagata() {
        assertThat(StatoPendenzaApplicativo.da(
                StatoVersamento.ESEGUITO, ADESSO.minusYears(1), ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.PAGATA);
        assertThat(StatoPendenzaApplicativo.da(
                StatoVersamento.ANNULLATO, ADESSO.minusYears(1), ADESSO))
                .isEqualTo(StatoPendenzaApplicativo.ANNULLATA);
    }
}
