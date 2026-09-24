package it.govpay.pendenze.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.entity.FlussoRendicontazione;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.entity.Ricevuta;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoFlussoRendicontazione;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoRendicontazione;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRicevuta;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.RicevutaElenco;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@Import(RicevutaRendicontazioneService.class)
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
        Pendenza pendenzaA = pendenzaPersistita("pend-a");
        Pendenza pendenzaB = pendenzaPersistita("pend-b");
        ricevutaPersistita(pendenzaA, "iur-1");
        ricevutaPersistita(pendenzaA, "iur-2");
        ricevutaPersistita(pendenzaB, "iur-3");

        PaginaRisultati<RicevutaElenco> pagina = service.cercaRicevute(pendenzaA.getId(), OffsetPageRequest.of(0, 10));

        assertThat(pagina.numeroRisultatiTotali()).isEqualTo(2);
        assertThat(pagina.risultati()).extracting(RicevutaElenco::getIur).containsExactlyInAnyOrder("iur-1", "iur-2");
    }

    @Test
    @DisplayName("trovaRicevuta trova per idPendenza+iur, Optional vuoto se non esiste")
    void trovaRicevuta() {
        Pendenza pendenza = pendenzaPersistita("pend-trova");
        ricevutaPersistita(pendenza, "iur-trova");

        Optional<Ricevuta> trovata = service.trovaRicevuta(pendenza.getId(), "iur-trova");
        Optional<Ricevuta> nonTrovata = service.trovaRicevuta(pendenza.getId(), "iur-inesistente");

        assertThat(trovata).isPresent();
        assertThat(nonTrovata).isEmpty();
    }

    @Test
    @DisplayName("cercaRendicontazioni trova solo le rendicontazioni della pendenza richiesta")
    void cercaRendicontazioni() {
        Pendenza pendenzaA = pendenzaPersistita("pend-rend-a");
        Pendenza pendenzaB = pendenzaPersistita("pend-rend-b");
        FlussoRendicontazione flusso = flussoPersistito("flusso-cerca");
        rendicontazionePersistita(pendenzaA, flusso, "iur-r1");
        rendicontazionePersistita(pendenzaB, flusso, "iur-r2");

        PaginaRisultati<Rendicontazione> pagina = service.cercaRendicontazioni(pendenzaA.getId(),
                OffsetPageRequest.of(0, 10));

        assertThat(pagina.numeroRisultatiTotali()).isEqualTo(1);
        assertThat(pagina.risultati().get(0).getIur()).isEqualTo("iur-r1");
    }

    @Test
    @DisplayName("il flusso di una rendicontazione è già inizializzato dopo la ricerca tramite il servizio, "
            + "in una transazione diversa da quella di preparazione")
    void cercaRendicontazioniInizializzaIlFlusso() {
        Pendenza pendenza = pendenzaPersistita("pend-init-flusso");
        FlussoRendicontazione flusso = flussoPersistito("flusso-init");
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
        assertThat(trovata.getFlusso().getIdFlusso()).isEqualTo("flusso-init");
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private Ricevuta ricevutaPersistita(Pendenza pendenza, String iur) {
        Ricevuta ricevuta = new Ricevuta();
        ricevuta.setIdPendenza(pendenza.getId());
        ricevuta.setIur(iur);
        ricevuta.setTipo(TipoRicevuta.CT_RECEIPT_V2);
        ricevuta.setData(ADESSO);
        ricevuta.setContenuto("<Receipt/>");
        em.persistAndFlush(ricevuta);
        return ricevuta;
    }

    private FlussoRendicontazione flussoPersistito(String idFlusso) {
        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(1L);
        flusso.setIdFlusso(idFlusso);
        flusso.setDataFlusso(ADESSO);
        flusso.setTrn("trn");
        flusso.setDataRegolamento(ADESSO);
        flusso.setIdPsp("PSP-1");
        flusso.setNumeroPagamenti(1);
        flusso.setImportoTotale(new BigDecimal("10.00"));
        flusso.setStato(StatoFlussoRendicontazione.ACQUISITO);
        flusso.setRevisione(1L);
        flusso.setObsoleto(false);
        em.persistAndFlush(flusso);
        return flusso;
    }

    private Rendicontazione rendicontazionePersistita(Pendenza pendenza, FlussoRendicontazione flusso, String iur) {
        Rendicontazione rendicontazione = new Rendicontazione();
        rendicontazione.setIdPendenza(pendenza.getId());
        rendicontazione.setFlusso(flusso);
        rendicontazione.setIuv(pendenza.getIuv());
        rendicontazione.setIur(iur);
        rendicontazione.setImporto(new BigDecimal("10.00"));
        rendicontazione.setEsito(0);
        rendicontazione.setData(LocalDate.of(2026, 6, 10));
        rendicontazione.setStato(StatoRendicontazione.OK);
        em.persistAndFlush(rendicontazione);
        return rendicontazione;
    }

    private Pendenza pendenzaPersistita(String idPendenza) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdA2A("A2A-RIC-REND-SVC");
        posizione.setIdPosizioneDebitoria("pos-" + idPendenza + "-" + UUID.randomUUID().toString().substring(0, 8));
        posizione.setIdDominio(1L);
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
        pendenza.setIdDominio(1L);
        pendenza.setIdPendenza(idPendenza);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(new BigDecimal("10.00"));
        pendenza.setNumeroAvviso("30000000000000" + String.format("%04d", Math.abs(idPendenza.hashCode() % 10000)));
        pendenza.setIuv(pendenza.getNumeroAvviso());
        pendenza.setStato(StatoPendenza.NON_ESEGUITA);
        pendenza.setDataCaricamento(LocalDate.of(2026, 7, 29));
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + idPendenza);
        voce.setImporto(new BigDecimal("10.00"));
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-1");
        pendenza.addVocePendenza(voce);

        em.persistAndFlush(posizione);
        return pendenza;
    }
}
