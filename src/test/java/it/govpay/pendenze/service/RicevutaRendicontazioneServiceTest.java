package it.govpay.pendenze.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;

import it.govpay.common.entity.DominioEntity;
import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.entity.FlussoRendicontazione;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoFlussoRendicontazione;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoRendicontazione;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.RicevutaElenco;
import it.govpay.pendenze.spi.GeneratoreIuv;
import it.govpay.pendenze.spi.IdentificativiPagamento;

/**
 * {@code DominioRepository} e' inclusa nella scansione (a differenza degli altri test di
 * servizio) perche' {@link RicevutaRendicontazioneService} la usa davvero per risolvere
 * {@code codDominio} (vedi Javadoc di classe del servizio). Per evitare che questo attivi
 * {@code PendenzeAutoConfiguration#generatoreIuvStandard} (che richiederebbe l'intera catena
 * di {@code GeneratoreProgressivoIuv}/{@code AllocatoreBloccoProgressivoIuv}, inutile qui),
 * {@link Config} registra un {@link GeneratoreIuv} fittizio: soddisfa
 * {@code @ConditionalOnMissingBean(GeneratoreIuv.class)} senza che nessun test di questa
 * classe lo invochi mai.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@Import({RicevutaRendicontazioneService.class, RicevutaRendicontazioneServiceTest.Config.class})
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"})
@ActiveProfiles("test")
class RicevutaRendicontazioneServiceTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 7, 29, 10, 30, 0, 0, ZoneOffset.ofHours(2));

    @Autowired
    private TestEntityManager em;

    @Autowired
    private RicevutaRendicontazioneService service;

    @Test
    @DisplayName("cercaRicevute trova solo le ricevute della pendenza richiesta, rispettando la paginazione")
    void cercaRicevute() {
        Long idDominio = dominioPersistito("DOM-CERCA-RICEVUTE");
        Pendenza pendenzaA = pendenzaPersistita("pend-a", idDominio);
        Pendenza pendenzaB = pendenzaPersistita("pend-b", idDominio);
        ricevutaPersistita(pendenzaA, "iur-1");
        ricevutaPersistita(pendenzaA, "iur-2");
        ricevutaPersistita(pendenzaB, "iur-3");

        PaginaRisultati<RicevutaElenco> pagina = service.cercaRicevute(pendenzaA.getId(), OffsetPageRequest.of(0, 10));

        assertThat(pagina.numeroRisultatiTotali()).isEqualTo(2);
        assertThat(pagina.risultati()).extracting(RicevutaElenco::getIur).containsExactlyInAnyOrder("iur-1", "iur-2");
    }

    @Test
    @DisplayName("cercaRicevute esclude le rpt per cui la ricevuta non e' ancora arrivata")
    void cercaRicevuteEsclusePrimaDellaRicevuta() {
        Long idDominio = dominioPersistito("DOM-ATTESA");
        Pendenza pendenza = pendenzaPersistita("pend-attesa", idDominio);
        ricevutaPersistita(pendenza, "iur-arrivata");
        rptSenzaRicevutaPersistita(pendenza, "iur-in-attesa");

        PaginaRisultati<RicevutaElenco> pagina = service.cercaRicevute(pendenza.getId(), OffsetPageRequest.of(0, 10));

        assertThat(pagina.numeroRisultatiTotali()).isEqualTo(1);
        assertThat(pagina.risultati()).extracting(RicevutaElenco::getIur).containsExactly("iur-arrivata");
    }

    @Test
    @DisplayName("trovaRicevuta trova per idPendenza+iur, Optional vuoto se non esiste o se la ricevuta non e' ancora arrivata")
    void trovaRicevuta() {
        Long idDominio = dominioPersistito("DOM-TROVA");
        Pendenza pendenza = pendenzaPersistita("pend-trova", idDominio);
        ricevutaPersistita(pendenza, "iur-trova");
        rptSenzaRicevutaPersistita(pendenza, "iur-in-attesa");

        Optional<Rpt> trovata = service.trovaRicevuta(pendenza.getId(), "iur-trova");
        Optional<Rpt> nonTrovata = service.trovaRicevuta(pendenza.getId(), "iur-inesistente");
        Optional<Rpt> nonAncoraArrivata = service.trovaRicevuta(pendenza.getId(), "iur-in-attesa");

        assertThat(trovata).isPresent();
        assertThat(nonTrovata).isEmpty();
        assertThat(nonAncoraArrivata).isEmpty();
    }

    @Test
    @DisplayName("cercaRendicontazioni trova solo le rendicontazioni della pendenza richiesta")
    void cercaRendicontazioni() {
        Long idDominio = dominioPersistito("DOM-CERCA-REND");
        Pendenza pendenzaA = pendenzaPersistita("pend-rend-a", idDominio);
        Pendenza pendenzaB = pendenzaPersistita("pend-rend-b", idDominio);
        FlussoRendicontazione flusso = flussoPersistito("flusso-cerca", idDominio, "DOM-CERCA-REND");
        rendicontazionePersistita(pendenzaA, flusso, "iur-r1");
        rendicontazionePersistita(pendenzaB, flusso, "iur-r2");

        PaginaRisultati<Rendicontazione> pagina = service.cercaRendicontazioni(pendenzaA.getId(),
                OffsetPageRequest.of(0, 10));

        assertThat(pagina.numeroRisultatiTotali()).isEqualTo(1);
        assertThat(pagina.risultati().get(0).getIur()).isEqualTo("iur-r1");
    }

    @Test
    @DisplayName("cercaRendicontazioni non restituisce le rendicontazioni di un altro dominio con lo stesso IUV")
    void cercaRendicontazioniNonAttraversaIDomini() {
        Long idDominioA = dominioPersistito("DOMINIO_A");
        Long idDominioB = dominioPersistito("DOMINIO_B");

        Pendenza pendenzaA = pendenzaPersistita("pend-dom-a", idDominioA);
        Pendenza pendenzaB = pendenzaPersistita("pend-dom-b", idDominioB);
        // Stesso IUV su due domini diversi: legittimo, l'unicita' e' solo per dominio.
        pendenzaA.setIuv("300000000000000099");
        pendenzaA.setSrcIuv(pendenzaA.getIuv());
        pendenzaB.setIuv("300000000000000099");
        pendenzaB.setSrcIuv(pendenzaB.getIuv());
        em.persistAndFlush(pendenzaA);
        em.persistAndFlush(pendenzaB);

        FlussoRendicontazione flussoA = flussoPersistito("flusso-dom-a", idDominioA, "DOMINIO_A");
        FlussoRendicontazione flussoB = flussoPersistito("flusso-dom-b", idDominioB, "DOMINIO_B");
        rendicontazionePersistita(pendenzaA, flussoA, "iur-dom-a");
        rendicontazionePersistita(pendenzaB, flussoB, "iur-dom-b");

        PaginaRisultati<Rendicontazione> pagina = service.cercaRendicontazioni(pendenzaA.getId(),
                OffsetPageRequest.of(0, 10));

        assertThat(pagina.numeroRisultatiTotali()).isEqualTo(1);
        assertThat(pagina.risultati().get(0).getIur()).isEqualTo("iur-dom-a");
    }

    @Test
    @DisplayName("il flusso di una rendicontazione è già inizializzato dopo la ricerca tramite il servizio, "
            + "in una transazione diversa da quella di preparazione")
    void cercaRendicontazioniInizializzaIlFlusso() {
        Long idDominio = dominioPersistito("DOM-INIT-FLUSSO");
        Pendenza pendenza = pendenzaPersistita("pend-init-flusso", idDominio);
        FlussoRendicontazione flusso = flussoPersistito("flusso-init", idDominio, "DOM-INIT-FLUSSO");
        rendicontazionePersistita(pendenza, flusso, "iur-init");
        Long idPendenza = pendenza.getId();

        // Chiude la transazione di preparazione e ne apre una nuova, con un contesto di
        // persistenza vuoto: senza questo passaggio, la ricerca sotto ritroverebbe le stesse
        // istanze già gestite sopra (identity map di Hibernate) — sempre già inizializzate a
        // prescindere dall'@EntityGraph, e il test non intercetterebbe una sua regressione.
        // Riproduce così lo scenario originale (lettura in una transazione/sessione diversa da
        // quella di scrittura), invece di verificare solo entità già presenti nel contesto JPA.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        PaginaRisultati<Rendicontazione> pagina = service.cercaRendicontazioni(idPendenza, OffsetPageRequest.of(0, 10));
        Rendicontazione trovata = pagina.risultati().get(0);

        assertThat(Hibernate.isInitialized(trovata.getFlusso())).isTrue();
        assertThat(trovata.getFlusso().getCodFlusso()).isEqualTo("flusso-init");
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private Long dominioPersistito(String codDominio) {
        DominioEntity dominio = new DominioEntity();
        dominio.setCodDominio(codDominio);
        dominio.setAbilitato(true);
        dominio.setRagioneSociale("Ente di prova " + codDominio);
        dominio.setAuxDigit(1);
        dominio.setIntermediato(false);
        dominio.setScaricaFr(false);
        em.persistAndFlush(dominio);
        return dominio.getId();
    }

    private Rpt ricevutaPersistita(Pendenza pendenza, String iur) {
        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv(pendenza.getIuv());
        rpt.setIur(iur);
        rpt.setCodDominio("DOMINIO_1");
        rpt.setXmlRt("<Receipt/>".getBytes(StandardCharsets.UTF_8));
        rpt.setDataMsgRicevuta(ADESSO);
        rpt.setVersione("RPTV2_RTV1");
        em.persistAndFlush(rpt);
        return rpt;
    }

    /** RPT (richiesta di pagamento) inviata, ricevuta non ancora acquisita. */
    private Rpt rptSenzaRicevutaPersistita(Pendenza pendenza, String iur) {
        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv(pendenza.getIuv());
        rpt.setIur(iur);
        rpt.setCodDominio("DOMINIO_1");
        rpt.setVersione("RPTV2_RTV1");
        em.persistAndFlush(rpt);
        return rpt;
    }

    private FlussoRendicontazione flussoPersistito(String codFlusso, Long idDominio, String codDominio) {
        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(idDominio);
        flusso.setCodDominio(codDominio);
        flusso.setCodFlusso(codFlusso);
        flusso.setDataOraFlusso(ADESSO);
        flusso.setIur("flusso-iur-" + codFlusso);
        flusso.setDataAcquisizione(ADESSO);
        flusso.setDataRegolamento(ADESSO);
        flusso.setCodPsp("PSP-1");
        flusso.setNumeroPagamenti(1L);
        flusso.setImportoTotale(10.00);
        flusso.setStato(StatoFlussoRendicontazione.ACCETTATA);
        flusso.setRevisione(1L);
        flusso.setObsoleto(false);
        em.persistAndFlush(flusso);
        return flusso;
    }

    private Rendicontazione rendicontazionePersistita(Pendenza pendenza, FlussoRendicontazione flusso, String iur) {
        Rendicontazione rendicontazione = new Rendicontazione();
        rendicontazione.setFlusso(flusso);
        rendicontazione.setIuv(pendenza.getIuv());
        rendicontazione.setIur(iur);
        rendicontazione.setImportoPagato(10.00);
        rendicontazione.setEsito(0);
        rendicontazione.setData(ADESSO);
        rendicontazione.setStato(StatoRendicontazione.OK);
        em.persistAndFlush(rendicontazione);
        return rendicontazione;
    }

    private Pendenza pendenzaPersistita(String idPendenza, Long idDominio) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(1L);
        posizione.setIdPosizioneDebitoria("pos-" + idPendenza + "-" + UUID.randomUUID().toString().substring(0, 8));
        posizione.setIdDominio(idDominio);
        posizione.setDescrizione("test");
        posizione.setNotificaSend(false);
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);

        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setOrdine(0);
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataCreazione(ADESSO);
        opzione.setDataUltimoAggiornamento(ADESSO);
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(idDominio);
        pendenza.setIdPendenza(idPendenza);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(10.00);
        pendenza.setIdApplicazione(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setNumeroAvviso("30000000000000" + String.format("%04d", Math.abs(idPendenza.hashCode() % 10000)));
        pendenza.setIuv(pendenza.getNumeroAvviso());
        pendenza.setSrcIuv(pendenza.getIuv());
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setDataCaricamento(LocalDate.of(2026, 7, 29));
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + idPendenza);
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-1");
        pendenza.addVocePendenza(voce);

        em.persistAndFlush(posizione);
        return pendenza;
    }

    @TestConfiguration
    static class Config {
        @Bean
        GeneratoreIuv generatoreIuv() {
            return new GeneratoreIuv() {
                @Override
                public IdentificativiPagamento genera(Long idDominio, String idA2A, String idPendenza,
                        String codificaIuvTipoPendenza) {
                    throw new UnsupportedOperationException("mai invocato dai test di questa classe");
                }

                @Override
                public IdentificativiPagamento convertiDaNumeroAvviso(Long idDominio, String numeroAvviso) {
                    throw new UnsupportedOperationException("mai invocato dai test di questa classe");
                }
            };
        }
    }
}
