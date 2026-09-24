package it.govpay.pendenze.validazione;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * {@link ValidatorePosizioneDebitoria} non ha stato ne' dipendenze da Spring/JPA: questi
 * test costruiscono gli aggregati a mano, senza {@code @DataJpaTest} ne' database.
 */
class ValidatorePosizioneDebitoriaTest {

    @Test
    @DisplayName("un aggregato valido non solleva eccezioni")
    void aggregatoValido() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);

        assertThatCode(() -> ValidatorePosizioneDebitoria.valida(posizione)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rifiuta una posizione senza soggetti debitori")
    void senzaSoggetti() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        posizione.getSoggettiDebitori().clear();

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("soggetto debitore");
    }

    @Test
    @DisplayName("rifiuta una posizione senza opzioni di pagamento")
    void senzaOpzioni() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        posizione.getOpzioniPagamento().clear();

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("opzione di pagamento");
    }

    @Test
    @DisplayName("PIANO_RATEALE con una sola pendenza viola il minimo di 2")
    void pianoRatealeSottoMinimo() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.PIANO_RATEALE, 1);

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("almeno 2");
    }

    @Test
    @DisplayName("SOLUZIONE_UNICA_ENTRO con due pendenze viola il massimo di 1")
    void soluzioneUnicaEntroSopraMassimo() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO, 2);
        posizione.getOpzioniPagamento().get(0).setGiorni(5);

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("massimo 1");
    }

    @Test
    @DisplayName("SOLUZIONE_UNICA_OLTRE senza giorni e' rifiutata")
    void soluzioneUnicaOltreSenzaGiorni() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA_OLTRE, 1);
        // giorni volutamente non impostato

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("giorni");
    }

    @Test
    @DisplayName("SOLUZIONE_UNICA con giorni impostato e' rifiutata (campo non previsto per questa tipologia)")
    void soluzioneUnicaConGiorni() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        posizione.getOpzioniPagamento().get(0).setGiorni(10);

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("non ammette");
    }

    @Test
    @DisplayName("una pendenza senza voci e' rifiutata")
    void pendenzaSenzaVoci() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().clear();

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("da 1 a 5 voci");
    }

    @Test
    @DisplayName("rifiuta un importo con più di 2 decimali, anche se la somma in memoria torna")
    void importoConTroppiDecimali() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        Pendenza pendenza = posizione.getOpzioniPagamento().get(0).getPendenze().get(0);
        // Due voci da 0.005 sommano esattamente 0.01 in memoria (compareTo lo accetterebbe),
        // ma ciascuna verrebbe arrotondata a 0.01 dal database (colonna NUMERIC(19,2)):
        // alla rilettura la somma sarebbe 0.02, non più coerente con l'importo della pendenza.
        pendenza.setImporto(new BigDecimal("0.01"));
        VocePendenza prima = pendenza.getVoci().get(0);
        prima.setImporto(new BigDecimal("0.005"));
        VocePendenza seconda = new VocePendenza();
        seconda.setIdVocePendenza("voce-extra");
        seconda.setImporto(new BigDecimal("0.005"));
        seconda.setDescrizione("test");
        seconda.setIndice(2);
        seconda.setStato(StatoVocePendenza.NON_ESEGUITO);
        seconda.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        seconda.setCodEntrata("SRV-1");
        pendenza.addVocePendenza(seconda);

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("più di 2 decimali");
    }

    @Test
    @DisplayName("l'importo della pendenza deve coincidere con la somma delle voci")
    void importoNonCoerenteConLeVoci() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0)
                .getVoci().get(0).setImporto(new BigDecimal("999.99"));

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("somma delle voci");
    }

    @Test
    @DisplayName("un dettaglioContabile valido (esattamente un campo alternativo valorizzato) e' accettato")
    void dettaglioContabileValido() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        primaVoce(posizione).setDettaglioContabile(List.of(
                new DettaglioContabile.Civilistico("2026", "UFF1", "14.01.03", null, null, BigDecimal.TEN)));

        assertThatCode(() -> ValidatorePosizioneDebitoria.valida(posizione)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dettaglioContabile non e' ammesso su una voce di tipo BOLLO")
    void dettaglioContabileNonAmmessoPerBollo() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        VocePendenza voce = primaVoce(posizione);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.BOLLO);
        voce.setDettaglioContabile(List.of(new DettaglioContabile.SpeseNotifica(BigDecimal.ONE)));

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("BOLLO");
    }

    @Test
    @DisplayName("CORRISPETTIVO_DL118 senza nessuno tra capitolo/accertamento/pianoFinanziario5Livello e' rifiutato")
    void corrispettivoDl118SenzaAlternativi() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        primaVoce(posizione).setDettaglioContabile(List.of(
                new DettaglioContabile.CorrispettivoDl118("2026", "UFF1", null, null, null, null, BigDecimal.TEN)));

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("CORRISPETTIVO_DL118")
                .hasMessageContaining("esattamente uno");
    }

    @Test
    @DisplayName("CORRISPETTIVO_DL118 con due tra capitolo/accertamento/pianoFinanziario5Livello e' rifiutato")
    void corrispettivoDl118ConDueAlternativi() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        primaVoce(posizione).setDettaglioContabile(List.of(
                new DettaglioContabile.CorrispettivoDl118("2026", "UFF1", "CAP1", "2026/1", null, null,
                        BigDecimal.TEN)));

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("esattamente uno");
    }

    @Test
    @DisplayName("CIVILISTICO richiede esattamente uno tra conto/commessa/nrDocumento")
    void civilisticoRichiedeEsattamenteUno() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        primaVoce(posizione).setDettaglioContabile(List.of(
                new DettaglioContabile.Civilistico("2026", "UFF1", null, null, null, BigDecimal.TEN)));

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("CIVILISTICO");
    }

    @Test
    @DisplayName("INCASSO_TIPICO con sia emissioneFattura sia nrDocumento e' rifiutato (alternativi)")
    void incassoTipicoNonAmmetteEntrambiGliAlternativi() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        primaVoce(posizione).setDettaglioContabile(List.of(
                new DettaglioContabile.IncassoTipico("2026", "UFF1", "DIRITTI", "SI", "DOC1", BigDecimal.TEN)));

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("alternativi");
    }

    @Test
    @DisplayName("UNKNOWN_ENTRIES non e' ammesso in scrittura: e' generato solo in lettura")
    void sconosciutoRifiutatoInScrittura() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        primaVoce(posizione).setDettaglioContabile(List.of(
                new DettaglioContabile.Sconosciuto(List.of(new DettaglioContabile.Sconosciuto.Voce("K", "V")))));

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("UNKNOWN_ENTRIES");
    }

    @Test
    @DisplayName("notificaSend attivo con una voce che ha gia' SPESE_NOTIFICA e' rifiutato (doppio addebito)")
    void notificaSendConSpeseNotificaGiaPresente() {
        PosizioneDebitoria posizione = posizioneValida(TipologiaOpzionePagamento.SOLUZIONE_UNICA, 1);
        posizione.setNotificaSend(true);
        primaVoce(posizione).setDettaglioContabile(List.of(new DettaglioContabile.SpeseNotifica(BigDecimal.ONE)));

        assertThatThrownBy(() -> ValidatorePosizioneDebitoria.valida(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("due volte");
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private VocePendenza primaVoce(PosizioneDebitoria posizione) {
        return posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().get(0);
    }

    private PosizioneDebitoria posizioneValida(TipologiaOpzionePagamento tipologia, int numeroPendenze) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdA2A("A2A-1");
        posizione.setIdPosizioneDebitoria("pos-1");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");

        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(tipologia);
        posizione.addOpzionePagamento(opzione);

        for (int i = 1; i <= numeroPendenze; i++) {
            Pendenza pendenza = new Pendenza();
            pendenza.setIdPendenza("pendenza-" + i);
            pendenza.setImporto(new BigDecimal("10.00"));
            opzione.addPendenza(pendenza);

            VocePendenza voce = new VocePendenza();
            voce.setIdVocePendenza("voce-" + i);
            voce.setImporto(new BigDecimal("10.00"));
            voce.setDescrizione("test");
            voce.setIndice(1);
            voce.setStato(StatoVocePendenza.NON_ESEGUITO);
            voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
            voce.setCodEntrata("SRV-1");
            pendenza.addVocePendenza(voce);
        }

        return posizione;
    }
}
