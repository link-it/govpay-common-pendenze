package it.govpay.pendenze.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;

/**
 * Verifica {@link PosizioneDebitoriaService#trovaPendenzaCompletaPerId}: la pendenza torna con
 * voci, opzione, posizione e soggetti debitori gia' caricati, in due sole query, e il grafo
 * resta navigabile dopo la chiusura della sessione (issue #13, lettura per
 * govpay-notify-batch).
 *
 * <p>Le statistiche di Hibernate sono attive solo in questo contesto, per contare gli
 * statement.</p>
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@Import(PosizioneDebitoriaService.class)
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"})
@ActiveProfiles("test")
class LetturaPendenzaCompletaTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 11, 1, 10, 30, 0, 0, ZoneOffset.ofHours(1));

    @Autowired
    private TestEntityManager em;

    @Autowired
    private PosizioneDebitoriaService service;

    @Autowired
    private PosizioneDebitoriaRepository posizioneDebitoriaRepository;

    @Autowired
    private PendenzaRepository pendenzaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistiche;

    @BeforeEach
    void azzeraStatistiche() {
        statistiche = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("voci, opzione, posizione e soggetti sono caricati in due query, nell'ordine dichiarato")
    void grafoCaricatoInDueQuery() {
        PosizioneDebitoria posizione = posizioneConDueSoggettiEDueVoci("DOC-GRAFO", "PND-GRAFO");
        em.persistAndFlush(posizione);
        Long idPendenza = posizione.getOpzioniPagamento().get(0).getPendenze().get(0).getId();
        em.clear();
        statistiche.clear();

        Pendenza pendenza = service.trovaPendenzaCompletaPerId(idPendenza).orElseThrow();

        assertThat(statistiche.getPrepareStatementCount()).isEqualTo(2);
        OpzionePagamento opzione = pendenza.getOpzionePagamento();
        assertThat(Hibernate.isInitialized(pendenza.getVoci())).isTrue();
        assertThat(Hibernate.isInitialized(opzione)).isTrue();
        assertThat(Hibernate.isInitialized(opzione.getPosizioneDebitoria())).isTrue();
        assertThat(Hibernate.isInitialized(opzione.getPosizioneDebitoria().getSoggettiDebitori())).isTrue();

        // Dopo il detach un accesso a una collezione non caricata solleverebbe
        // LazyInitializationException.
        em.clear();
        assertGrafoCompleto(pendenza);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("il grafo resta navigabile a sessione chiusa, fuori da ogni transazione")
    void grafoNavigabileFuoriTransazione() {
        TransactionTemplate transazione = new TransactionTemplate(transactionManager);
        Long idPosizione = transazione.execute(stato -> posizioneDebitoriaRepository
                .save(posizioneConDueSoggettiEDueVoci("DOC-FUORI-TX", "PND-FUORI-TX")).getId());
        try {
            Long idPendenza = transazione.execute(stato -> posizioneDebitoriaRepository.findById(idPosizione)
                    .orElseThrow().getOpzioniPagamento().get(0).getPendenze().get(0).getId());

            Pendenza pendenza = service.trovaPendenzaCompletaPerId(idPendenza).orElseThrow();

            assertGrafoCompleto(pendenza);
        } finally {
            transazione.executeWithoutResult(stato -> posizioneDebitoriaRepository.deleteById(idPosizione));
        }
    }

    @Test
    @DisplayName("una pendenza v2 senza opzione e' restituita con le sole voci, in una query")
    void pendenzaSenzaOpzione() {
        Pendenza pendenza = pendenza("PND-V2");
        pendenza.addVocePendenza(voce(1));
        em.persistAndFlush(pendenza);
        em.clear();
        statistiche.clear();

        Pendenza letta = service.trovaPendenzaCompletaPerId(pendenza.getId()).orElseThrow();

        assertThat(statistiche.getPrepareStatementCount()).isEqualTo(1);
        em.clear();
        assertThat(letta.getOpzionePagamento()).isNull();
        assertThat(letta.getVoci()).extracting(VocePendenza::getIndice).containsExactly(1);
    }

    @Test
    @DisplayName("un id inesistente restituisce un Optional vuoto")
    void idInesistente() {
        assertThat(service.trovaPendenzaCompletaPerId(-1L)).isEmpty();
        assertThat(pendenzaRepository.findByIdConVociEOpzione(-1L)).isEmpty();
    }

    private static void assertGrafoCompleto(Pendenza pendenza) {
        assertThat(pendenza.getVoci()).extracting(VocePendenza::getIndice).containsExactly(1, 2);
        OpzionePagamento opzione = pendenza.getOpzionePagamento();
        assertThat(opzione.getTipologia()).isEqualTo(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        PosizioneDebitoria posizione = opzione.getPosizioneDebitoria();
        assertThat(posizione.getCodDocumento()).startsWith("DOC-");
        assertThat(posizione.getSoggettiDebitori())
                .extracting(SoggettoDebitore::getOrdine, SoggettoDebitore::getIdentificativo)
                .containsExactly(
                        tuple(0, "RSSMRA80A01H501U"),
                        tuple(1, "VRDLGU75B02F205X"));
    }

    /** Voci e soggetti aggiunti in ordine inverso, per verificare l'{@code @OrderBy} delle collezioni. */
    private static PosizioneDebitoria posizioneConDueSoggettiEDueVoci(String codDocumento, String idPendenza) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(1L);
        posizione.setIdDominio(1L);
        posizione.setCodDocumento(codDocumento);
        posizione.setDescrizione("Posizione con due debitori");
        posizione.setNotificaSend(false);
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);
        posizione.addSoggettoDebitore(soggetto(1, "VRDLGU75B02F205X", "Luigi Verdi"));
        posizione.addSoggettoDebitore(soggetto(0, "RSSMRA80A01H501U", "Mario Rossi"));

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataCreazione(ADESSO);
        opzione.setDataUltimoAggiornamento(ADESSO);
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = pendenza(idPendenza);
        pendenza.addVocePendenza(voce(2));
        pendenza.addVocePendenza(voce(1));
        opzione.addPendenza(pendenza);
        return posizione;
    }

    private static SoggettoDebitore soggetto(int ordine, String identificativo, String anagrafica) {
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setOrdine(ordine);
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo(identificativo);
        soggetto.setAnagrafica(anagrafica);
        return soggetto;
    }

    private static Pendenza pendenza(String idPendenza) {
        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(1L);
        pendenza.setIdApplicazione(1L);
        pendenza.setIdPendenza(idPendenza);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(100.50);
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        return pendenza;
    }

    private static VocePendenza voce(int indice) {
        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + indice);
        voce.setImporto(50.25);
        voce.setDescrizione("Voce " + indice);
        voce.setIndice(indice);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        return voce;
    }
}
