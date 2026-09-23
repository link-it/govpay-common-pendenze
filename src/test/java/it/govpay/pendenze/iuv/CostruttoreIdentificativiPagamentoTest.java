package it.govpay.pendenze.iuv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.spi.IdentificativiPagamento;

/**
 * {@link CostruttoreIdentificativiPagamento} non ha stato ne' dipendenze: questi test
 * verificano l'algoritmo contro valori attesi calcolati indipendentemente (Python, stessa
 * formula di {@code IuvBD.generaIuv}/{@code getCheckDigit93} del legacy), non solo la
 * coerenza interna fra generazione e conversione.
 */
class CostruttoreIdentificativiPagamentoTest {

    @Test
    @DisplayName("AuxDigit 0: reference=applicationCode+progressivo, check digit mod-93 con application code")
    void generaAuxDigit0() {
        IdentificativiPagamento identificativi = CostruttoreIdentificativiPagamento.genera(0, "", null, 5, 1);

        assertThat(identificativi.iuv()).isEqualTo("000000000000115");
        assertThat(identificativi.numeroAvviso()).isEqualTo("005000000000000115");
    }

    @Test
    @DisplayName("AuxDigit 1: reference di 15 cifre, check digit mod-93 semplice")
    void generaAuxDigit1() {
        IdentificativiPagamento identificativi = CostruttoreIdentificativiPagamento.genera(1, "", null, null, 1);

        assertThat(identificativi.iuv()).isEqualTo("00000000000000102");
        assertThat(identificativi.numeroAvviso()).isEqualTo("100000000000000102");
    }

    @Test
    @DisplayName("AuxDigit 2 con prefisso numerico: il prefisso occupa le prime cifre della reference")
    void generaAuxDigit2ConPrefisso() {
        IdentificativiPagamento identificativi = CostruttoreIdentificativiPagamento.genera(2, "12", null, null, 42);

        assertThat(identificativi.iuv()).isEqualTo("12000000000004259");
        assertThat(identificativi.numeroAvviso()).isEqualTo("212000000000004259");
    }

    @Test
    @DisplayName("AuxDigit 3: iuv = codice di segregazione + reference + check digit")
    void generaAuxDigit3() {
        IdentificativiPagamento identificativi = CostruttoreIdentificativiPagamento.genera(3, "", 12, null, 7);

        assertThat(identificativi.iuv()).isEqualTo("12000000000000725");
        assertThat(identificativi.numeroAvviso()).isEqualTo("312000000000000725");
    }

    @Test
    @DisplayName("AuxDigit 0 senza application code e' un errore di configurazione, non un IUV malformato")
    void generaAuxDigit0SenzaApplicationCode() {
        assertThatThrownBy(() -> CostruttoreIdentificativiPagamento.genera(0, "", null, null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application code");
    }

    @Test
    @DisplayName("AuxDigit 3 senza codice di segregazione e' un errore di configurazione")
    void generaAuxDigit3SenzaSegregationCode() {
        assertThatThrownBy(() -> CostruttoreIdentificativiPagamento.genera(3, "", null, null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("segregazione");
    }

    @Test
    @DisplayName("un prefisso quasi al limite non deve produrre un numero avviso piu' lungo di 18 cifre "
            + "(il controllo di lunghezza deve usare il limite del singolo AuxDigit, non sempre 15)")
    void generaRifiutaPrefissoCheSforaLaLunghezza() {
        // AuxDigit 3/0 hanno reference di 13 cifre, non 15: un controllo che verifica sempre
        // "> 15" lascia passare una reference di 14 cifre (prefisso 12 cifre + progressivo di
        // 2 cifre), producendo un IUV di 18 cifre e un numeroAvviso di 19 invece di 18.
        assertThatThrownBy(() -> CostruttoreIdentificativiPagamento.genera(3, "123456789012", 12, null, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("123456789012");
    }

    @Test
    @DisplayName("converti ricava lo stesso iuv generato, per ciascun AuxDigit (round trip)")
    void convertiRoundTrip() {
        IdentificativiPagamento aux0 = CostruttoreIdentificativiPagamento.genera(0, "", null, 5, 1);
        assertThat(CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(aux0.numeroAvviso(), 0, null, 5))
                .isEqualTo(aux0.iuv());

        IdentificativiPagamento aux1 = CostruttoreIdentificativiPagamento.genera(1, "", null, null, 1);
        assertThat(CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(aux1.numeroAvviso(), 1, null, null))
                .isEqualTo(aux1.iuv());

        IdentificativiPagamento aux3 = CostruttoreIdentificativiPagamento.genera(3, "", 12, null, 7);
        assertThat(CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(aux3.numeroAvviso(), 3, 12, null))
                .isEqualTo(aux3.iuv());
    }

    @Test
    @DisplayName("converti non consuma un progressivo: e' pura decodifica, chiamabile ripetutamente sullo stesso NAV")
    void convertiNonHaEffettiCollaterali() {
        String numeroAvviso = CostruttoreIdentificativiPagamento.genera(1, "", null, null, 1).numeroAvviso();

        String prima = CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(numeroAvviso, 1, null, null);
        String seconda = CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(numeroAvviso, 1, null, null);

        assertThat(prima).isEqualTo(seconda);
    }

    @Test
    @DisplayName("converti rifiuta un numeroAvviso con AuxDigit diverso da quello configurato sul dominio")
    void convertiRifiutaAuxDigitDiverso() {
        String numeroAvviso = CostruttoreIdentificativiPagamento.genera(1, "", null, null, 1).numeroAvviso();

        assertThatThrownBy(() -> CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(numeroAvviso, 2, null, null))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("AuxDigit");
    }

    @Test
    @DisplayName("converti rifiuta un numeroAvviso con check digit alterato")
    void convertiRifiutaCheckDigitAlterato() {
        String numeroAvviso = CostruttoreIdentificativiPagamento.genera(1, "", null, null, 1).numeroAvviso();
        String alterato = numeroAvviso.substring(0, numeroAvviso.length() - 1)
                + (numeroAvviso.charAt(numeroAvviso.length() - 1) == '0' ? '1' : '0');

        assertThatThrownBy(() -> CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(alterato, 1, null, null))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("check digit");
    }

    @Test
    @DisplayName("converti rifiuta un formato non a 18 cifre numeriche")
    void convertiRifiutaFormatoNonValido() {
        assertThatThrownBy(() -> CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso("123", 1, null, null))
                .isInstanceOf(ValidazioneNonSuperataException.class);

        assertThatThrownBy(() -> CostruttoreIdentificativiPagamento.convertiDaNumeroAvviso(null, 1, null, null))
                .isInstanceOf(ValidazioneNonSuperataException.class);
    }
}
