package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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
import it.govpay.pendenze.model.TipoPagamento;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Verifica il mapping di {@link Rpt}/{@link Pagamento}/{@link Rendicontazione}/
 * {@link FlussoRendicontazione} contro lo schema legacy reale: sono fuori dall'aggregato
 * {@link PosizioneDebitoria} (FK piatte o correlazione per IUV, non relazioni JPA verso
 * {@link Pendenza} — vedi Javadoc di classe di {@link Rpt}/{@link Rendicontazione}),
 * quindi questo test le persiste collegandole a una pendenza reale tramite il suo
 * {@code id}/{@code iuv}, non tramite un {@code addX(...)} sull'aggregato.
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
    @DisplayName("una ricevuta (rpt) viene salvata e riletta, con il contenuto XML intatto")
    void ricevutaSalvataERiletta() {
        Pendenza pendenza = pendenzaPersistita();

        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv(pendenza.getIuv());
        rpt.setIur("1234acdc");
        rpt.setCodDominio("DOMINIO_1");
        rpt.setXmlRt("<Receipt><receiptId>9a1e6b2e</receiptId></Receipt>".getBytes(StandardCharsets.UTF_8));
        rpt.setDataMsgRicevuta(ADESSO);
        rpt.setVersione("RPTV2_RTV1");
        em.persistAndFlush(rpt);
        em.clear();

        Rpt riletta = em.find(Rpt.class, rpt.getId());

        assertThat(riletta.getIdVersamento()).isEqualTo(pendenza.getId());
        assertThat(riletta.getIur()).isEqualTo("1234acdc");
        assertThat(riletta.getVersione()).isEqualTo("RPTV2_RTV1");
        assertThat(riletta.getXmlRt())
                .isEqualTo("<Receipt><receiptId>9a1e6b2e</receiptId></Receipt>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("un pagamento viene salvato e riletto, collegato alla sua rpt")
    void pagamentoSalvatoERiletto() {
        Pendenza pendenza = pendenzaPersistita();

        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv(pendenza.getIuv());
        rpt.setIur("1234acdc");
        rpt.setCodDominio("DOMINIO_1");
        rpt.setXmlRt("<Receipt/>".getBytes(StandardCharsets.UTF_8));
        rpt.setDataMsgRicevuta(ADESSO);
        rpt.setVersione("RPTV2_RTV1");
        em.persistAndFlush(rpt);

        Pagamento pagamento = new Pagamento();
        pagamento.setCodDominio("DOMINIO_1");
        pagamento.setIuv(pendenza.getIuv());
        pagamento.setIur("1234acdc");
        pagamento.setImportoPagato(100.50);
        pagamento.setDataAcquisizione(ADESSO);
        pagamento.setDataPagamento(ADESSO);
        pagamento.setTipo(TipoPagamento.ENTRATA);
        pagamento.setRpt(rpt);
        em.persistAndFlush(pagamento);
        em.clear();

        Pagamento riletto = em.find(Pagamento.class, pagamento.getId());

        assertThat(riletto.getIur()).isEqualTo("1234acdc");
        assertThat(riletto.getRpt().getVersione()).isEqualTo("RPTV2_RTV1");
    }

    @Test
    @DisplayName("una rendicontazione viene salvata e riletta insieme al suo flusso")
    void rendicontazioneConFlussoSalvataERiletta() {
        Pendenza pendenza = pendenzaPersistita();

        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(1L);
        flusso.setCodDominio("DOMINIO_1");
        flusso.setCodFlusso("2017-11-21GovPAYPsp1-10:27:27.903");
        flusso.setDataOraFlusso(ADESSO);
        flusso.setIur("flusso-iur-1");
        flusso.setDataAcquisizione(ADESSO);
        flusso.setDataRegolamento(ADESSO);
        flusso.setCodPsp("ABI-12345");
        flusso.setNumeroPagamenti(3L);
        flusso.setImportoTotale(100.01);
        flusso.setStato(StatoFlussoRendicontazione.ACCETTATA);
        flusso.setRevisione(1L);
        flusso.setObsoleto(false);
        em.persistAndFlush(flusso);

        Rendicontazione rendicontazione = new Rendicontazione();
        rendicontazione.setFlusso(flusso);
        rendicontazione.setIuv(pendenza.getIuv());
        rendicontazione.setIur("1234acdc");
        rendicontazione.setIndiceDati(1);
        rendicontazione.setImportoPagato(10.01);
        rendicontazione.setEsito(0);
        rendicontazione.setData(ADESSO);
        rendicontazione.setStato(StatoRendicontazione.OK);

        em.persistAndFlush(rendicontazione);
        em.clear();

        Rendicontazione riletta = em.find(Rendicontazione.class, rendicontazione.getId());

        assertThat(riletta.getIuv()).isEqualTo(pendenza.getIuv());
        assertThat(riletta.getImportoPagato()).isEqualTo(10.01);
        assertThat(riletta.getFlusso().getCodFlusso()).isEqualTo("2017-11-21GovPAYPsp1-10:27:27.903");
        assertThat(riletta.getFlusso().getImportoTotale()).isEqualTo(100.01);
        assertThat(riletta.getFlusso().getStato()).isEqualTo(StatoFlussoRendicontazione.ACCETTATA);
    }

    @Test
    @DisplayName("più rendicontazioni possono condividere lo stesso flusso, senza duplicarne i dati di testata")
    void piuRendicontazioniCondividonoLoStessoFlusso() {
        Pendenza pendenza = pendenzaPersistita();

        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(1L);
        flusso.setCodDominio("DOMINIO_1");
        flusso.setCodFlusso("flusso-condiviso");
        flusso.setDataOraFlusso(ADESSO);
        flusso.setIur("flusso-iur-condiviso");
        flusso.setDataAcquisizione(ADESSO);
        flusso.setDataRegolamento(ADESSO);
        flusso.setCodPsp("ABI-1");
        flusso.setNumeroPagamenti(2L);
        flusso.setImportoTotale(20.00);
        flusso.setStato(StatoFlussoRendicontazione.ACCETTATA);
        flusso.setRevisione(1L);
        flusso.setObsoleto(false);
        em.persistAndFlush(flusso);

        Rendicontazione prima = new Rendicontazione();
        prima.setFlusso(flusso);
        prima.setIuv(pendenza.getIuv());
        prima.setIur("iur-1");
        prima.setImportoPagato(10.00);
        prima.setEsito(0);
        prima.setData(ADESSO);
        prima.setStato(StatoRendicontazione.OK);
        em.persistAndFlush(prima);

        Rendicontazione seconda = new Rendicontazione();
        seconda.setFlusso(flusso);
        seconda.setIuv(pendenza.getIuv());
        seconda.setIur("iur-2");
        seconda.setImportoPagato(10.00);
        seconda.setEsito(0);
        seconda.setData(ADESSO);
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
        revisione1.setCodDominio("DOMINIO_1");
        revisione1.setCodFlusso("flusso-revisionato");
        revisione1.setDataOraFlusso(ADESSO.minusDays(1));
        revisione1.setIur("flusso-iur-rev1");
        revisione1.setDataAcquisizione(ADESSO.minusDays(1));
        revisione1.setDataRegolamento(ADESSO.minusDays(1));
        revisione1.setCodPsp("ABI-1");
        revisione1.setNumeroPagamenti(1L);
        revisione1.setImportoTotale(10.00);
        revisione1.setStato(StatoFlussoRendicontazione.ACCETTATA);
        revisione1.setRevisione(1L);
        revisione1.setObsoleto(true);
        em.persistAndFlush(revisione1);

        FlussoRendicontazione revisione2 = new FlussoRendicontazione();
        revisione2.setIdDominio(1L);
        revisione2.setCodDominio("DOMINIO_1");
        revisione2.setCodFlusso("flusso-revisionato");
        revisione2.setDataOraFlusso(ADESSO);
        revisione2.setIur("flusso-iur-rev2");
        revisione2.setDataAcquisizione(ADESSO);
        revisione2.setDataRegolamento(ADESSO);
        revisione2.setCodPsp("ABI-1");
        revisione2.setNumeroPagamenti(2L);
        revisione2.setImportoTotale(20.00);
        revisione2.setStato(StatoFlussoRendicontazione.ACCETTATA);
        revisione2.setRevisione(2L);
        revisione2.setObsoleto(false);
        em.persistAndFlush(revisione2);

        Rendicontazione dallaRevisione1 = new Rendicontazione();
        dallaRevisione1.setFlusso(revisione1);
        dallaRevisione1.setIuv(pendenza.getIuv());
        dallaRevisione1.setIur("iur-rev1");
        dallaRevisione1.setImportoPagato(10.00);
        dallaRevisione1.setEsito(0);
        dallaRevisione1.setData(ADESSO);
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
        posizione.setIdApplicazione(1L);
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
        pendenza.setIdApplicazione(1L);
        pendenza.setIdPendenza("pendenza-ric-rend");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(100.50);
        pendenza.setNumeroAvviso("300000000000000009");
        pendenza.setIuv("300000000000000009");
        pendenza.setSrcIuv("300000000000000009");
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setDataCaricamento(java.time.LocalDate.of(2026, 7, 29));
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-1");
        voce.setImporto(100.50);
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
