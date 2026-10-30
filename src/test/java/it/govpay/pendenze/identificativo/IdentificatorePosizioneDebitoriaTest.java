package it.govpay.pendenze.identificativo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.govpay.pendenze.identificativo.IdentificatorePosizioneDebitoria.ComponentiIdentificativo;

class IdentificatorePosizioneDebitoriaTest {

    @Test
    void componeConcatenandoSenzaSeparatore() {
        String risultato = IdentificatorePosizioneDebitoria.comporre("A2A-12345", "01234567890", "IMU2026-000017");
        assertThat(risultato).isEqualTo("A2A-1234501234567890IMU2026-000017");
    }

    @Test
    void componeRifiutaIdDominioConLunghezzaSbagliata() {
        assertThatThrownBy(() -> IdentificatorePosizioneDebitoria.comporre("A2A-12345", "123", "IMU2026-000017"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> IdentificatorePosizioneDebitoria.comporre("A2A-12345", "012345678901", "IMU2026-000017"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scomponiRoundTripConComporre() {
        String idA2A = "A2A-12345";
        String idDominio = "01234567890";
        String codDocumento = "IMU2026-000017";
        String idPosizioneDebitoria = IdentificatorePosizioneDebitoria.comporre(idA2A, idDominio, codDocumento);

        Optional<ComponentiIdentificativo> risultato = IdentificatorePosizioneDebitoria.scomponi(idA2A,
                idPosizioneDebitoria);

        assertThat(risultato).isPresent();
        assertThat(risultato.get().idDominio()).isEqualTo(idDominio);
        assertThat(risultato.get().codDocumento()).isEqualTo(codDocumento);
    }

    @Test
    void scomponiFunzionaAncheConCodDocumentoCheContieneCaratteriUgualiAIdA2A() {
        // codDocumento puo' legittimamente ripetere/contenere lo stesso alfabeto di idA2A: la
        // decomposizione non deve confondersi, perche' non "cerca" idA2A nella stringa, lo
        // strippa per lunghezza nota a priori.
        String idA2A = "ABC";
        String idDominio = "01234567890";
        String codDocumento = "ABC-ABC-ABC";
        String idPosizioneDebitoria = IdentificatorePosizioneDebitoria.comporre(idA2A, idDominio, codDocumento);

        Optional<ComponentiIdentificativo> risultato = IdentificatorePosizioneDebitoria.scomponi(idA2A,
                idPosizioneDebitoria);

        assertThat(risultato).contains(new ComponentiIdentificativo(idDominio, codDocumento));
    }

    @Test
    void scomponiRestituisceVuotoSePrefissoIdA2ANonCorrisponde() {
        String idPosizioneDebitoria = IdentificatorePosizioneDebitoria.comporre("A2A-12345", "01234567890",
                "IMU2026-000017");

        assertThat(IdentificatorePosizioneDebitoria.scomponi("ALTRO-A2A", idPosizioneDebitoria)).isEmpty();
    }

    @Test
    void scomponiRestituisceVuotoSeTroppoCortaPerContenereIdDominio() {
        String idA2A = "A2A-12345";
        // dopo aver strippato idA2A restano solo 5 caratteri, meno degli 11 richiesti per idDominio
        assertThat(IdentificatorePosizioneDebitoria.scomponi(idA2A, idA2A + "12345")).isEmpty();
    }

    @Test
    void scomponiRestituisceVuotoSeCodDocumentoRisulterebbeVuoto() {
        String idA2A = "A2A-12345";
        String idDominio = "01234567890";
        // esattamente idA2A+idDominio, senza nemmeno un carattere di codDocumento
        assertThat(IdentificatorePosizioneDebitoria.scomponi(idA2A, idA2A + idDominio)).isEmpty();
    }

    @Test
    void scomponiRestituisceVuotoConIdA2ANullo() {
        assertThat(IdentificatorePosizioneDebitoria.scomponi(null, "qualunque")).isEmpty();
    }

    @Test
    void scomponiRestituisceVuotoConIdPosizioneDebitoriaNullo() {
        assertThat(IdentificatorePosizioneDebitoria.scomponi("A2A-12345", null)).isEmpty();
    }
}
