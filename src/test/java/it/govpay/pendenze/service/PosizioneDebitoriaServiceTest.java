package it.govpay.pendenze.service;

import it.govpay.pendenze.comando.AggiornamentoPendenza;
import it.govpay.pendenze.comando.AggiornamentoPosizioneDebitoria;
import it.govpay.pendenze.comando.DatiOpzionePagamento;
import it.govpay.pendenze.comando.DatiPendenza;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.comando.DatiSoggettoDebitore;
import it.govpay.pendenze.comando.ModificaCampo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import it.govpay.common.repository.DominioRepository;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.entity.FlussoRendicontazione;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.RisorsaGiaEsistenteException;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.StatoFlussoRendicontazione;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoRendicontazione;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;

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
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DominioRepository.class))
@ActiveProfiles("test")
class PosizioneDebitoriaServiceTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PosizioneDebitoriaService service;

    /**
     * Spia sul bean reale (non un mock puro): usata solo per forzare
     * {@code existsByIdApplicazioneAndIdPosizioneDebitoria} a restituire {@code false} in un
     * singolo test, simulando la finestra di corsa fra il controllo preliminare e l'INSERT
     * reale — l'unico modo deterministico di riprodurre una violazione del vincolo UNIQUE
     * rilevata solo al flush, dato che in un test a singolo thread quel controllo la
     * intercetterebbe sempre per primo.
     */
    @MockitoSpyBean
    private PosizioneDebitoriaRepository posizioneDebitoriaRepository;

    private final Map<String, Long> applicazioni = new HashMap<>();

    private Long idApplicazionePer(String codApplicazione) {
        return applicazioni.computeIfAbsent(codApplicazione, cod -> {
            ApplicazioneEntity applicazione = ApplicazioneEntity.builder()
                    .codApplicazione(cod).autoIuv(true).firmaRicevuta("N").trusted(true)
                    .idUtenza((long) (applicazioni.size() + 1)).build();
            em.persistAndFlush(applicazione);
            return applicazione.getId();
        });
    }

    @Test
    @DisplayName("crea valorizza audit e marcatura ACA su tutta la gerarchia, genera l'UUID dell'opzione")
    void creaValorizzaAuditETimestampAca() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        OpzionePagamento opzione = posizione.getOpzioniPagamento().get(0);
        Pendenza pendenza = opzione.getPendenze().get(0);
        opzione.setIdOpzionePagamento(null); // deve generarlo crea()

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getDataCreazione()).isNotNull();
        assertThat(salvata.getDataUltimoAggiornamento()).isNotNull();
        assertThat(salvata.getDataUltimaModificaAca()).isNotNull();
        assertThat(salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento()).isNotNull();
        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getDataUltimaModificaAca()).isNotNull();
        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getIdDominio()).isEqualTo(posizione.getIdDominio());
    }

    @Test
    @DisplayName("attiva porta ad ATTIVATA, annulla automaticamente le altre DISPONIBILI e marca ACA su entrambe")
    void attivaAnnullaLeAlternativeEMarcaAca() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazionePer("A2A-1"));
        posizione.setIdPosizioneDebitoria("pos-1");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.addSoggettoDebitore(soggettoDiProva());

        OpzionePagamento scelta = opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1");
        OpzionePagamento alternativa = opzioneConPendenza(posizione, TipologiaOpzionePagamento.PIANO_RATEALE, "2");
        opzioneConPendenza(alternativa, "3"); // seconda rata, per un piano rateale realistico

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        em.flush();
        // Simula: l'ultima marcatura ACA e' gia' stata sincronizzata (colonne azzerate),
        // cosi' l'asserzione sotto dimostra che registraEsitoPendenza (via attiva) le
        // ripopola davvero, non che sono rimaste valorizzate dalla creazione.
        azzeraMarcatureAca(salvata);
        em.flush();
        em.clear();

        UUID idScelta = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        UUID idAlternativa = salvata.getOpzioniPagamento().get(1).getIdOpzionePagamento();

        Pendenza pendenzaEseguita = service.registraEsitoPendenza("A2A-1", "pendenza-1", StatoPendenza.ESEGUITO);
        OpzionePagamento attivata = pendenzaEseguita.getOpzionePagamento();
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
        assertThat(alternativaRiletta.getPendenze()).extracting(Pendenza::getStato)
                .containsOnly(StatoPendenza.ANNULLATO);

        OpzionePagamento sceltaRiletta = riletta.getOpzioniPagamento().stream()
                .filter(o -> o.getIdOpzionePagamento().equals(idScelta))
                .findFirst().orElseThrow();
        assertThat(sceltaRiletta.getPendenze()).allSatisfy(
                p -> assertThat(p.getDataUltimaModificaAca()).isNotNull());
    }

    @Test
    @DisplayName("crea rifiuta una pendenza priva di IUV/numero avviso se nessun GeneratoreIuv e' configurato")
    void creaRifiutaSenzaGeneratoreIuv() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setIuv(null);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setNumeroAvviso(null);

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(posizione))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("crea rifiuta una pendenza con solo numeroAvviso valorizzato se nessun GeneratoreIuv "
            + "e' configurato per ricavarne lo iuv")
    void creaRifiutaSoloNumeroAvvisoSenzaGeneratoreIuv() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        String numeroAvvisoFornito = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso();
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setIuv(null);

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(posizione)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(numeroAvvisoFornito);
    }

    @Test
    @DisplayName("crea rifiuta una pendenza con solo iuv valorizzato (senza numeroAvviso)")
    void creaRifiutaSoloIuv() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setNumeroAvviso(null);

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(posizione))).isInstanceOf(ValidazioneNonSuperataException.class);
    }

    @Test
    @DisplayName("crea assegna numeroRata e ordine dalla posizione nelle liste, non li lascia a 0")
    void creaAssegnaNumeroRataEOrdine() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazionePer("A2A-indici"));
        posizione.setIdPosizioneDebitoria("pos-indici");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.addSoggettoDebitore(soggettoDiProva());
        posizione.addSoggettoDebitore(soggettoDiProva2());

        OpzionePagamento opzione = opzioneConPendenza(posizione, TipologiaOpzionePagamento.PIANO_RATEALE, "1");
        opzioneConPendenza(opzione, "2");

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getSoggettiDebitori()).extracting(SoggettoDebitore::getOrdine)
                .containsExactly(0, 1);
        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze()).extracting(Pendenza::getNumeroRata)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("crea assegna l'indice delle voci aggiunte con addVocePendenza, non le lascia a 0")
    void creaAssegnaIndiceVoci() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        Pendenza pendenza = posizione.getOpzioniPagamento().get(0).getPendenze().get(0);
        // la fixture parte gia' con una voce da 10.00: la sostituiamo con due da 5.00
        // aggiunte nello stesso modo (addVocePendenza), per riprodurre esattamente il caso
        // segnalato — entrambe partirebbero da indice 0 senza il fix.
        pendenza.getVoci().clear();
        pendenza.addVocePendenza(voceDiProva("voce-a", 5.00));
        pendenza.addVocePendenza(voceDiProva("voce-b", 5.00));

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci()).extracting(VocePendenza::getIndice).containsExactly(1, 2);
    }

    @Test
    @DisplayName("crea valorizza VocePendenza.idDominio con quello della posizione se il chiamante "
            + "non indica un override esplicito (multi-beneficiario pagoPA)")
    void creaValorizzaIdDominioVoceConQuelloDellaPosizioneSeAssente() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        VocePendenza voce = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().get(0);
        voce.setIdDominio(null);

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().get(0).getIdDominio()).isEqualTo(posizione.getIdDominio());
    }

    @Test
    @DisplayName("crea mantiene l'idDominio esplicito di una voce se diverso da quello della posizione "
            + "(multi-beneficiario pagoPA: entrata destinata a un ente creditore diverso)")
    void creaMantieneIdDominioEsplicitoDellaVoceSeDiverso() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        VocePendenza voce = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().get(0);
        voce.setIdDominio(99L);

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().get(0).getIdDominio()).isEqualTo(99L);
        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().get(0).getIdDominio()).isNotEqualTo(posizione.getIdDominio());
    }

    @Test
    @DisplayName("crea rifiuta notificaSend attivo senza navNotifica se non c'e' SOLUZIONE_UNICA ne' PIANO_RATEALE")
    void creaRifiutaNotificaSendSenzaCandidatoPerNavNotifica() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazionePer("A2A-entro"));
        posizione.setIdPosizioneDebitoria("pos-entro");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.setNotificaSend(true);
        posizione.addSoggettoDebitore(soggettoDiProva());

        OpzionePagamento opzione = opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO, "1");
        opzione.setGiorni(5);

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(posizione)))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("navNotifica");
    }

    @Test
    @DisplayName("crea rifiuta navNotifica che non corrisponde al numeroAvviso di alcuna pendenza")
    void creaRifiutaNavNotificaNonCorrispondente() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.setNavNotifica("999999999999999999");

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(posizione)))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("navNotifica");
    }

    @Test
    @DisplayName("crea assegna automaticamente navNotifica se notificaSend e' attivo e la posizione ha una sola pendenza")
    void creaAssegnaNavNotificaAutomaticamente() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.setNotificaSend(true);
        String numeroAvviso = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso();

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getNavNotifica()).isEqualTo(numeroAvviso);
    }

    @Test
    @DisplayName("annulla e' idempotente su un'opzione gia' ANNULLATA")
    void annullaIdempotente() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        String idA2A = codApplicazioneDi(salvata);
        String idPos = salvata.getIdPosizioneDebitoria();
        service.annulla(idA2A, idPos, id);
        OpzionePagamento risultato = service.annulla(idA2A, idPos, id);

        assertThat(risultato.getStato()).isEqualTo(StatoOpzionePagamento.ANNULLATA);
    }

    @Test
    @DisplayName("annulla rifiuta un'opzione gia' ATTIVATA")
    void annullaRifiutaOpzioneAttivata() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        service.registraEsitoPendenza(codApplicazioneDi(salvata), "pendenza-1", StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.annulla(codApplicazioneDi(salvata), salvata.getIdPosizioneDebitoria(), id))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("annulla(idA2A, idPosizioneDebitoria, idOpzionePagamento) annulla l'opzione se "
            + "appartiene davvero a quella posizione/applicazione")
    void annullaScopedFunzionaSeLidentitaCorrisponde() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        OpzionePagamento risultato = service.annulla(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), id);

        assertThat(risultato.getStato()).isEqualTo(StatoOpzionePagamento.ANNULLATA);
    }

    @Test
    @DisplayName("annulla(idA2A, idPosizioneDebitoria, idOpzionePagamento) rifiuta con "
            + "RisorsaNonTrovataException (non annulla nulla) se l'opzione esiste ma appartiene "
            + "a un'ALTRA posizione/applicazione — un'applicazione non deve poter annullare "
            + "un'opzione di un'altra indovinandone lo UUID")
    void annullaScopedRifiutaSeLopzioneNonAppartieneAQuestaPosizione() {
        PosizioneDebitoria posizioneA = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        // Non posizioneConUnaOpzione() una seconda volta: userebbe lo stesso idDominio (1L) e
        // lo stesso suffisso fisso "1" per la pendenza, collidendo su numeroAvviso con
        // posizioneA gia' creata (stesso identico bug di isolamento che avrebbe
        // attivaAnnullaLeAlternativeEMarcaAca se non costruisse le sue opzioni a mano).
        PosizioneDebitoria posizioneB = new PosizioneDebitoria();
        posizioneB.setIdApplicazione(idApplicazionePer("A2A-" + UUID.randomUUID().toString().substring(0, 8)));
        posizioneB.setIdPosizioneDebitoria("pos-" + UUID.randomUUID().toString().substring(0, 8));
        posizioneB.setIdDominio(2L);
        posizioneB.setDescrizione("test");
        posizioneB.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizioneB, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "b");
        posizioneB = service.crea(DatiPosizioneDebitoria.da(posizioneB));
        UUID idOpzioneB = posizioneB.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        assertThatThrownBy(() -> service.annulla(codApplicazioneDi(posizioneA),
                posizioneA.getIdPosizioneDebitoria(), idOpzioneB))
                .isInstanceOf(RisorsaNonTrovataException.class);

        // ancora DISPONIBILE: il tentativo di annullamento rifiutato sopra non l'ha toccata.
        Pendenza pendenzaBEseguita = service.registraEsitoPendenza(codApplicazioneDi(posizioneB), "pendenza-b",
                StatoPendenza.ESEGUITO);
        assertThat(pendenzaBEseguita.getOpzionePagamento().getStato()).isEqualTo(StatoOpzionePagamento.ATTIVATA);
    }

    @Test
    @DisplayName("annulla(idA2A, idPosizioneDebitoria, idOpzionePagamento) rifiuta con "
            + "RisorsaNonTrovataException un'opzione inesistente")
    void annullaScopedRifiutaOpzioneInesistente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        assertThatThrownBy(() -> service.annulla(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), UUID.randomUUID()))
                .isInstanceOf(RisorsaNonTrovataException.class);
    }

    @Test
    @DisplayName("annulla(idA2A, idPosizioneDebitoria, idOpzionePagamento) rifiuta un'opzione gia' ATTIVATA")
    void annullaScopedRifiutaOpzioneAttivata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        service.registraEsitoPendenza(codApplicazioneDi(posizione), "pendenza-1", StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.annulla(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), id))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("annulla imposta ANNULLATO su tutte le pendenze dell'opzione (PIANO_RATEALE, piu' di una rata)")
    void annullaCascataSuTutteLePendenze() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.PIANO_RATEALE);
        opzioneConPendenza(posizione.getOpzioniPagamento().get(0), "2");
        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        OpzionePagamento risultato = service.annulla(codApplicazioneDi(salvata), salvata.getIdPosizioneDebitoria(),
                id);

        assertThat(risultato.getPendenze()).extracting(Pendenza::getStato).containsOnly(StatoPendenza.ANNULLATO);
    }

    @Test
    @DisplayName("ripristina riporta DISPONIBILE un'opzione ANNULLATA, con cascata NON_ESEGUITO su tutte le pendenze")
    void ripristinaFunzionaECascataSuTutteLePendenze() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.PIANO_RATEALE);
        opzioneConPendenza(posizione.getOpzioniPagamento().get(0), "2");
        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        String idA2A = codApplicazioneDi(salvata);
        String idPos = salvata.getIdPosizioneDebitoria();
        service.annulla(idA2A, idPos, id);

        OpzionePagamento risultato = service.ripristina(idA2A, idPos, id);

        assertThat(risultato.getStato()).isEqualTo(StatoOpzionePagamento.DISPONIBILE);
        assertThat(risultato.getPendenze()).extracting(Pendenza::getStato).containsOnly(StatoPendenza.NON_ESEGUITO);
    }

    @Test
    @DisplayName("ripristina e' idempotente su un'opzione gia' DISPONIBILE")
    void ripristinaIdempotente() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        OpzionePagamento risultato = service.ripristina(codApplicazioneDi(salvata), salvata.getIdPosizioneDebitoria(),
                id);

        assertThat(risultato.getStato()).isEqualTo(StatoOpzionePagamento.DISPONIBILE);
    }

    @Test
    @DisplayName("ripristina rifiuta un'opzione ATTIVATA")
    void ripristinaRifiutaOpzioneAttivata() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        UUID id = salvata.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        service.registraEsitoPendenza(codApplicazioneDi(salvata), "pendenza-1", StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.ripristina(codApplicazioneDi(salvata), salvata.getIdPosizioneDebitoria(), id))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("ripristina(idA2A, idPosizioneDebitoria, idOpzionePagamento) ripristina l'opzione se "
            + "appartiene davvero a quella posizione/applicazione")
    void ripristinaScopedFunzionaSeLidentitaCorrisponde() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        service.annulla(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(), id);

        OpzionePagamento risultato = service.ripristina(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), id);

        assertThat(risultato.getStato()).isEqualTo(StatoOpzionePagamento.DISPONIBILE);
    }

    @Test
    @DisplayName("ripristina(idA2A, idPosizioneDebitoria, idOpzionePagamento) rifiuta con "
            + "RisorsaNonTrovataException se l'opzione appartiene a un'ALTRA posizione/applicazione")
    void ripristinaScopedRifiutaSeLopzioneNonAppartieneAQuestaPosizione() {
        PosizioneDebitoria posizioneA = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        PosizioneDebitoria posizioneB = new PosizioneDebitoria();
        posizioneB.setIdApplicazione(idApplicazionePer("A2A-" + UUID.randomUUID().toString().substring(0, 8)));
        posizioneB.setIdPosizioneDebitoria("pos-" + UUID.randomUUID().toString().substring(0, 8));
        posizioneB.setIdDominio(2L);
        posizioneB.setDescrizione("test");
        posizioneB.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizioneB, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "b");
        posizioneB = service.crea(DatiPosizioneDebitoria.da(posizioneB));
        UUID idOpzioneB = posizioneB.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        service.annulla(codApplicazioneDi(posizioneB), posizioneB.getIdPosizioneDebitoria(), idOpzioneB);

        assertThatThrownBy(() -> service.ripristina(codApplicazioneDi(posizioneA),
                posizioneA.getIdPosizioneDebitoria(), idOpzioneB))
                .isInstanceOf(RisorsaNonTrovataException.class);
    }

    @Test
    @DisplayName("ripristina(idA2A, idPosizioneDebitoria, idOpzionePagamento) rifiuta con "
            + "RisorsaNonTrovataException un'opzione inesistente")
    void ripristinaScopedRifiutaOpzioneInesistente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        assertThatThrownBy(() -> service.ripristina(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), UUID.randomUUID()))
                .isInstanceOf(RisorsaNonTrovataException.class);
    }

    @Test
    @DisplayName("ripristina(idA2A, idPosizioneDebitoria, idOpzionePagamento) rifiuta un'opzione gia' ATTIVATA")
    void ripristinaScopedRifiutaOpzioneAttivata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        service.registraEsitoPendenza(codApplicazioneDi(posizione), "pendenza-1", StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.ripristina(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), id))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    /**
     * Bug riprodotto in revisione: senza questo controllo, annulla() sovrascriveva
     * silenziosamente con ANNULLATO una pendenza gia' eseguita (disallineamento fra
     * opzione.stato e lo stato reale delle sue pendenze).
     */
    @Test
    @DisplayName("annulla rifiuta se una pendenza e' gia' eseguita, invece di sovrascriverla silenziosamente")
    void annullaRifiutaSePendenzaGiaEseguita() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        Pendenza pendenza = posizione.getOpzioniPagamento().get(0).getPendenze().get(0);
        pendenza.setStato(StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.annulla(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                id)).isInstanceOf(TransizioneStatoNonAmmessaException.class);
        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
    }

    /**
     * Il ramo idempotente (opzione gia' ANNULLATA) deve rilevare il disallineamento, non
     * ignorarlo solo perche' non ci sarebbe nulla da mutare sull'opzione stessa.
     */
    @Test
    @DisplayName("annulla rileva un disallineamento anche quando sarebbe idempotente (opzione gia' ANNULLATA)")
    void annullaIdempotenteRilevaPendenzaDisallineata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        String idA2A = codApplicazioneDi(posizione);
        String idPos = posizione.getIdPosizioneDebitoria();
        service.annulla(idA2A, idPos, id);
        // Simula un disallineamento esterno (es. un futuro motore di registrazione pagamenti
        // che scrivesse la pendenza senza passare da questo service).
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setStato(StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.annulla(idA2A, idPos, id))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    /**
     * Bug riprodotto in revisione: {@code verificaNessunaPendenzaEseguita} da sola accetta
     * qualunque mix di NON_ESEGUITO/ANNULLATO, quindi non rilevava una pendenza rimasta
     * NON_ESEGUITO mentre l'opzione e' GIA' ANNULLATA (nessuna cascata l'avrebbe mai
     * corretta, essendo un ritorno idempotente).
     */
    @Test
    @DisplayName("annulla rifiuta (non ignora) se l'opzione e' gia' ANNULLATA ma una pendenza e' rimasta NON_ESEGUITO")
    void annullaIdempotenteRilevaPendenzaNonEseguitaDisallineata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        String idA2A = codApplicazioneDi(posizione);
        String idPos = posizione.getIdPosizioneDebitoria();
        service.annulla(idA2A, idPos, id);
        // Simula un disallineamento esterno: l'opzione e' ANNULLATA ma questa pendenza non
        // ha seguito la cascata (es. scritta direttamente da un futuro motore esterno).
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setStato(StatoPendenza.NON_ESEGUITO);

        assertThatThrownBy(() -> service.annulla(idA2A, idPos, id))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("ripristina rifiuta se una pendenza e' gia' eseguita, invece di sovrascriverla silenziosamente")
    void ripristinaRifiutaSePendenzaGiaEseguita() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        String idA2A = codApplicazioneDi(posizione);
        String idPos = posizione.getIdPosizioneDebitoria();
        service.annulla(idA2A, idPos, id);
        Pendenza pendenza = posizione.getOpzioniPagamento().get(0).getPendenze().get(0);
        pendenza.setStato(StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.ripristina(idA2A, idPos, id))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
        assertThat(pendenza.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
    }

    @Test
    @DisplayName("ripristina rileva un disallineamento anche quando sarebbe idempotente (opzione gia' DISPONIBILE)")
    void ripristinaIdempotenteRilevaPendenzaDisallineata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setStato(StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.ripristina(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                id)).isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    /**
     * Simmetrico di {@link #annullaIdempotenteRilevaPendenzaNonEseguitaDisallineata}: una
     * pendenza rimasta ANNULLATO mentre l'opzione e' GIA' DISPONIBILE non viene rilevata da
     * {@code verificaNessunaPendenzaEseguita} (ANNULLATO non e' "eseguita"), ma e' comunque
     * un disallineamento perche' nessuna cascata e' in corso per correggerla.
     */
    @Test
    @DisplayName("ripristina rifiuta (non ignora) se l'opzione e' gia' DISPONIBILE ma una pendenza e' rimasta ANNULLATO")
    void ripristinaIdempotenteRilevaPendenzaAnnullataDisallineata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID id = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        // L'opzione resta DISPONIBILE (nessuna transizione reale), ma la pendenza viene
        // forzata ad ANNULLATO per simulare un disallineamento esterno.
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setStato(StatoPendenza.ANNULLATO);

        assertThatThrownBy(() -> service.ripristina(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                id)).isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    /**
     * Bug riprodotto in revisione: 1) attiva(A) annulla automaticamente B (ancora
     * DISPONIBILE) e le sue pendenze; 2) senza questo controllo, ripristina(B) la riportava
     * DISPONIBILE (pendenze a NON_ESEGUITO) con A ancora ATTIVATA, vanificando l'esclusione
     * reciproca e rendendo B di nuovo attivabile in seguito.
     */
    @Test
    @DisplayName("ripristina rifiuta se un'altra opzione della stessa posizione e' ATTIVATA")
    void ripristinaRifiutaSeAltraOpzioneAttivata() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazionePer("A2A-" + UUID.randomUUID().toString().substring(0, 8)));
        posizione.setIdPosizioneDebitoria("pos-" + UUID.randomUUID().toString().substring(0, 8));
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "a");
        OpzionePagamento b = opzioneConPendenza(posizione, TipologiaOpzionePagamento.PIANO_RATEALE, "b1");
        opzioneConPendenza(b, "b2"); // seconda rata, per un piano rateale realistico

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        UUID idB = salvata.getOpzioniPagamento().get(1).getIdOpzionePagamento();

        // cascata automatica: B -> ANNULLATA, sue pendenze -> ANNULLATO
        String idA2A = codApplicazioneDi(salvata);
        service.registraEsitoPendenza(idA2A, "pendenza-a", StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.ripristina(idA2A, salvata.getIdPosizioneDebitoria(), idB))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("registraEsitoPendenza allinea l'opzione ad ATTIVATA, in un'unica operazione")
    void registraEsitoPendenzaAllineaOpzioneAdAttivata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        Pendenza risultato = service.registraEsitoPendenza(codApplicazioneDi(posizione), "pendenza-1",
                StatoPendenza.ESEGUITO);

        assertThat(risultato.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
        assertThat(posizione.getOpzioniPagamento().get(0).getStato()).isEqualTo(StatoOpzionePagamento.ATTIVATA);
    }

    /**
     * Stessa cascata di {@link #attivaAnnullaLeAlternativeEMarcaAca} ({@code attiva(UUID)}),
     * ma innescata dal verso pendenza, non dall'opzione — e' esattamente il coordinamento
     * che {@code registraEsitoPendenza} deve garantire in un'unica operazione.
     */
    @Test
    @DisplayName("registraEsitoPendenza annulla automaticamente le alternative e le loro pendenze")
    void registraEsitoPendenzaAnnullaAutomaticamenteLeAlternative() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazionePer("A2A-" + UUID.randomUUID().toString().substring(0, 8)));
        posizione.setIdPosizioneDebitoria("pos-" + UUID.randomUUID().toString().substring(0, 8));
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "a");
        OpzionePagamento alternativa = opzioneConPendenza(posizione, TipologiaOpzionePagamento.PIANO_RATEALE, "b1");
        opzioneConPendenza(alternativa, "b2"); // seconda rata, per un piano rateale realistico

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        String idA2A = codApplicazioneDi(salvata);

        service.registraEsitoPendenza(idA2A, "pendenza-a", StatoPendenza.ESEGUITO);

        OpzionePagamento alternativaRiletta = salvata.getOpzioniPagamento().get(1);
        assertThat(alternativaRiletta.getStato()).isEqualTo(StatoOpzionePagamento.ANNULLATA);
        assertThat(alternativaRiletta.getPendenze()).extracting(Pendenza::getStato)
                .containsOnly(StatoPendenza.ANNULLATO);
    }

    @Test
    @DisplayName("registraEsitoPendenza non richiama di nuovo la cascata se l'opzione e' gia' ATTIVATA")
    void registraEsitoPendenzaNonRipeteLaCascataSeGiaAttivata() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.PIANO_RATEALE);
        opzioneConPendenza(posizione.getOpzioniPagamento().get(0), "2");
        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));
        String idA2A = codApplicazioneDi(salvata);
        service.registraEsitoPendenza(idA2A, "pendenza-1", StatoPendenza.ESEGUITO);
        em.flush();
        // Simula: l'ultima marcatura ACA e' gia' stata sincronizzata (colonne azzerate), cosi'
        // l'asserzione sotto dimostra che il secondo esito la ripopola davvero (bug riprodotto
        // in revisione: senza marcaModificaAca esplicito su questo ramo, restava nulla).
        azzeraMarcatureAca(salvata);
        em.flush();

        Pendenza risultato = service.registraEsitoPendenza(idA2A, "pendenza-2", StatoPendenza.ESEGUITO);

        assertThat(risultato.getStato()).isEqualTo(StatoPendenza.ESEGUITO);
        assertThat(risultato.getDataUltimaModificaAca()).isNotNull();
        assertThat(salvata.getOpzioniPagamento().get(0).getStato()).isEqualTo(StatoOpzionePagamento.ATTIVATA);
        assertThat(salvata.getDataUltimaModificaAca()).isNotNull();
    }

    /**
     * Bug riprodotto in revisione: senza questo controllo, {@code registraEsitoPendenza(...,
     * NON_ESEGUITO)} su una pendenza gia' ESEGUITO lasciava l'opzione incoerentemente
     * ATTIVATA con la pendenza "appena pagata" tornata NON_ESEGUITO — un disallineamento
     * silenzioso.
     */
    @Test
    @DisplayName("registraEsitoPendenza rifiuta NON_ESEGUITO: non e' un esito di pagamento")
    void registraEsitoPendenzaRifiutaNonEseguito() {
        String idA2A = codApplicazioneDi(service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA))));

        assertThatThrownBy(() -> service.registraEsitoPendenza(idA2A, "pendenza-1", StatoPendenza.NON_ESEGUITO))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    /**
     * Bug riprodotto in revisione: senza questo controllo, {@code registraEsitoPendenza(...,
     * ANNULLATO)} reintroduceva l'annullamento indipendente della singola pendenza da una
     * porta laterale — esattamente il percorso che la v3 ha deliberatamente eliminato in
     * favore del solo annullamento a livello di opzione.
     */
    @Test
    @DisplayName("registraEsitoPendenza rifiuta ANNULLATO: non e' un esito di pagamento")
    void registraEsitoPendenzaRifiutaAnnullato() {
        String idA2A = codApplicazioneDi(service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA))));

        assertThatThrownBy(() -> service.registraEsitoPendenza(idA2A, "pendenza-1", StatoPendenza.ANNULLATO))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("registraEsitoPendenza rifiuta un nuovoStato nullo")
    void registraEsitoPendenzaRifiutaStatoNullo() {
        String idA2A = codApplicazioneDi(service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA))));

        assertThatThrownBy(() -> service.registraEsitoPendenza(idA2A, "pendenza-1", null))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("registraEsitoPendenza rifiuta se l'opzione della pendenza e' ANNULLATA")
    void registraEsitoPendenzaRifiutaSeOpzioneAnnullata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        String idA2A = codApplicazioneDi(posizione);
        service.annulla(idA2A, posizione.getIdPosizioneDebitoria(),
                posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento());

        assertThatThrownBy(() -> service.registraEsitoPendenza(idA2A, "pendenza-1", StatoPendenza.ESEGUITO))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("registraEsitoPendenza solleva RisorsaNonTrovataException per una pendenza inesistente")
    void registraEsitoPendenzaRisorsaInesistente() {
        assertThatThrownBy(() -> service.registraEsitoPendenza("A2A-INESISTENTE", "pendenza-inesistente",
                StatoPendenza.ESEGUITO))
                .isInstanceOf(RisorsaNonTrovataException.class);
    }

    @Test
    @DisplayName("aggiorna applica la mutazione, rivalida e marca ACA su posizione e pendenze")
    void aggiornaApplicaMutazioneEMarcaAca() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        azzeraMarcatureAca(posizione);
        em.flush();
        em.clear();

        PosizioneDebitoria aggiornata = service.aggiorna(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                new AggiornamentoPosizioneDebitoria(ModificaCampo.imposta("nuova descrizione"), ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato()));

        assertThat(aggiornata.getDescrizione()).isEqualTo("nuova descrizione");
        assertThat(aggiornata.getDataUltimoAggiornamento()).isNotNull();
        assertThat(aggiornata.getDataUltimaModificaAca()).isNotNull();
        assertThat(aggiornata.getOpzioniPagamento().get(0).getPendenze().get(0).getDataUltimaModificaAca())
                .isNotNull();
    }

    @Test
    @DisplayName("aggiorna solleva RisorsaNonTrovataException per una posizione inesistente")
    void aggiornaRisorsaInesistente() {
        assertThatThrownBy(() -> service.aggiorna("A2A-INESISTENTE", "pos-inesistente", AggiornamentoPosizioneDebitoria.nessunaModifica())).isInstanceOf(RisorsaNonTrovataException.class);
    }

    @Test
    @DisplayName("aggiorna rivalida l'aggregato: rifiuta uno svuotamento di soggettiDebitori")
    void aggiornaRifiutaSoggettiDebitoriVuoti() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        assertThatThrownBy(() -> service.aggiorna(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                conSoggetti(java.util.List.of())))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("soggetto debitore");
    }

    @Test
    @DisplayName("aggiorna sostituisce soggettiDebitori: il vecchio soggetto e' rimosso (orphanRemoval), "
            + "il nuovo ha ordine assegnato in base alla posizione nella lista")
    void aggiornaSostituisceSoggettiDebitori() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        em.clear();

        service.aggiorna(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                conSoggetti(java.util.List.of(soggettoDiProva2(), soggettoDiProva())));
        em.flush();
        em.clear();

        PosizioneDebitoria riletta = em.find(PosizioneDebitoria.class, posizione.getId());
        assertThat(riletta.getSoggettiDebitori()).hasSize(2);
        assertThat(riletta.getSoggettiDebitori().get(0).getIdentificativo())
                .isEqualTo(soggettoDiProva2().getIdentificativo());
        assertThat(riletta.getSoggettiDebitori().get(1).getIdentificativo())
                .isEqualTo(soggettoDiProva().getIdentificativo());
    }

    @Test
    @DisplayName("aggiorna assegna automaticamente navNotifica se il patch attiva notificaSend "
            + "senza specificarlo esplicitamente (stessa regola di crea)")
    void aggiornaAssegnaNavNotificaAutomaticamente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        String numeroAvviso = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso();

        PosizioneDebitoria aggiornata = service.aggiorna(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                new AggiornamentoPosizioneDebitoria(ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.imposta(true), ModificaCampo.invariato(), ModificaCampo.invariato()));

        assertThat(aggiornata.getNavNotifica()).isEqualTo(numeroAvviso);
    }

    @Test
    @DisplayName("aggiorna rifiuta un navNotifica che non corrisponde al numeroAvviso di alcuna pendenza")
    void aggiornaRifiutaNavNotificaNonCorrispondente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        assertThatThrownBy(() -> service.aggiorna(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                new AggiornamentoPosizioneDebitoria(ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.imposta("999999999999999999"), ModificaCampo.invariato())))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("navNotifica");
    }

    @Test
    @DisplayName("aggiungiOpzionePagamento aggiunge una nuova opzione all'aggregato esistente, "
            + "assegna id/stato/timestamp/ACA solo alla nuova opzione")
    void aggiungiOpzionePagamentoAggiungeENonToccaLeEsistenti() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        azzeraMarcatureAca(posizione);
        em.flush();
        em.clear();

        OpzionePagamento nuova = service.aggiungiOpzionePagamento(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(),
                DatiOpzionePagamento.da(nuovaOpzioneStandalone(1L, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "nuova")));

        assertThat(nuova.getIdOpzionePagamento()).isNotNull();
        assertThat(nuova.getStato()).isEqualTo(StatoOpzionePagamento.DISPONIBILE);
        assertThat(nuova.getDataCreazione()).isNotNull();
        assertThat(nuova.getPendenze().get(0).getDataUltimaModificaAca()).isNotNull();
        assertThat(nuova.getPendenze().get(0).getIdDominio()).isEqualTo(posizione.getIdDominio());

        PosizioneDebitoria riletta = em.find(PosizioneDebitoria.class, posizione.getId());
        assertThat(riletta.getOpzioniPagamento()).hasSize(2);
        assertThat(riletta.getDataUltimaModificaAca()).isNotNull();
        // La marcatura ACA azzerata sopra sulla pendenza GIA' esistente non viene ritoccata
        // dall'aggiunta di una nuova opzione (solo quella nuova ne ha bisogno).
        Pendenza pendenzaEsistente = riletta.getOpzioniPagamento().stream()
                .filter(o -> !o.getIdOpzionePagamento().equals(nuova.getIdOpzionePagamento()))
                .findFirst().orElseThrow()
                .getPendenze().get(0);
        assertThat(pendenzaEsistente.getDataUltimaModificaAca()).isNull();
    }

    @Test
    @DisplayName("aggiungiOpzionePagamento assegna numeroRata/indice alla nuova opzione in base "
            + "all'ordine delle sue liste, senza toccare quelli delle opzioni esistenti")
    void aggiungiOpzionePagamentoAssegnaIndici() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        OpzionePagamento nuova = service.aggiungiOpzionePagamento(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(),
                DatiOpzionePagamento.da(nuovaOpzioneStandalone(1L, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "nuova")));

        assertThat(nuova.getPendenze().get(0).getNumeroRata()).isEqualTo(1);
        assertThat(nuova.getPendenze().get(0).getVoci().get(0).getIndice()).isEqualTo(1);
        assertThat(posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroRata()).isEqualTo(1);
    }

    @Test
    @DisplayName("aggiungiOpzionePagamento solleva RisorsaNonTrovataException per una posizione inesistente")
    void aggiungiOpzionePagamentoRisorsaInesistente() {
        assertThatThrownBy(() -> service.aggiungiOpzionePagamento("A2A-INESISTENTE", "pos-inesistente",
                DatiOpzionePagamento.da(nuovaOpzioneStandalone(1L, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "x"))))
                .isInstanceOf(RisorsaNonTrovataException.class);
    }

    @Test
    @DisplayName("aggiungiOpzionePagamento rivalida l'intero aggregato: rifiuta un numeroAvviso "
            + "duplicato tra la nuova opzione e una gia' esistente")
    void aggiungiOpzionePagamentoRifiutaNumeroAvvisoDuplicato() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        String numeroAvvisoEsistente = posizione.getOpzioniPagamento().get(0).getPendenze().get(0)
                .getNumeroAvviso();

        assertThatThrownBy(() -> service.aggiungiOpzionePagamento(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(),
                DatiOpzionePagamento.da(((java.util.function.Supplier<OpzionePagamento>) () -> {
                    OpzionePagamento opzione = nuovaOpzioneStandalone(1L,
                            TipologiaOpzionePagamento.SOLUZIONE_UNICA, "duplicata");
                    opzione.getPendenze().get(0).setNumeroAvviso(numeroAvvisoEsistente);
                    opzione.getPendenze().get(0).setIuv(numeroAvvisoEsistente);
                    return opzione;
                }).get())))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("numeroAvviso");
    }

    @Test
    @DisplayName("aggiungiOpzionePagamento rifiuta se la posizione ha gia' un'opzione ATTIVATA "
            + "(pagamento gia' eseguito): un'alternativa aggiunta dopo non sarebbe mai passata "
            + "per l'annullamento automatico")
    void aggiungiOpzionePagamentoRifiutaSeEsisteGiaUnaOpzioneAttivata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        service.registraEsitoPendenza(codApplicazioneDi(posizione), "pendenza-1", StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> service.aggiungiOpzionePagamento(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(),
                DatiOpzionePagamento.da(nuovaOpzioneStandalone(1L, TipologiaOpzionePagamento.SOLUZIONE_UNICA,
                        "dopo-attivazione"))))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class)
                .hasMessageContaining("ATTIVATA");
    }

    @Test
    @DisplayName("aggiungiOpzionePagamento rifiuta con RisorsaGiaEsistenteException (non 500) un "
            + "idPendenza gia' usato da un'altra pendenza della stessa applicazione, anche di "
            + "un'altra posizione")
    void aggiungiOpzionePagamentoRifiutaIdPendenzaGiaUsatoDaAltraPosizione() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        String idPendenzaEsistente = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getIdPendenza();

        assertThatThrownBy(() -> service.aggiungiOpzionePagamento(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(),
                DatiOpzionePagamento.da(((java.util.function.Supplier<OpzionePagamento>) () -> {
                    OpzionePagamento opzione = nuovaOpzioneStandalone(1L,
                            TipologiaOpzionePagamento.SOLUZIONE_UNICA, "id-duplicato");
                    opzione.getPendenze().get(0).setIdPendenza(idPendenzaEsistente);
                    return opzione;
                }).get())))
                .isInstanceOf(RisorsaGiaEsistenteException.class)
                .hasMessageContaining(idPendenzaEsistente);
    }

    @Test
    @DisplayName("aggiungiPendenza aggiunge una pendenza a un'opzione SOLUZIONE_UNICA esistente, "
            + "con numeroRata successivo a quello della pendenza gia' presente")
    void aggiungiPendenzaAggiungeAOpzioneSoluzioneUnicaEsistente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID idOpzione = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        em.flush();
        em.clear();

        Pendenza aggiunta = service.aggiungiPendenza(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), idOpzione,
                DatiPendenza.da(nuovaPendenzaStandalone(1L, "aggiunta")));

        assertThat(aggiunta.getNumeroRata()).isEqualTo(2);
        assertThat(aggiunta.getIdDominio()).isEqualTo(posizione.getIdDominio());
        assertThat(aggiunta.getVoci().get(0).getIndice()).isEqualTo(1);

        OpzionePagamento opzioneRiletta = em.find(OpzionePagamento.class,
                posizione.getOpzioniPagamento().get(0).getId());
        assertThat(opzioneRiletta.getPendenze()).hasSize(2);
    }

    @Test
    @DisplayName("aggiungiPendenza funziona anche su PIANO_RATEALE (non solo SOLUZIONE_UNICA): "
            + "nessuna delle due tipologie ha un limite massimo di pendenze")
    void aggiungiPendenzaAggiungeAOpzionePianoRatealeEsistente() {
        // Due pendenze fin dalla creazione, per uno scenario di piano rateale realistico
        // (il minimo di 2 e' comunque solo nel contratto REST v3, non in questa libreria —
        // vedi cardinalitaPendenzeMinima).
        PosizioneDebitoria posizioneDaCreare = posizioneConUnaOpzione(TipologiaOpzionePagamento.PIANO_RATEALE);
        opzioneConPendenza(posizioneDaCreare.getOpzioniPagamento().get(0), "2");
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneDaCreare));
        UUID idOpzione = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        Pendenza aggiunta = service.aggiungiPendenza(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), idOpzione,
                DatiPendenza.da(nuovaPendenzaStandalone(1L, "rata-3")));

        assertThat(aggiunta.getNumeroRata()).isEqualTo(3);
    }

    @Test
    @DisplayName("aggiungiPendenza rifiuta con ValidazioneNonSuperataException (la cardinalita' massima "
            + "e' verificata dal validatore di libreria, non da un controllo duplicato qui) l'aggiunta "
            + "a un'opzione SOLUZIONE_UNICA_ENTRO che ne ammette al massimo una")
    void aggiungiPendenzaRifiutaSeSuperaLaCardinalitaMassima() {
        PosizioneDebitoria posizioneDaCreare = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO);
        posizioneDaCreare.getOpzioniPagamento().get(0).setGiorni(30);
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneDaCreare));
        UUID idOpzione = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        assertThatThrownBy(() -> service.aggiungiPendenza(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), idOpzione,
                DatiPendenza.da(nuovaPendenzaStandalone(1L, "seconda"))))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("al massimo");
    }

    @Test
    @DisplayName("aggiungiPendenza rifiuta se l'opzione non e' DISPONIBILE (es. ANNULLATA)")
    void aggiungiPendenzaRifiutaSeOpzioneNonDisponibile() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        UUID idOpzione = posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento();
        service.annulla(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(), idOpzione);

        assertThatThrownBy(() -> service.aggiungiPendenza(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), idOpzione,
                DatiPendenza.da(nuovaPendenzaStandalone(1L, "su-annullata"))))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class)
                .hasMessageContaining("DISPONIBILE");
    }

    @Test
    @DisplayName("aggiungiPendenza solleva RisorsaNonTrovataException per un'opzione inesistente")
    void aggiungiPendenzaRisorsaInesistente() {
        assertThatThrownBy(() -> service.aggiungiPendenza("A2A-INESISTENTE", "pos-inesistente", UUID.randomUUID(),
                DatiPendenza.da(nuovaPendenzaStandalone(1L, "x"))))
                .isInstanceOf(RisorsaNonTrovataException.class);
    }

    @Test
    @DisplayName("aggiungiPendenza solleva RisorsaNonTrovataException se l'opzione esiste ma appartiene "
            + "a un'altra posizione/applicazione (nessuna rivelazione dell'appartenenza reale)")
    void aggiungiPendenzaRisorsaInesistenteSeOpzioneDiAltraPosizione() {
        PosizioneDebitoria posizioneA = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));

        // Non posizioneConUnaOpzione per posizioneB: usa sempre suffisso pendenza/voce "1" fisso,
        // che qui collide (stesso idDominio=1L) con la pendenza-1/numeroAvviso di posizioneA.
        PosizioneDebitoria posizioneBDaCreare = new PosizioneDebitoria();
        String suffissoB = UUID.randomUUID().toString().substring(0, 8);
        posizioneBDaCreare.setIdApplicazione(idApplicazionePer("A2A-" + suffissoB));
        posizioneBDaCreare.setIdPosizioneDebitoria("pos-" + suffissoB);
        posizioneBDaCreare.setIdDominio(1L);
        posizioneBDaCreare.setDescrizione("test");
        posizioneBDaCreare.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizioneBDaCreare, TipologiaOpzionePagamento.SOLUZIONE_UNICA, suffissoB);
        PosizioneDebitoria posizioneB = service.crea(DatiPosizioneDebitoria.da(posizioneBDaCreare));
        UUID idOpzioneDiB = posizioneB.getOpzioniPagamento().get(0).getIdOpzionePagamento();

        assertThatThrownBy(() -> service.aggiungiPendenza(codApplicazioneDi(posizioneA),
                posizioneA.getIdPosizioneDebitoria(), idOpzioneDiB,
                DatiPendenza.da(nuovaPendenzaStandalone(1L, "cross"))))
                .isInstanceOf(RisorsaNonTrovataException.class);
    }

    @Test
    @DisplayName("crea rifiuta con RisorsaGiaEsistenteException (non 500) un idPendenza gia' "
            + "usato da un'altra posizione della stessa applicazione")
    void creaRifiutaIdPendenzaGiaUsatoDaAltraPosizione() {
        PosizioneDebitoria prima = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        String idPendenzaEsistente = prima.getOpzioniPagamento().get(0).getPendenze().get(0).getIdPendenza();

        PosizioneDebitoria seconda = new PosizioneDebitoria();
        seconda.setIdApplicazione(prima.getIdApplicazione());
        seconda.setIdPosizioneDebitoria("pos-id-pendenza-duplicato");
        seconda.setIdDominio(1L);
        seconda.setDescrizione("test");
        seconda.addSoggettoDebitore(soggettoDiProva());
        OpzionePagamento opzione = opzioneConPendenza(seconda, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "altra");
        opzione.getPendenze().get(0).setIdPendenza(idPendenzaEsistente);

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(seconda)))
                .isInstanceOf(RisorsaGiaEsistenteException.class)
                .hasMessageContaining("idPendenza");
    }

    /**
     * Opzione autonoma, NON ancora collegata a nessuna posizione (a differenza di
     * {@link #opzioneConPendenza(PosizioneDebitoria, TipologiaOpzionePagamento, String)}): il
     * chiamante di {@code aggiungiOpzionePagamento} costruisce l'opzione dentro la funzione
     * passata al servizio, prima che sia collegata all'aggregato reale.
     */
    private OpzionePagamento nuovaOpzioneStandalone(Long idApplicazione, TipologiaOpzionePagamento tipologia,
            String suffisso) {
        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(tipologia);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdApplicazione(idApplicazione);
        pendenza.setIdPendenza("pendenza-" + suffisso);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setImporto(10.00);
        String cifraUnica = String.valueOf((Math.abs(suffisso.hashCode()) % 9) + 1);
        pendenza.setNumeroAvviso("4000000000000000" + cifraUnica);
        pendenza.setIuv("4000000000000000" + cifraUnica);
        pendenza.setSrcIuv(pendenza.getIuv());
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + suffisso);
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        pendenza.addVocePendenza(voce);

        return opzione;
    }

    /** Come {@link #nuovaOpzioneStandalone}, ma solo la pendenza (per {@code aggiungiPendenza}). */
    private Pendenza nuovaPendenzaStandalone(Long idApplicazione, String suffisso) {
        Pendenza pendenza = new Pendenza();
        pendenza.setIdApplicazione(idApplicazione);
        pendenza.setIdPendenza("pendenza-" + suffisso);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setImporto(10.00);
        String cifraUnica = String.valueOf((Math.abs(suffisso.hashCode()) % 9) + 1);
        pendenza.setNumeroAvviso("5000000000000000" + cifraUnica);
        pendenza.setIuv("5000000000000000" + cifraUnica);
        pendenza.setSrcIuv(pendenza.getIuv());
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + suffisso);
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        pendenza.addVocePendenza(voce);

        return pendenza;
    }

    private String codApplicazioneDi(PosizioneDebitoria posizione) {
        return applicazioni.entrySet().stream()
                .filter(e -> e.getValue().equals(posizione.getIdApplicazione()))
                .map(Map.Entry::getKey)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("cercaPerDebitore trova tutte le posizioni dello stesso gestionale con quel soggetto, "
            + "non solo quelle in cui e' il primo debitore")
    void cercaPerDebitoreTrovaLePosizioniConQuelSoggetto() {
        PosizioneDebitoria posizioneA = new PosizioneDebitoria();
        posizioneA.setIdApplicazione(idApplicazionePer("A2A-CERCA-DEBITORE"));
        posizioneA.setIdPosizioneDebitoria("pos-cerca-1");
        posizioneA.setIdDominio(1L);
        posizioneA.setDescrizione("test");
        posizioneA.addSoggettoDebitore(soggettoDiProva2()); // primo debitore diverso
        posizioneA.addSoggettoDebitore(soggettoDiProva()); // debitore cercato, non il primo
        opzioneConPendenza(posizioneA, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1");
        service.crea(DatiPosizioneDebitoria.da(posizioneA));

        PosizioneDebitoria posizioneB = new PosizioneDebitoria();
        posizioneB.setIdApplicazione(idApplicazionePer("A2A-CERCA-DEBITORE"));
        posizioneB.setIdPosizioneDebitoria("pos-cerca-2");
        posizioneB.setIdDominio(1L);
        posizioneB.setDescrizione("test");
        posizioneB.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizioneB, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "2");
        service.crea(DatiPosizioneDebitoria.da(posizioneB));

        PosizioneDebitoria posizioneAltroDebitore = new PosizioneDebitoria();
        posizioneAltroDebitore.setIdApplicazione(idApplicazionePer("A2A-CERCA-DEBITORE"));
        posizioneAltroDebitore.setIdPosizioneDebitoria("pos-cerca-3");
        posizioneAltroDebitore.setIdDominio(1L);
        posizioneAltroDebitore.setDescrizione("test");
        posizioneAltroDebitore.addSoggettoDebitore(soggettoDiProva2());
        opzioneConPendenza(posizioneAltroDebitore, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "3");
        service.crea(DatiPosizioneDebitoria.da(posizioneAltroDebitore));

        PaginaRisultati<PosizioneDebitoria> risultato = service.cercaPerDebitore("A2A-CERCA-DEBITORE",
                "RSSMRA80A01H501U", OffsetPageRequest.of(0, 10));

        assertThat(risultato.numeroRisultatiTotali()).isEqualTo(2);
        assertThat(risultato.risultati()).extracting(PosizioneDebitoria::getIdPosizioneDebitoria)
                .containsExactlyInAnyOrder("pos-cerca-1", "pos-cerca-2");
    }

    @Test
    @DisplayName("cercaPerDebitore rispetta offset e limit (scorrimento libero, non a pagine allineate)")
    void cercaPerDebitoreRispettaOffsetELimit() {
        for (int i = 1; i <= 3; i++) {
            PosizioneDebitoria posizione = new PosizioneDebitoria();
            posizione.setIdApplicazione(idApplicazionePer("A2A-PAGINAZIONE"));
            posizione.setIdPosizioneDebitoria("pos-pag-" + i);
            posizione.setIdDominio(1L);
            posizione.setDescrizione("test");
            posizione.addSoggettoDebitore(soggettoDiProva());
            opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA, String.valueOf(i));
            service.crea(DatiPosizioneDebitoria.da(posizione));
        }

        PaginaRisultati<PosizioneDebitoria> primaPagina = service.cercaPerDebitore("A2A-PAGINAZIONE",
                "RSSMRA80A01H501U", OffsetPageRequest.of(0, 2));
        PaginaRisultati<PosizioneDebitoria> secondaPagina = service.cercaPerDebitore("A2A-PAGINAZIONE",
                "RSSMRA80A01H501U", OffsetPageRequest.of(2, 2));

        assertThat(primaPagina.numeroRisultatiTotali()).isEqualTo(3);
        assertThat(primaPagina.risultati()).hasSize(2);
        assertThat(primaPagina.haAltriRisultati()).isTrue();
        assertThat(secondaPagina.risultati()).hasSize(1);
        assertThat(secondaPagina.haAltriRisultati()).isFalse();
    }

    @Test
    @DisplayName("haAltriRisultati() e' corretto anche con un offset non multiplo di limit — riproduce esattamente "
            + "il caso segnalato: 3 risultati totali, offset 1, limit 2, restituiti gli ultimi due")
    void cercaPerDebitoreHaAltriRisultatiConOffsetNonAllineato() {
        for (int i = 1; i <= 3; i++) {
            PosizioneDebitoria posizione = new PosizioneDebitoria();
            posizione.setIdApplicazione(idApplicazionePer("A2A-OFFSET-DISALLINEATO"));
            posizione.setIdPosizioneDebitoria("pos-off-" + i);
            posizione.setIdDominio(1L);
            posizione.setDescrizione("test");
            posizione.addSoggettoDebitore(soggettoDiProva());
            opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA, String.valueOf(i));
            service.crea(DatiPosizioneDebitoria.da(posizione));
        }

        PaginaRisultati<PosizioneDebitoria> pagina = service.cercaPerDebitore("A2A-OFFSET-DISALLINEATO",
                "RSSMRA80A01H501U", OffsetPageRequest.of(1, 2));

        assertThat(pagina.numeroRisultatiTotali()).isEqualTo(3);
        assertThat(pagina.risultati()).hasSize(2);
        assertThat(pagina.haAltriRisultati()).isFalse();
    }

    @Test
    @DisplayName("cercaPendenze senza idDominio trova pendenze con lo stesso numeroAvviso su domini diversi (M13)")
    void cercaPendenzeSenzaIdDominioTrovaSuDominiDiversi() {
        PosizioneDebitoria posizioneDominio1 = new PosizioneDebitoria();
        posizioneDominio1.setIdApplicazione(idApplicazionePer("A2A-CERCA-NAV"));
        posizioneDominio1.setIdPosizioneDebitoria("pos-nav-dominio1");
        posizioneDominio1.setIdDominio(1L);
        posizioneDominio1.setDescrizione("test");
        posizioneDominio1.addSoggettoDebitore(soggettoDiProva());
        Pendenza pendenzaDominio1 = opzioneConPendenza(posizioneDominio1, TipologiaOpzionePagamento.SOLUZIONE_UNICA,
                "nav-1").getPendenze().get(0);
        pendenzaDominio1.setNumeroAvviso("300000000000000001");
        pendenzaDominio1.setIuv("300000000000000001");
        service.crea(DatiPosizioneDebitoria.da(posizioneDominio1));

        PosizioneDebitoria posizioneDominio2 = new PosizioneDebitoria();
        posizioneDominio2.setIdApplicazione(idApplicazionePer("A2A-CERCA-NAV"));
        posizioneDominio2.setIdPosizioneDebitoria("pos-nav-dominio2");
        posizioneDominio2.setIdDominio(2L);
        posizioneDominio2.setDescrizione("test");
        posizioneDominio2.addSoggettoDebitore(soggettoDiProva());
        Pendenza pendenzaDominio2 = opzioneConPendenza(posizioneDominio2, TipologiaOpzionePagamento.SOLUZIONE_UNICA,
                "nav-2").getPendenze().get(0);
        pendenzaDominio2.setNumeroAvviso("300000000000000001"); // stesso NAV, dominio diverso: M13
        pendenzaDominio2.setIuv("300000000000000002");
        service.crea(DatiPosizioneDebitoria.da(posizioneDominio2));

        PaginaRisultati<Pendenza> senzaFiltroDominio = service.cercaPendenze("A2A-CERCA-NAV", "300000000000000001",
                null, OffsetPageRequest.of(0, 10));
        PaginaRisultati<Pendenza> conFiltroDominio = service.cercaPendenze("A2A-CERCA-NAV", "300000000000000001", 2L,
                OffsetPageRequest.of(0, 10));

        assertThat(senzaFiltroDominio.numeroRisultatiTotali()).isEqualTo(2);
        assertThat(conFiltroDominio.numeroRisultatiTotali()).isEqualTo(1);
        assertThat(conFiltroDominio.risultati().get(0).getIdDominio()).isEqualTo(2L);
    }

    @Test
    @DisplayName("trovaPerIdentificativo trova comunque una posizione con dataPubblicazione futura: lo YAML v3 "
            + "dice che una posizione non pubblicata resta invisibile solo per ricerca/pagamento ESTERNO "
            + "(Nodo dei Pagamenti), ma sempre visibile e gestibile per l'applicazione che l'ha creata — "
            + "decisione del lead, 2026-09-27, che corregge un filtro incondizionato introdotto il 2026-09-26: "
            + "ogni chiamante di questo metodo e' sempre l'applicazione proprietaria (mai un consumatore "
            + "realmente esterno), quindi filtrare qui era sbagliato — vedi Javadoc di "
            + "PosizioneDebitoriaRepository#findByIdApplicazioneAndIdPosizioneDebitoria")
    void trovaPerIdentificativoTrovaPosizioneAncheSeNonAncoraPubblicata() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazionePer("A2A-NON-PUBBLICATA"));
        posizione.setIdPosizioneDebitoria("pos-non-pubblicata");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.setDataPubblicazione(LocalDate.now().plusDays(30));
        posizione.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1");
        service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(service.trovaPerIdentificativo("A2A-NON-PUBBLICATA", "pos-non-pubblicata")).isPresent();
    }

    @Test
    @DisplayName("trovaPendenzaPerIdentificativo trova la pendenza per idPendenza+idA2A")
    void trovaPendenzaPerIdentificativoTrovaLaPendenza() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        String idPendenza = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getIdPendenza();

        java.util.Optional<Pendenza> trovata = service.trovaPendenzaPerIdentificativo(codApplicazioneDi(posizione),
                idPendenza);

        assertThat(trovata).isPresent();
        assertThat(trovata.get().getIdPendenza()).isEqualTo(idPendenza);
    }

    @Test
    @DisplayName("trovaPendenzaPerIdentificativo non trova nulla per idA2A inesistente o idPendenza sbagliato")
    void trovaPendenzaPerIdentificativoNonTrovaSeIdentificativoSbagliato() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        String idPendenza = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getIdPendenza();

        assertThat(service.trovaPendenzaPerIdentificativo("A2A-INESISTENTE", idPendenza)).isEmpty();
        assertThat(service.trovaPendenzaPerIdentificativo(codApplicazioneDi(posizione), "pendenza-inesistente"))
                .isEmpty();
    }

    @Test
    @DisplayName("cercaPerDebitore trova comunque una posizione con dataPubblicazione futura — stessa "
            + "decisione del lead, 2026-09-27, vedi trovaPerIdentificativoTrovaPosizioneAncheSeNonAncoraPubblicata")
    void cercaPerDebitoreTrovaPosizioneAncheSeNonAncoraPubblicata() {
        PosizioneDebitoria nonPubblicata = new PosizioneDebitoria();
        nonPubblicata.setIdApplicazione(idApplicazionePer("A2A-CERCA-PUBBLICAZIONE"));
        nonPubblicata.setIdPosizioneDebitoria("pos-cp-non-pubblicata");
        nonPubblicata.setIdDominio(1L);
        nonPubblicata.setDescrizione("test");
        nonPubblicata.setDataPubblicazione(LocalDate.now().plusDays(30));
        nonPubblicata.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(nonPubblicata, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1");
        service.crea(DatiPosizioneDebitoria.da(nonPubblicata));

        PaginaRisultati<PosizioneDebitoria> risultato = service.cercaPerDebitore("A2A-CERCA-PUBBLICAZIONE",
                "RSSMRA80A01H501U", OffsetPageRequest.of(0, 10));

        assertThat(risultato.numeroRisultatiTotali()).isEqualTo(1);
        assertThat(risultato.risultati()).extracting(PosizioneDebitoria::getIdPosizioneDebitoria)
                .containsExactly("pos-cp-non-pubblicata");
    }

    @Test
    @DisplayName("cercaPendenze trova comunque le pendenze la cui posizione non e' ancora pubblicata — stessa "
            + "decisione del lead, 2026-09-27, vedi trovaPerIdentificativoTrovaPosizioneAncheSeNonAncoraPubblicata")
    void cercaPendenzeTrovaPendenzaAncheSePosizioneNonAncoraPubblicata() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazionePer("A2A-CERCA-PENDENZE-PUBBLICAZIONE"));
        posizione.setIdPosizioneDebitoria("pos-cpp-non-pubblicata");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.setDataPubblicazione(LocalDate.now().plusDays(30));
        posizione.addSoggettoDebitore(soggettoDiProva());
        OpzionePagamento opzione = opzioneConPendenza(posizione, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1");
        String numeroAvviso = opzione.getPendenze().get(0).getNumeroAvviso();
        service.crea(DatiPosizioneDebitoria.da(posizione));

        PaginaRisultati<Pendenza> risultato = service.cercaPendenze("A2A-CERCA-PENDENZE-PUBBLICAZIONE", numeroAvviso,
                null, OffsetPageRequest.of(0, 10));

        assertThat(risultato.numeroRisultatiTotali()).isEqualTo(1);
    }

    @Test
    @DisplayName("cercaPendenze esclude una pendenza priva di opzionePagamento (creata da v2/migrazione) sia dai "
            + "risultati sia dal conteggio — bug del lead, 2026-09-28: un primo tentativo la faceva trovare da "
            + "cercaPendenze e la filtrava solo nel controller REST, DOPO che la Page era gia' stata paginata e "
            + "contata dal DB. Con dati misti questo produceva pagine incomplete o numRisultati disallineato dal "
            + "numero di elementi restituiti (es. numRisultati: 1, risultati: [] con una sola pendenza v2 in "
            + "pagina) — il filtro deve stare nella query, non dopo")
    void cercaPendenzeEscludeLaPendenzaSenzaOpzionePagamentoDaRisultatiENumeroTotale() {
        Long idApplicazione = idApplicazionePer("A2A-PENDENZA-V2");

        Pendenza pendenzaV2 = new Pendenza();
        pendenzaV2.setIdApplicazione(idApplicazione);
        pendenzaV2.setIdDominio(1L);
        pendenzaV2.setIdPendenza("pendenza-v2");
        pendenzaV2.setIdTipoPendenza(1L);
        pendenzaV2.setIdTipoVersamento(1L);
        pendenzaV2.setImporto(10.00);
        pendenzaV2.setNumeroAvviso("300000000000000v2");
        pendenzaV2.setDataCreazione(java.time.OffsetDateTime.now());
        pendenzaV2.setDataUltimoAggiornamento(java.time.OffsetDateTime.now());
        em.persistAndFlush(pendenzaV2);

        PaginaRisultati<Pendenza> risultato = service.cercaPendenze("A2A-PENDENZA-V2", "300000000000000v2", null,
                OffsetPageRequest.of(0, 10));

        assertThat(risultato.numeroRisultatiTotali()).isEqualTo(0);
        assertThat(risultato.risultati()).isEmpty();
    }

    @Test
    @DisplayName("trovaPendenzaPerIdentificativo esclude una pendenza priva di opzionePagamento "
            + "(v2/migrazione) — bug gemello di cercaPendenze, stessa causa: il mapper della REST "
            + "API non sa rappresentarla (IllegalStateException, tradotta in 500); deve valere "
            + "come non trovata (404), non un errore interno")
    void trovaPendenzaPerIdentificativoEsclideLaPendenzaSenzaOpzionePagamento() {
        Long idApplicazione = idApplicazionePer("A2A-PENDENZA-V2-GET");

        Pendenza pendenzaV2 = new Pendenza();
        pendenzaV2.setIdApplicazione(idApplicazione);
        pendenzaV2.setIdDominio(1L);
        pendenzaV2.setIdPendenza("pendenza-v2-get");
        pendenzaV2.setIdTipoPendenza(1L);
        pendenzaV2.setIdTipoVersamento(1L);
        pendenzaV2.setImporto(10.00);
        pendenzaV2.setNumeroAvviso("300000000000000v3");
        pendenzaV2.setDataCreazione(java.time.OffsetDateTime.now());
        pendenzaV2.setDataUltimoAggiornamento(java.time.OffsetDateTime.now());
        em.persistAndFlush(pendenzaV2);

        assertThat(service.trovaPendenzaPerIdentificativo("A2A-PENDENZA-V2-GET", "pendenza-v2-get")).isEmpty();
    }

    @Test
    @DisplayName("crea rifiuta un numeroAvviso gia' usato da un'altra pendenza dello stesso dominio "
            + "(controllo applicativo, replica VER_025 del legacy: versamenti non ha un vincolo UNIQUE per questo)")
    void creaRifiutaNumeroAvvisoDuplicatoNelloStessoDominio() {
        PosizioneDebitoria prima = new PosizioneDebitoria();
        prima.setIdApplicazione(idApplicazionePer("A2A-NAV-DUPLICATO"));
        prima.setIdPosizioneDebitoria("pos-nav-dup-1");
        prima.setIdDominio(1L);
        prima.setDescrizione("test");
        prima.addSoggettoDebitore(soggettoDiProva());
        Pendenza pendenzaPrima = opzioneConPendenza(prima, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1")
                .getPendenze().get(0);
        pendenzaPrima.setNumeroAvviso("300000000000000042");
        pendenzaPrima.setIuv("300000000000000042");
        service.crea(DatiPosizioneDebitoria.da(prima));

        PosizioneDebitoria seconda = new PosizioneDebitoria();
        seconda.setIdApplicazione(idApplicazionePer("A2A-NAV-DUPLICATO"));
        seconda.setIdPosizioneDebitoria("pos-nav-dup-2");
        seconda.setIdDominio(1L); // stesso dominio della prima
        seconda.setDescrizione("test");
        seconda.addSoggettoDebitore(soggettoDiProva());
        Pendenza pendenzaSeconda = opzioneConPendenza(seconda, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "2")
                .getPendenze().get(0);
        pendenzaSeconda.setNumeroAvviso("300000000000000042"); // stesso NAV, stesso dominio: rifiutato
        pendenzaSeconda.setIuv("300000000000000099");

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(seconda)))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("300000000000000042");
    }

    @Test
    @DisplayName("crea rifiuta una posizione con lo stesso idA2A+idPosizioneDebitoria di una gia' esistente, "
            + "anche con un dominio diverso (bug del lead, 2026-09-26: il vincolo DB reale su documenti include "
            + "anche id_dominio, ma la ricerca pubblica per identificativo e il 409 dello YAML v3 sono chiavati "
            + "solo su idA2A+idPosizioneDebitoria)")
    void creaRifiutaIdPosizioneDebitoriaDuplicatoAncheConDominioDiverso() {
        Long idApplicazione = idApplicazionePer("A2A-POS-DUPLICATA");

        PosizioneDebitoria prima = new PosizioneDebitoria();
        prima.setIdApplicazione(idApplicazione);
        prima.setIdPosizioneDebitoria("pos-duplicata");
        prima.setIdDominio(1L);
        prima.setDescrizione("test");
        prima.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(prima, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1");
        service.crea(DatiPosizioneDebitoria.da(prima));

        PosizioneDebitoria seconda = new PosizioneDebitoria();
        seconda.setIdApplicazione(idApplicazione);
        seconda.setIdPosizioneDebitoria("pos-duplicata"); // stesso idA2A+idPosizioneDebitoria
        seconda.setIdDominio(2L); // dominio diverso: il vincolo DB legacy lo lascerebbe passare
        seconda.setDescrizione("test");
        seconda.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(seconda, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "2");

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(seconda)))
                .isInstanceOf(RisorsaGiaEsistenteException.class)
                .hasMessageContaining("pos-duplicata");
    }

    @Test
    @DisplayName("crea non traduce la violazione di un NOT NULL in risorsa gia' esistente")
    void creaNonTraduceLaViolazioneDiUnVincoloDiverso() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setIdTipoVersamento(null);
        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(posizione)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .isNotInstanceOf(RisorsaGiaEsistenteException.class);
    }

    @Test
    @DisplayName("crea traduce in RisorsaGiaEsistenteException anche quando il duplicato rilevato solo al "
            + "flush ha lo stesso dominio del preesistente — bug del lead, 2026-09-27: in quel caso "
            + "viola SIA unique_documenti_applicazione SIA unique_documenti_1 (gia' nello schema legacy, "
            + "cod_documento+id_applicazione+id_dominio), e quale dei due il motore segnali non e' "
            + "deterministico lato applicativo — riconoscere solo il primo lasciava propagare un 500 "
            + "quando veniva segnalato il secondo. Simulata la race condition (existsBy... che non vede "
            + "ancora il duplicato) con uno spy, l'unico modo deterministico di riprodurla a un solo "
            + "thread — vedi Javadoc del campo")
    void creaTraduceLaViolazioneAncheSuUniqueDocumenti1QuandoIlDominioECoincidente() {
        Long idApplicazione = idApplicazionePer("A2A-RACE-STESSO-DOMINIO");

        PosizioneDebitoria prima = new PosizioneDebitoria();
        prima.setIdApplicazione(idApplicazione);
        prima.setIdPosizioneDebitoria("pos-race-stesso-dominio");
        prima.setIdDominio(1L);
        prima.setDescrizione("test");
        prima.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(prima, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "1");
        service.crea(DatiPosizioneDebitoria.da(prima));

        PosizioneDebitoria seconda = new PosizioneDebitoria();
        seconda.setIdApplicazione(idApplicazione);
        seconda.setIdPosizioneDebitoria("pos-race-stesso-dominio"); // stesso idA2A+idPosizioneDebitoria
        seconda.setIdDominio(1L); // stesso dominio della prima: viola anche unique_documenti_1
        seconda.setDescrizione("test");
        seconda.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(seconda, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "2");

        // Forza il controllo preliminare a non vedere il duplicato gia' presente, simulando
        // due richieste concorrenti che lo superano entrambe (vedi bug del 2026-09-26).
        Mockito.doReturn(false).when(posizioneDebitoriaRepository)
                .existsByIdApplicazioneAndIdPosizioneDebitoria(idApplicazione, "pos-race-stesso-dominio");

        assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(seconda)))
                .isInstanceOf(RisorsaGiaEsistenteException.class)
                .hasMessageContaining("pos-race-stesso-dominio");
    }

    @Test
    @DisplayName("crea non fallisce se il chiamante non valorizza affatto i campi debitore su Pendenza: "
            + "sono placeholder fissi (debitoreIdentificativo/debitoreAnagrafica/srcDebitoreIdentificativo), "
            + "non un dato funzionale richiesto al chiamante — bug del lead, 2026-09-26: "
            + "srcDebitoreIdentificativo era rimasto escluso dal placeholder introdotto per gli altri due")
    void creaValorizzaIPlaceholderDebitoreSenzaRichiederliAlChiamante() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazionePer("A2A-SENZA-CAMPI-DEBITORE"));
        posizione.setIdPosizioneDebitoria("pos-senza-campi-debitore");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.addSoggettoDebitore(soggettoDiProva());

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.addOpzionePagamento(opzione);

        // Costruita come farebbe il bean converter del livello API: nessun setDebitoreXxx/
        // setSrcDebitoreIdentificativo, quelli non fanno parte dello schema Pendenza dello
        // YAML v3 (il debitore vero sta su soggettiDebitori).
        Pendenza pendenza = new Pendenza();
        pendenza.setIdApplicazione(posizione.getIdApplicazione());
        pendenza.setIdPendenza("pendenza-senza-campi-debitore");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setImporto(10.00);
        pendenza.setNumeroAvviso("300000000000000077");
        pendenza.setIuv("300000000000000077");
        opzione.addPendenza(pendenza);

        pendenza.addVocePendenza(voceDiProva("voce-senza-campi-debitore", 10.00));

        PosizioneDebitoria creata = service.crea(DatiPosizioneDebitoria.da(posizione));
        Pendenza pendenzaCreata = creata.getOpzioniPagamento().get(0).getPendenze().get(0);

        assertThat(pendenzaCreata.getDebitoreIdentificativo()).isEqualTo("VEDERE_SOGGETTI_DEBITORI");
        assertThat(pendenzaCreata.getDebitoreAnagrafica()).isEqualTo("Vedere tabella soggetti_debitori");
        assertThat(pendenzaCreata.getSrcDebitoreIdentificativo()).isEqualTo("VEDERE_SOGGETTI_DEBITORI");
    }

    // ── aggiornaPendenza ─────────────────────────────────────────────────────

    @Test
    @DisplayName("aggiornaPendenza marca dataUltimoAggiornamento sulla pendenza e dataUltimaModificaAca "
            + "(bug: aggiorna() marcava solo la posizione/ACA, non il timestamp della pendenza stessa)")
    void aggiornaPendenzaMarcaDataUltimoAggiornamentoEAca() throws InterruptedException {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        azzeraMarcatureAca(posizione);
        OffsetDateTime originale = posizione.getOpzioniPagamento().get(0).getPendenze().get(0)
                .getDataUltimoAggiornamento();
        em.flush();
        em.clear();
        Thread.sleep(5);

        Pendenza aggiornata = aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
        });

        assertThat(aggiornata.getDataUltimoAggiornamento()).isAfter(originale);
        assertThat(aggiornata.getDataUltimaModificaAca()).isNotNull();
    }

    @Test
    @DisplayName("aggiornaPendenza rifiuta se lo stato non e' NON_ESEGUITO ne' ANNULLATO")
    void aggiornaPendenzaRifiutaStatoNonCompatibile() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setStato(StatoPendenza.ESEGUITO);

        assertThatThrownBy(() -> aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
        })).isInstanceOf(TransizioneStatoNonAmmessaException.class);
    }

    @Test
    @DisplayName("aggiornaPendenza solleva RisorsaNonTrovataException per una pendenza inesistente")
    void aggiornaPendenzaRisorsaInesistente() {
        assertThatThrownBy(() -> aggiornaPendenzaDaDati("A2A-INESISTENTE", "pendenza-inesistente", p -> {
        })).isInstanceOf(RisorsaNonTrovataException.class);
    }

    /**
     * A differenza del legacy ({@code VersamentoUtils.validazioneSemanticaAggiornamento},
     * {@code VER_005}/{@code VER_006}, che rifiuta sempre una riduzione) questo endpoint v3
     * ammette la rimozione di una voce esistente — richiesta esplicita, non un mirror.
     * Verificato con una chiamata diretta al service (nessun {@code PatchOp}/JSON), come
     * farebbe un futuro consumatore diretto della libreria (es. console-api).
     */
    @Test
    @DisplayName("aggiornaPendenza permette la rimozione di una voce esistente, purche' ne resti almeno una")
    void aggiornaPendenzaPermetteRimozioneVoceEsistente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        em.clear();

        aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
            VocePendenza extra = voceDiProva("voce-extra", 5.00);
            extra.setIndice(2);
            p.addVocePendenza(extra);
            p.setImporto(15.00);
        });
        em.flush();
        em.clear();

        Pendenza aggiornata = aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
            p.getVoci().removeIf(v -> "voce-1".equals(v.getIdVocePendenza()));
            p.setImporto(5.00);
        });

        assertThat(aggiornata.getVoci()).hasSize(1);
        assertThat(aggiornata.getVoci().get(0).getIdVocePendenza()).isEqualTo("voce-extra");
    }

    /**
     * Stesso scenario di {@link #aggiornaPendenzaPermetteRimozioneVoceEsistente}, ma la voce da
     * rimuovere e' gia' referenziata da una {@link Rendicontazione} (FK fisica
     * {@code idSingoloVersamento}, vedi Javadoc di {@link PosizioneDebitoriaService#aggiornaPendenza}):
     * rimuoverla attribuirebbe quello storico alla voce che ne riusasse la riga. Nessun
     * {@link it.govpay.pendenze.entity.Pagamento} e nessun cambio di stato della pendenza qui
     * (resta {@code NON_ESEGUITO}): il controllo non e' legato all'esecuzione del pagamento, una
     * rendicontazione anomala puo' arrivare anche senza.
     */
    @Test
    @DisplayName("aggiornaPendenza rifiuta la rimozione di una voce gia' referenziata da una rendicontazione, "
            + "anche senza alcun pagamento associato e con la pendenza ancora NON_ESEGUITO")
    void aggiornaPendenzaRifiutaRimozioneVoceGiaRendicontata() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        Long idVoce1 = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().get(0).getId();
        String iuvPendenza = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getIuv();
        em.flush();
        em.clear();

        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(1L);
        flusso.setCodDominio("DOM-REND-VOCE");
        flusso.setCodFlusso("flusso-rend-voce");
        flusso.setDataOraFlusso(OffsetDateTime.now());
        flusso.setIur("flusso-iur-rend-voce");
        flusso.setDataAcquisizione(OffsetDateTime.now());
        flusso.setDataRegolamento(OffsetDateTime.now());
        flusso.setCodPsp("PSP-1");
        flusso.setNumeroPagamenti(1L);
        flusso.setImportoTotale(10.00);
        flusso.setStato(StatoFlussoRendicontazione.ACCETTATA);
        flusso.setRevisione(1L);
        flusso.setObsoleto(false);
        em.persistAndFlush(flusso);

        Rendicontazione rendicontazione = new Rendicontazione();
        rendicontazione.setFlusso(flusso);
        rendicontazione.setIuv(iuvPendenza);
        rendicontazione.setIur("iur-rend-voce");
        rendicontazione.setImportoPagato(10.00);
        rendicontazione.setEsito(0);
        rendicontazione.setData(OffsetDateTime.now());
        rendicontazione.setStato(StatoRendicontazione.OK);
        rendicontazione.setIdSingoloVersamento(idVoce1);
        em.persistAndFlush(rendicontazione);
        em.clear();

        aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
            VocePendenza extra = voceDiProva("voce-extra", 5.00);
            extra.setIndice(2);
            p.addVocePendenza(extra);
            p.setImporto(15.00);
        });
        em.flush();
        em.clear();

        assertThatThrownBy(() -> aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
            p.getVoci().removeIf(v -> "voce-1".equals(v.getIdVocePendenza()));
            p.setImporto(5.00);
        }))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class)
                .hasMessageContaining("rendicontazione");
    }

    /**
     * Il minimo di una voce residua non e' verificato dal confronto prima/dopo di
     * {@code aggiornaPendenza} (vedi test sopra), ma resta comunque garantito a valle da
     * {@code ValidatorePosizioneDebitoria.validaPendenza} (cardinalita' 1-5), eseguito da
     * {@link PosizioneDebitoriaService#aggiorna} prima del salvataggio.
     */
    @Test
    @DisplayName("aggiornaPendenza rifiuta comunque la rimozione dell'ultima voce residua "
            + "(cardinalita' minima, garantita dal validatore di libreria)")
    void aggiornaPendenzaRifiutaRimozioneUltimaVoce() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
            p.getVoci().clear();
            p.setImporto(0.00);
        })).isInstanceOf(ValidazioneNonSuperataException.class);
    }

    @Test
    @DisplayName("aggiornaPendenza rifiuta il cambio di tributo di una voce esistente, anche chiamando il "
            + "service direttamente")
    void aggiornaPendenzaRifiutaCambioTributoVoceEsistente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1",
                p -> p.getVoci().get(0).setIdTributo(99L)))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class)
                .hasMessageContaining("non puo' cambiare il riferimento");
    }

    /**
     * Stesso controllo gia' presente in {@code PosizioneDebitoriaMapper#applicaPutPendenza}
     * (mirror VER_024), qui duplicato nel service perche' valga anche per un chiamante diretto
     * della libreria, non solo per il percorso REST — vedi Javadoc di
     * {@link PosizioneDebitoriaService#aggiornaPendenza}.
     */
    @Test
    @DisplayName("aggiornaPendenza rifiuta il cambio di numeroAvviso gia' assegnato, anche chiamando il "
            + "service direttamente")
    void aggiornaPendenzaRifiutaCambioNumeroAvvisoGiaAssegnato() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        String numeroAvvisoOriginale = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso();
        em.flush();
        em.clear();

        assertThatThrownBy(() -> aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1",
                p -> p.setNumeroAvviso("999999999999999999")))
                .isInstanceOf(TransizioneStatoNonAmmessaException.class)
                .hasMessageContaining("numeroAvviso")
                .hasMessageContaining(numeroAvvisoOriginale);
    }

    /**
     * Il confronto prima/dopo e' indicizzato per {@code idVocePendenza}, non per l'{@code id}
     * di database (vedi Javadoc di {@link PosizioneDebitoriaService#aggiornaPendenza}): se
     * fosse indicizzato per {@code id}, questo test fallirebbe, perche' qui si muta la STESSA
     * istanza gestita (stesso {@code id} di database) facendole cambiare sia
     * {@code idVocePendenza} sia {@code idTributo} — esattamente quello che fa
     * {@code PosizioneDebitoriaMapper#sostituisciVoci} in govpay-pendenze-api per riusare la
     * riga di una voce rimossa invece di una coppia DELETE+INSERT (necessario per evitare una
     * collisione transitoria sul vincolo di unicita' DB, visto che Hibernate esegue gli insert
     * prima dei delete nello stesso flush). Dal punto di vista di idVocePendenza questo e' a
     * tutti gli effetti "la vecchia voce rimossa, una nuova voce aggiunta" — ammesso.
     */
    @Test
    @DisplayName("aggiornaPendenza permette di riusare la stessa voce gestita per un'identita' diversa "
            + "(stesso id di database, idVocePendenza e tributo diversi) — e' rimozione+aggiunta, non un "
            + "cambio di riferimento sulla stessa voce")
    void aggiornaPendenzaPermetteRiusoDellaStessaVocePerUnaIdentitaDiversa() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        em.clear();

        Pendenza aggiornata = aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
            VocePendenza riusata = p.getVoci().get(0);
            riusata.setIdVocePendenza("voce-riusata");
            riusata.setIdTributo(99L);
        });

        assertThat(aggiornata.getVoci()).hasSize(1);
        assertThat(aggiornata.getVoci().get(0).getIdVocePendenza()).isEqualTo("voce-riusata");
        assertThat(aggiornata.getVoci().get(0).getIdTributo()).isEqualTo(99L);
    }

    /**
     * Verifica che il limite sia garantito dal validatore di libreria, non solo dalla bean
     * validation del DTO REST: nessun {@code PatchOp}/JSON qui, solo una chiamata diretta a
     * {@code aggiornaPendenza} con importi negativi su voce e pendenza, somma coerente tra
     * loro — esattamente il caso che il solo controllo di coerenza (somma voci = importo)
     * lascia passare, perche' due importi negativi possono comunque somare correttamente.
     */
    @Test
    @DisplayName("aggiornaPendenza rifiuta importi negativi su voce/pendenza anche con somma coerente, "
            + "chiamando il service direttamente (non solo tramite il mapper REST)")
    void aggiornaPendenzaRifiutaImportiNegativiAncheConSommaCoerente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
            p.getVoci().get(0).setImporto(-10.00);
            p.setImporto(-10.00);
        })).isInstanceOf(ValidazioneNonSuperataException.class);
    }

    @Test
    @DisplayName("aggiornaPendenza permette di aggiungere una voce nuova senza toccare quelle esistenti")
    void aggiornaPendenzaPermetteAggiuntaVoceNuova() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        em.flush();
        em.clear();

        Pendenza aggiornata = aggiornaPendenzaDaDati(codApplicazioneDi(posizione), "pendenza-1", p -> {
            VocePendenza nuova = voceDiProva("voce-nuova", 5.00);
            nuova.setIndice(2);
            p.addVocePendenza(nuova);
            p.setImporto(15.00);
        });

        assertThat(aggiornata.getVoci()).hasSize(2);
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private VocePendenza voceDiProva(String idVocePendenza, double importo) {
        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza(idVocePendenza);
        voce.setImporto(importo);
        voce.setDescrizione("test");
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        return voce;
    }

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
        posizione.setIdApplicazione(idApplicazionePer("A2A-" + suffisso));
        posizione.setIdPosizioneDebitoria("pos-" + suffisso);
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizione, tipologia, "1");
        return posizione;
    }

    private SoggettoDebitore soggettoDiProva() {
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        soggetto.setAnagrafica("Mario Rossi");
        return soggetto;
    }

    private SoggettoDebitore soggettoDiProva2() {
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("VRDGNN80A01H501W");
        soggetto.setAnagrafica("Giovanna Verdi");
        return soggetto;
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
        pendenza.setIdApplicazione(opzione.getPosizioneDebitoria().getIdApplicazione());
        pendenza.setIdPendenza("pendenza-" + suffisso);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setImporto(10.00);
        pendenza.setNumeroAvviso("30000000000000000" + suffisso);
        pendenza.setIuv("30000000000000000" + suffisso);
        pendenza.setSrcIuv("30000000000000000" + suffisso);
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + suffisso);
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        pendenza.addVocePendenza(voce);

        return pendenza;
    }

    @Test
    @DisplayName("i comandi di creazione copiano i dati e non importano stati, UUID o riferimenti JPA")
    void creaDaComandoIsolatoDallaFixture() {
        PosizioneDebitoria sorgente = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        OpzionePagamento opzione = sorgente.getOpzioniPagamento().get(0);
        Pendenza pendenza = opzione.getPendenze().get(0);
        opzione.setStato(StatoOpzionePagamento.ATTIVATA);
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        pendenza.setStato(StatoPendenza.ESEGUITO);
        pendenza.getVoci().get(0).setStato(StatoVocePendenza.ESEGUITO);
        DatiPosizioneDebitoria comando = DatiPosizioneDebitoria.da(sorgente);
        pendenza.setImporto(123.00);
        pendenza.getVoci().clear();

        PosizioneDebitoria creata = service.crea(comando);
        OpzionePagamento salvata = creata.getOpzioniPagamento().get(0);
        assertThat(sorgente.getId()).isNull();
        assertThat(salvata.getIdOpzionePagamento()).isNotEqualTo(opzione.getIdOpzionePagamento());
        assertThat(salvata.getStato()).isEqualTo(StatoOpzionePagamento.DISPONIBILE);
        assertThat(salvata.getPendenze().get(0).getStato()).isEqualTo(StatoPendenza.NON_ESEGUITO);
        assertThat(salvata.getPendenze().get(0).getImporto()).isEqualTo(10.00);
        assertThat(salvata.getPendenze().get(0).getVoci()).hasSize(1);
        assertThat(salvata.getPendenze().get(0).getVoci().get(0).getStato())
                .isEqualTo(StatoVocePendenza.NON_ESEGUITO);
    }

    @Test
    void aggiunteInizializzanoStatiEApplicazioneDalProprietario() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(
                posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        OpzionePagamento datiOpzione = nuovaOpzioneStandalone(999L, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "nuova");
        datiOpzione.setStato(StatoOpzionePagamento.ANNULLATA);
        datiOpzione.getPendenze().get(0).setStato(StatoPendenza.ANNULLATO);
        OpzionePagamento aggiunta = service.aggiungiOpzionePagamento(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), DatiOpzionePagamento.da(datiOpzione));
        Pendenza datiPendenza = nuovaPendenzaStandalone(999L, "seconda");
        datiPendenza.setStato(StatoPendenza.ESEGUITO);
        Pendenza pendenza = service.aggiungiPendenza(codApplicazioneDi(posizione),
                posizione.getIdPosizioneDebitoria(), aggiunta.getIdOpzionePagamento(), DatiPendenza.da(datiPendenza));
        assertThat(aggiunta.getStato()).isEqualTo(StatoOpzionePagamento.DISPONIBILE);
        assertThat(aggiunta.getPendenze()).allSatisfy(p -> {
            assertThat(p.getStato()).isEqualTo(StatoPendenza.NON_ESEGUITO);
            assertThat(p.getIdApplicazione()).isEqualTo(posizione.getIdApplicazione());
        });
        assertThat(pendenza.getIdDominio()).isEqualTo(posizione.getIdDominio());
    }

    @Test
    void aggiornamentoParzialeDistingueAssenzaERimozione() {
        PosizioneDebitoria sorgente = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        LocalDate data = LocalDate.of(2026, 10, 1);
        sorgente.setDataPubblicazione(data);
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(sorgente));
        service.aggiorna(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                new AggiornamentoPosizioneDebitoria(ModificaCampo.imposta("nuova descrizione"),
                        ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato(),
                        ModificaCampo.invariato()));
        assertThat(posizione.getDataPubblicazione()).isEqualTo(data);
        service.aggiorna(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                new AggiornamentoPosizioneDebitoria(ModificaCampo.invariato(), ModificaCampo.imposta(null),
                        ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato()));
        assertThat(posizione.getDataPubblicazione()).isNull();
        assertThat(posizione.getDescrizione()).isEqualTo("nuova descrizione");
    }

    @Test
    void aggiornamentoRifiutaUnoStatoPersistitoIncoerente() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(
                posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        // Simula dati gia' disallineati, scritti fuori dal service.
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setStato(StatoPendenza.ESEGUITO);
        em.flush();
        assertThatThrownBy(() -> service.aggiorna(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                AggiornamentoPosizioneDebitoria.nessunaModifica()))
                .isInstanceOf(ValidazioneNonSuperataException.class).hasMessageContaining("stato");
    }

    @Test
    void putMantieneStatoIdentitaEIuvEDerivaIlMergeNellaLibreria() {
        PosizioneDebitoria posizione = service.crea(DatiPosizioneDebitoria.da(
                posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA)));
        service.annulla(codApplicazioneDi(posizione), posizione.getIdPosizioneDebitoria(),
                posizione.getOpzioniPagamento().get(0).getIdOpzionePagamento());
        Pendenza originale = posizione.getOpzioniPagamento().get(0).getPendenze().get(0);
        Long id = originale.getId();
        Long idVoce = originale.getVoci().get(0).getId();
        String iuv = originale.getIuv();
        String nav = originale.getNumeroAvviso();
        Pendenza dati = ApplicatoreComandi.pendenza(DatiPendenza.da(originale));
        dati.setNumeroAvviso(null); // omissione nel PUT conserva quello gia' assegnato
        dati.setStato(StatoPendenza.ESEGUITO); // non fa parte del comando
        dati.setIuv("non-scrivibile"); // non fa parte del comando
        dati.getVoci().get(0).setDescrizione("aggiornata");
        Pendenza aggiornata = service.aggiornaPendenza(codApplicazioneDi(posizione), originale.getIdPendenza(),
                AggiornamentoPendenza.da(dati));
        assertThat(aggiornata.getId()).isEqualTo(id);
        assertThat(aggiornata.getIuv()).isEqualTo(iuv);
        assertThat(aggiornata.getNumeroAvviso()).isEqualTo(nav);
        assertThat(aggiornata.getStato()).isEqualTo(StatoPendenza.ANNULLATO);
        assertThat(aggiornata.getOpzionePagamento().getStato()).isEqualTo(StatoOpzionePagamento.ANNULLATA);
        assertThat(aggiornata.getVoci().get(0).getId()).isEqualTo(idVoce);
        assertThat(aggiornata.getVoci().get(0).getDescrizione()).isEqualTo("aggiornata");
    }

    private static AggiornamentoPosizioneDebitoria conSoggetti(java.util.List<SoggettoDebitore> soggetti) {
        return new AggiornamentoPosizioneDebitoria(ModificaCampo.invariato(), ModificaCampo.invariato(),
                ModificaCampo.invariato(), ModificaCampo.invariato(),
                ModificaCampo.imposta(soggetti.stream().map(DatiSoggettoDebitore::da).toList()));
    }

    /** Prepara un comando da una COPIA non gestita: il service non riceve callback o entity. */
    private Pendenza aggiornaPendenzaDaDati(String idA2A, String idPendenza,
            java.util.function.Consumer<Pendenza> preparaDati) {
        Pendenza copia = service.trovaPendenzaPerIdentificativo(idA2A, idPendenza)
                .map(p -> ApplicatoreComandi.pendenza(DatiPendenza.da(p)))
                .orElseGet(Pendenza::new);
        preparaDati.accept(copia);
        return service.aggiornaPendenza(idA2A, idPendenza, AggiornamentoPendenza.da(copia));
    }
}
