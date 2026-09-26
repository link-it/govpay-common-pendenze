package it.govpay.pendenze.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

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
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.RisorsaGiaEsistenteException;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
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
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DominioRepository.class))
@ActiveProfiles("test")
class PosizioneDebitoriaServiceTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PosizioneDebitoriaService service;

    private final Map<String, Long> applicazioni = new HashMap<>();

    private Long idApplicazionePer(String codApplicazione) {
        return applicazioni.computeIfAbsent(codApplicazione, cod -> {
            ApplicazioneEntity applicazione = ApplicazioneEntity.builder()
                    .codApplicazione(cod).autoIuv(true).firmaRicevuta("N").trusted(true).build();
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
        posizione.setIdApplicazione(idApplicazionePer("A2A-1"));
        posizione.setIdPosizioneDebitoria("pos-1");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        posizione.addSoggettoDebitore(soggettoDiProva());

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
    @DisplayName("crea rifiuta una pendenza priva di IUV/numero avviso se nessun GeneratoreIuv e' configurato")
    void creaRifiutaSenzaGeneratoreIuv() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setIuv(null);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setNumeroAvviso(null);

        assertThatThrownBy(() -> service.crea(posizione)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("crea rifiuta una pendenza con solo numeroAvviso valorizzato se nessun GeneratoreIuv "
            + "e' configurato per ricavarne lo iuv")
    void creaRifiutaSoloNumeroAvvisoSenzaGeneratoreIuv() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        String numeroAvvisoFornito = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso();
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setIuv(null);

        assertThatThrownBy(() -> service.crea(posizione))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(numeroAvvisoFornito);
    }

    @Test
    @DisplayName("crea rifiuta una pendenza con solo iuv valorizzato (senza numeroAvviso)")
    void creaRifiutaSoloIuv() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.getOpzioniPagamento().get(0).getPendenze().get(0).setNumeroAvviso(null);

        assertThatThrownBy(() -> service.crea(posizione)).isInstanceOf(ValidazioneNonSuperataException.class);
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

        service.crea(posizione);

        assertThat(posizione.getSoggettiDebitori()).extracting(SoggettoDebitore::getOrdine)
                .containsExactly(0, 1);
        assertThat(opzione.getPendenze()).extracting(Pendenza::getNumeroRata)
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

        service.crea(posizione);

        assertThat(pendenza.getVoci()).extracting(VocePendenza::getIndice).containsExactly(1, 2);
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

        assertThatThrownBy(() -> service.crea(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("navNotifica");
    }

    @Test
    @DisplayName("crea rifiuta navNotifica che non corrisponde al numeroAvviso di alcuna pendenza")
    void creaRifiutaNavNotificaNonCorrispondente() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.setNavNotifica("999999999999999999");

        assertThatThrownBy(() -> service.crea(posizione))
                .isInstanceOf(ValidazioneNonSuperataException.class)
                .hasMessageContaining("navNotifica");
    }

    @Test
    @DisplayName("crea assegna automaticamente navNotifica se notificaSend e' attivo e la posizione ha una sola pendenza")
    void creaAssegnaNavNotificaAutomaticamente() {
        PosizioneDebitoria posizione = posizioneConUnaOpzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.setNotificaSend(true);
        String numeroAvviso = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso();

        service.crea(posizione);

        assertThat(posizione.getNavNotifica()).isEqualTo(numeroAvviso);
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
        service.crea(posizioneA);

        PosizioneDebitoria posizioneB = new PosizioneDebitoria();
        posizioneB.setIdApplicazione(idApplicazionePer("A2A-CERCA-DEBITORE"));
        posizioneB.setIdPosizioneDebitoria("pos-cerca-2");
        posizioneB.setIdDominio(1L);
        posizioneB.setDescrizione("test");
        posizioneB.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(posizioneB, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "2");
        service.crea(posizioneB);

        PosizioneDebitoria posizioneAltroDebitore = new PosizioneDebitoria();
        posizioneAltroDebitore.setIdApplicazione(idApplicazionePer("A2A-CERCA-DEBITORE"));
        posizioneAltroDebitore.setIdPosizioneDebitoria("pos-cerca-3");
        posizioneAltroDebitore.setIdDominio(1L);
        posizioneAltroDebitore.setDescrizione("test");
        posizioneAltroDebitore.addSoggettoDebitore(soggettoDiProva2());
        opzioneConPendenza(posizioneAltroDebitore, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "3");
        service.crea(posizioneAltroDebitore);

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
            service.crea(posizione);
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
            service.crea(posizione);
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
        service.crea(posizioneDominio1);

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
        service.crea(posizioneDominio2);

        PaginaRisultati<Pendenza> senzaFiltroDominio = service.cercaPendenze("A2A-CERCA-NAV", "300000000000000001",
                null, OffsetPageRequest.of(0, 10));
        PaginaRisultati<Pendenza> conFiltroDominio = service.cercaPendenze("A2A-CERCA-NAV", "300000000000000001", 2L,
                OffsetPageRequest.of(0, 10));

        assertThat(senzaFiltroDominio.numeroRisultatiTotali()).isEqualTo(2);
        assertThat(conFiltroDominio.numeroRisultatiTotali()).isEqualTo(1);
        assertThat(conFiltroDominio.risultati().get(0).getIdDominio()).isEqualTo(2L);
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
        service.crea(prima);

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

        assertThatThrownBy(() -> service.crea(seconda))
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
        service.crea(prima);

        PosizioneDebitoria seconda = new PosizioneDebitoria();
        seconda.setIdApplicazione(idApplicazione);
        seconda.setIdPosizioneDebitoria("pos-duplicata"); // stesso idA2A+idPosizioneDebitoria
        seconda.setIdDominio(2L); // dominio diverso: il vincolo DB legacy lo lascerebbe passare
        seconda.setDescrizione("test");
        seconda.addSoggettoDebitore(soggettoDiProva());
        opzioneConPendenza(seconda, TipologiaOpzionePagamento.SOLUZIONE_UNICA, "2");

        assertThatThrownBy(() -> service.crea(seconda))
                .isInstanceOf(RisorsaGiaEsistenteException.class)
                .hasMessageContaining("pos-duplicata");
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
        pendenza.setDataCaricamento(LocalDate.of(2026, 7, 29));
        opzione.addPendenza(pendenza);

        pendenza.addVocePendenza(voceDiProva("voce-senza-campi-debitore", 10.00));

        PosizioneDebitoria creata = service.crea(posizione);
        Pendenza pendenzaCreata = creata.getOpzioniPagamento().get(0).getPendenze().get(0);

        assertThat(pendenzaCreata.getDebitoreIdentificativo()).isEqualTo("VEDERE_SOGGETTI_DEBITORI");
        assertThat(pendenzaCreata.getDebitoreAnagrafica()).isEqualTo("Vedere tabella soggetti_debitori");
        assertThat(pendenzaCreata.getSrcDebitoreIdentificativo()).isEqualTo("VEDERE_SOGGETTI_DEBITORI");
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private VocePendenza voceDiProva(String idVocePendenza, double importo) {
        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza(idVocePendenza);
        voce.setImporto(importo);
        voce.setDescrizione("test");
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-1");
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
        pendenza.setDataCaricamento(LocalDate.of(2026, 7, 29));
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + suffisso);
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-1");
        pendenza.addVocePendenza(voce);

        return pendenza;
    }
}
