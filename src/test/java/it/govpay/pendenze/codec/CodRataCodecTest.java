package it.govpay.pendenze.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import it.govpay.pendenze.model.RataOSoglia;
import it.govpay.pendenze.model.TipoSoglia;

class CodRataCodecTest {

    @Test
    @DisplayName("numero di rata")
    void numeroRata() {
        assertThat(CodRataCodec.decodifica("3")).contains(new RataOSoglia.NumeroRata(3));
        assertThat(CodRataCodec.codifica(new RataOSoglia.NumeroRata(3))).isEqualTo("3");
    }

    @Test
    @DisplayName("soglie con giorni: ENTRO e OLTRE")
    void sogliaConGiorni() {
        assertThat(CodRataCodec.decodifica("ENTRO15"))
                .contains(new RataOSoglia.Soglia(TipoSoglia.ENTRO, 15));
        assertThat(CodRataCodec.decodifica("OLTRE30"))
                .contains(new RataOSoglia.Soglia(TipoSoglia.OLTRE, 30));

        assertThat(CodRataCodec.codifica(new RataOSoglia.Soglia(TipoSoglia.ENTRO, 15)))
                .isEqualTo("ENTRO15");
        assertThat(CodRataCodec.codifica(new RataOSoglia.Soglia(TipoSoglia.OLTRE, 30)))
                .isEqualTo("OLTRE30");
    }

    @Test
    @DisplayName("soglie senza giorni: RIDOTTO e SCONTATO")
    void sogliaSenzaGiorni() {
        assertThat(CodRataCodec.decodifica("RIDOTTO"))
                .contains(new RataOSoglia.Soglia(TipoSoglia.RIDOTTO, null));
        assertThat(CodRataCodec.decodifica("SCONTATO"))
                .contains(new RataOSoglia.Soglia(TipoSoglia.SCONTATO, null));

        assertThat(CodRataCodec.codifica(new RataOSoglia.Soglia(TipoSoglia.RIDOTTO, null)))
                .isEqualTo("RIDOTTO");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("colonna vuota: nessun valore")
    void colonnaVuota(String valore) {
        assertThat(CodRataCodec.decodifica(valore)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PIPPO", "ENTRO", "ENTROxx", "RIDOTTO10", "3.5", "-1"})
    @DisplayName("valori non conformi: ignorati senza eccezioni")
    void valoriNonConformi(String valore) {
        assertThat(CodRataCodec.decodifica(valore)).isEmpty();
    }

    @Test
    @DisplayName("la codifica rifiuta le combinazioni non ammesse dal dominio")
    void combinazioniNonAmmesse() {
        assertThatThrownBy(() -> new RataOSoglia.Soglia(TipoSoglia.ENTRO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("richiede un numero di giorni");

        assertThatThrownBy(() -> new RataOSoglia.Soglia(TipoSoglia.RIDOTTO, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non ammette");

        assertThatThrownBy(() -> new RataOSoglia.NumeroRata(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ogni valore ammesso dal dominio sta nella colonna VARCHAR(35)")
    void ogniValoreStaNellaColonna() {
        for (TipoSoglia tipo : TipoSoglia.values()) {
            RataOSoglia soglia = new RataOSoglia.Soglia(
                    tipo, tipo.richiedeGiorni() ? Integer.MAX_VALUE : null);
            assertThat(CodRataCodec.codifica(soglia).length())
                    .isLessThanOrEqualTo(CodRataCodec.LUNGHEZZA_MASSIMA);
        }
        assertThat(CodRataCodec.codifica(new RataOSoglia.NumeroRata(Integer.MAX_VALUE)).length())
                .isLessThanOrEqualTo(CodRataCodec.LUNGHEZZA_MASSIMA);
    }

    @Test
    @DisplayName("valore assente: nessuna codifica")
    void codificaAssente() {
        assertThat(CodRataCodec.codifica(null)).isNull();
    }
}
