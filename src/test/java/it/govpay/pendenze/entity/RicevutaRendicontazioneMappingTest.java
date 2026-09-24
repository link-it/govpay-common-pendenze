package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.model.StatoFlussoRendicontazione;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoRendicontazione;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRicevuta;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Verifica il mapping di {@link Ricevuta}/{@link Rendicontazione}/{@link FlussoRendicontazione}
 * contro lo schema reale: sono fuori dall'aggregato {@link PosizioneDebitoria} (FK piatte, non
 * relazioni JPA verso {@link Pendenza} — vedi Javadoc di classe di {@link Ricevuta}), quindi
 * questo test le persiste collegandole a una pendenza reale tramite il suo {@code id} interno,
 * non tramite un {@code addX(...)} sull'aggregato.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@ActiveProfiles("test")
class RicevutaRendicontazioneMappingTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 7, 29, 10, 30, 0, 0, ZoneOffset.ofHours(2));

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("una ricevuta viene salvata e riletta, con il contenuto JSON intatto")
    void ricevutaSalvataERiletta() {
        Pendenza pendenza = pendenzaPersistita();

        Ricevuta ricevuta = new Ricevuta();
        ricevuta.setIdPendenza(pendenza.getId());
        ricevuta.setIur("1234acdc");
        ricevuta.setTipo(TipoRicevuta.CT_RECEIPT_V2);
        ricevuta.setData(ADESSO);
        ricevuta.setContenuto("<Receipt><receiptId>9a1e6b2e</receiptId></Receipt>");

        em.persistAndFlush(ricevuta);
        em.clear();

        Ricevuta riletta = em.find(Ricevuta.class, ricevuta.getId());

        assertThat(riletta.getIdPendenza()).isEqualTo(pendenza.getId());
        assertThat(riletta.getIur()).isEqualTo("1234acdc");
        assertThat(riletta.getTipo()).isEqualTo(TipoRicevuta.CT_RECEIPT_V2);
        assertThat(riletta.getContenuto()).isEqualTo("<Receipt><receiptId>9a1e6b2e</receiptId></Receipt>");
    }

    @Test
    @DisplayName("una rendicontazione viene salvata e riletta insieme al suo flusso")
    void rendicontazioneConFlussoSalvataERiletta() {
        Pendenza pendenza = pendenzaPersistita();

        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(1L);
        flusso.setIdFlusso("2017-11-21GovPAYPsp1-10:27:27.903");
        flusso.setDataFlusso(ADESSO);
        flusso.setTrn("idriversamento12345");
        flusso.setDataRegolamento(ADESSO);
        flusso.setIdPsp("ABI-12345");
        flusso.setNumeroPagamenti(3);
        flusso.setImportoTotale(new BigDecimal("100.01"));
        flusso.setStato(StatoFlussoRendicontazione.ACQUISITO);
        flusso.setRevisione(1L);
        flusso.setObsoleto(false);
        em.persistAndFlush(flusso);

        Rendicontazione rendicontazione = new Rendicontazione();
        rendicontazione.setIdPendenza(pendenza.getId());
        rendicontazione.setFlusso(flusso);
        rendicontazione.setIuv(pendenza.getIuv());
        rendicontazione.setIur("1234acdc");
        rendicontazione.setIndice(1);
        rendicontazione.setImporto(new BigDecimal("10.01"));
        rendicontazione.setEsito(0);
        rendicontazione.setData(LocalDate.of(2026, 6, 10));
        rendicontazione.setStato(StatoRendicontazione.OK);

        em.persistAndFlush(rendicontazione);
        em.clear();

        Rendicontazione riletta = em.find(Rendicontazione.class, rendicontazione.getId());

        assertThat(riletta.getIdPendenza()).isEqualTo(pendenza.getId());
        assertThat(riletta.getImporto()).isEqualByComparingTo("10.01");
        assertThat(riletta.getFlusso().getIdFlusso()).isEqualTo("2017-11-21GovPAYPsp1-10:27:27.903");
        assertThat(riletta.getFlusso().getImportoTotale()).isEqualByComparingTo("100.01");
        assertThat(riletta.getFlusso().getStato()).isEqualTo(StatoFlussoRendicontazione.ACQUISITO);
    }

    @Test
    @DisplayName("più rendicontazioni possono condividere lo stesso flusso, senza duplicarne i dati di testata")
    void piuRendicontazioniCondividonoLoStessoFlusso() {
        Pendenza pendenza = pendenzaPersistita();

        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(1L);
        flusso.setIdFlusso("flusso-condiviso");
        flusso.setDataFlusso(ADESSO);
        flusso.setTrn("trn-1");
        flusso.setDataRegolamento(ADESSO);
        flusso.setIdPsp("ABI-1");
        flusso.setNumeroPagamenti(2);
        flusso.setImportoTotale(new BigDecimal("20.00"));
        flusso.setStato(StatoFlussoRendicontazione.ACQUISITO);
        flusso.setRevisione(1L);
        flusso.setObsoleto(false);
        em.persistAndFlush(flusso);

        Rendicontazione prima = new Rendicontazione();
        prima.setIdPendenza(pendenza.getId());
        prima.setFlusso(flusso);
        prima.setIuv(pendenza.getIuv());
        prima.setIur("iur-1");
        prima.setImporto(new BigDecimal("10.00"));
        prima.setEsito(0);
        prima.setData(LocalDate.of(2026, 6, 10));
        prima.setStato(StatoRendicontazione.OK);
        em.persistAndFlush(prima);

        Rendicontazione seconda = new Rendicontazione();
        seconda.setIdPendenza(pendenza.getId());
        seconda.setFlusso(flusso);
        seconda.setIuv(pendenza.getIuv());
        seconda.setIur("iur-2");
        seconda.setImporto(new BigDecimal("10.00"));
        seconda.setEsito(0);
        seconda.setData(LocalDate.of(2026, 6, 10));
        seconda.setStato(StatoRendicontazione.OK);
        em.persistAndFlush(seconda);

        em.clear();

        Rendicontazione primaRiletta = em.find(Rendicontazione.class, prima.getId());
        Rendicontazione secondaRiletta = em.find(Rendicontazione.class, seconda.getId());

        assertThat(primaRiletta.getFlusso().getId()).isEqualTo(secondaRiletta.getFlusso().getId());
    }

    @Test
    @DisplayName("più revisioni dello stesso flusso (dominio+identificativo) possono coesistere, ciascuna con le proprie rendicontazioni")
    void piuRevisioniDelloStessoFlussoCoesistono() {
        Pendenza pendenza = pendenzaPersistita();

        FlussoRendicontazione revisione1 = new FlussoRendicontazione();
        revisione1.setIdDominio(1L);
        revisione1.setIdFlusso("flusso-revisionato");
        revisione1.setDataFlusso(ADESSO.minusDays(1));
        revisione1.setTrn("trn-1");
        revisione1.setDataRegolamento(ADESSO.minusDays(1));
        revisione1.setIdPsp("ABI-1");
        revisione1.setNumeroPagamenti(1);
        revisione1.setImportoTotale(new BigDecimal("10.00"));
        revisione1.setStato(StatoFlussoRendicontazione.ACQUISITO);
        revisione1.setRevisione(1L);
        revisione1.setObsoleto(true);
        em.persistAndFlush(revisione1);

        FlussoRendicontazione revisione2 = new FlussoRendicontazione();
        revisione2.setIdDominio(1L);
        revisione2.setIdFlusso("flusso-revisionato");
        revisione2.setDataFlusso(ADESSO);
        revisione2.setTrn("trn-1");
        revisione2.setDataRegolamento(ADESSO);
        revisione2.setIdPsp("ABI-1");
        revisione2.setNumeroPagamenti(2);
        revisione2.setImportoTotale(new BigDecimal("20.00"));
        revisione2.setStato(StatoFlussoRendicontazione.ACQUISITO);
        revisione2.setRevisione(2L);
        revisione2.setObsoleto(false);
        em.persistAndFlush(revisione2);

        Rendicontazione dallaRevisione1 = new Rendicontazione();
        dallaRevisione1.setIdPendenza(pendenza.getId());
        dallaRevisione1.setFlusso(revisione1);
        dallaRevisione1.setIuv(pendenza.getIuv());
        dallaRevisione1.setIur("iur-rev1");
        dallaRevisione1.setImporto(new BigDecimal("10.00"));
        dallaRevisione1.setEsito(0);
        dallaRevisione1.setData(LocalDate.of(2026, 6, 10));
        dallaRevisione1.setStato(StatoRendicontazione.OK);
        em.persistAndFlush(dallaRevisione1);

        em.clear();

        FlussoRendicontazione rev1Riletta = em.find(FlussoRendicontazione.class, revisione1.getId());
        FlussoRendicontazione rev2Riletta = em.find(FlussoRendicontazione.class, revisione2.getId());
        Rendicontazione rendicontazioneRiletta = em.find(Rendicontazione.class, dallaRevisione1.getId());

        assertThat(rev1Riletta.isObsoleto()).isTrue();
        assertThat(rev2Riletta.isObsoleto()).isFalse();
        assertThat(rendicontazioneRiletta.getFlusso().getId()).isEqualTo(revisione1.getId());
        assertThat(rendicontazioneRiletta.getFlusso().getRevisione()).isEqualTo(1L);
    }

    private Pendenza pendenzaPersistita() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdA2A("A2A-RIC-REND");
        posizione.setIdPosizioneDebitoria("pos-ric-rend-" + UUID.randomUUID().toString().substring(0, 8));
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
        pendenza.setIdPendenza("pendenza-ric-rend");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(new BigDecimal("100.50"));
        pendenza.setNumeroAvviso("300000000000000009");
        pendenza.setIuv("300000000000000009");
        pendenza.setStato(StatoPendenza.NON_ESEGUITA);
        pendenza.setDataCaricamento(LocalDate.of(2026, 7, 29));
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-1");
        voce.setImporto(new BigDecimal("100.50"));
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
