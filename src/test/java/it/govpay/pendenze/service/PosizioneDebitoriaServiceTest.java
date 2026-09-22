package it.govpay.pendenze.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Verifica {@link PosizioneDebitoriaService}, in particolare i tre difetti trovati in
 * revisione (non solo il comportamento a lieto fine): marcatura ACA mancante su
 * {@code attiva}/{@code annulla}, e le transizioni di stato di {@code OpzionePagamento}.
 * L'unicita' IUV/NAV per dominio (M13) e' verificata a livello di entita' in
 * {@code PosizioneDebitoriaMappingTest}, non qui.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@Import(PosizioneDebitoriaService.class)
@ActiveProfiles("test")
class PosizioneDebitoriaServiceTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PosizioneDebitoriaService service;

    @Test
    @DisplayName("crea valorizza audit e marcatura ACA su tutta la gerarchia, genera l'UUID dell'opzione")
    void creaValorizzaAuditETimestampAca() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        OpzionePagamento opzione = posizione.getOpzioniPagamento().get(0);
        Pendenza pendenza = opzione.getPendenze().get(0);
        opzione.setIdOpzionePagamento(null); // deve generarlo crea()

        PosizioneDebitoria salvata = service.crea(posizione);

        assertThat(salvata.getDataCreazione()).isNotNull();
        assertThat(salvata.getDataUltimoAggiornamento()).isNotNull();
        assertThat(salvata.getDataUltimaModificaAca()).isNotNull();
        assertThat(opzione.getIdOpzionePagamento()).isNotNull();
        assertThat(pendenza.getDataUltimaModificaAca()).isNotNull();
        assertThat(pendenza.getIdDominio()).isEqualTo(posizione.getIdDominio());
    }

    @Test
    @DisplayName("attiva porta ad ATTIVATA, annulla automaticamente le altre DISPONIBILI e marca ACA su entrambe")
    void attivaAnnullaLeAlternativeEMarcaAca() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdA2A("A2A-1");
        posizione.setIdPosizioneDebitoria("pos-1");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");

        OpzionePagamento scelta = opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1");
        OpzionePagamento alternativa = opzioneConPendenza(posizione, TipologiaOpzionePagamento.PIANO_RATEALE, "2");
        opzioneConPendenza(alternativa, "3"); // seconda rata, per rispettare il minimo di 2 di PIANO_RATEALE

        PosizioneDebitoria salvata = service.crea(posizione);
        em.flush();
        // Simula: l'ultima marcatura ACA e' gia' stata sincronizzata (colonne azzerate),
        // cosi' l'asserzione sotto dimostra che attiva() le ripopola davvero, non che
        // sono rimaste valorizzate dalla creazione.
        azzeraMarcatureAca(salvata);
        em.flush();
        em.clear();

        UUID idScelta = scelta.getIdOpzionePagamento();
        UUID idAlternativa = alternativa.getIdOpzionePagamento();

        OpzionePagamento attivata = service.attiva(idScelta);
        em.flush();
        em.clear();

        assertThat(attivata.getStato()).isEqualTo(StatoOpzionePagamento.ATTIVATA);

        PosizioneDebitoria riletta = em.find(PosizioneDebitoria.class, salvata.getId());
        assertThat(riletta.getDataUltimaModificaAca()).isNotNull();

        OpzionePagamento alternativaRiletta = riletta.getOpzioniPagamento().stream()
                .filter(o -> o.getIdOpzionePagamento().equals(idAlternativa))
                .findFirst().orElseThrow();
        assertThat(alternativaRiletta.getStato()).isEqualTo(StatoOpzionePagamento.ANNULLATA);
        assertThat(alternativaRiletta.getPendenze()).allSatisfy(
                p -> assertThat(p.getDataUltimaModificaAca()).isNotNull());

        OpzionePagamento sceltaRiletta = riletta.getOpzioniPagamento().stream()
                .filter(o -> o.getIdOpzionePagamento().equals(idScelta))
                .findFirst().orElseThrow();
        assertThat(sceltaRiletta.getPendenze()).allSatisfy(
                p -> assertThat(p.getDataUltimaModificaAca()).isNotNull());
    }

    @Test
    @DisplayName("attiva rifiuta un'opzione non DISPONIBILE")
    void attivaRifiutaOpzioneNonDisponibile() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        PosizioneDebitoria salvata = service.crea(posizione);
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        service.attiva(id);

        assertThatThrownBy(() -> service.attiva(id)).isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("annulla e' idempotente su un'opzione gia' ANNULLATA")
    void annullaIdempotente() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        PosizioneDebitoria salvata = service.crea(posizione);
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        service.annulla(id);
        OpzionePagamento risultato = service.annulla(id);

        assertThat(risultato.getStato()).isEqualTo(StatoOpzionePagamento.ANNULLATA);
    }

    @Test
    @DisplayName("annulla rifiuta un'opzione gia' ATTIVATA")
    void annullaRifiutaOpzioneAttivata() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        PosizioneDebitoria salvata = service.crea(posizione);
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        service.attiva(id);

        assertThatThrownBy(() -> service.annulla(id)).isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("attiva e annulla sollevano RisorsaNonTrovataException per un'opzione inesistente")
    void opzioneInesistente() {
        UUID inesistente = UUID.randomUUID();

        assertThatThrownBy(() -> service.attiva(inesistente)).isInstanceOf(RisorsaNonTrovataException.class);
        assertThatThrownBy(() -> service.annulla(inesistente)).isInstanceOf(RisorsaNonTrovataException.class);
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private void azzeraMarcatureAca(PosizioneDebitoria posizione) {
        posizione.setDataUltimaModificaAca(null);
        for (OpzionePagamento opzione : posizione.getOpzioniPagamento()) {
            for (Pendenza pendenza : opzione.getPendenze()) {
                pendenza.setDataUltimaModificaAca(null);
            }
        }
    }

    private PosizioneDebitoria posizioneConUnaOpzione(TipologiaOpzionePagamento tipologia) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        String suffisso = UUID.randomUUID().toString().substring(0, 8);
        posizione.setIdA2A("A2A-" + suffisso);
        posizione.setIdPosizioneDebitoria("pos-" + suffisso);
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        opzioneConPendenza(posizione, tipologia, "1");
        return posizione;
    }

    private OpzionePagamento opzioneConPendenza(PosizioneDebitoria posizione, TipologiaOpzionePagamento tipologia,
            String suffisso) {
        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(tipologia);
        posizione.addOpzionePagamento(opzione);
        opzioneConPendenza(opzione, suffisso);
        return opzione;
    }

    private Pendenza opzioneConPendenza(OpzionePagamento opzione, String suffisso) {
        Pendenza pendenza = new Pendenza();
        pendenza.setIdPendenza("pendenza-" + suffisso);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setImporto(new BigDecimal("10.00"));
        pendenza.setNumeroAvviso("30000000000000000" + suffisso);
        pendenza.setIuv("30000000000000000" + suffisso);
        pendenza.setStato(StatoPendenza.NON_ESEGUITA);
        pendenza.setDataCaricamento(LocalDate.of(2026, 7, 29));
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + suffisso);
        voce.setImporto(new BigDecimal("10.00"));
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-1");
        pendenza.addVocePendenza(voce);

        return pendenza;
    }
}
