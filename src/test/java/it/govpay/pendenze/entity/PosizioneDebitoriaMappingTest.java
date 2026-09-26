package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Verifica il mapping dell'aggregato contro lo schema reale (M1-M11 di
 * {@code proposta-modello-nativo-v3.md}).
 *
 * <p>Il contesto usa {@code ddl-auto=validate} su H2 con il DDL di {@code db/schema-pendenze-test.sql}:
 * se una colonna avesse il nome o il tipo sbagliato, il contesto non partirebbe nemmeno.
 * Il fatto che questi test girino e' quindi la prima asserzione, implicita.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@ActiveProfiles("test")
class PosizioneDebitoriaMappingTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 7, 29, 10, 30, 0, 0, ZoneOffset.ofHours(2));

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("una posizione con l'aggregato minimo viene salvata e riletta")
    void aggregatoMinimo() {
        PosizioneDebitoria posizione = posizioneMinima();
        SoggettoDebitore soggetto = soggetto(0, "RSSMRA80A01H501U", "Mario Rossi");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzione = opzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = pendenza(posizione.getIdDominio(), "10000000001", "300000000000000001");
        opzione.addPendenza(pendenza);

        VocePendenza voce = voceRiferimentoEntrata(1);
        pendenza.addVocePendenza(voce);

        em.persistAndFlush(posizione);
        em.clear();

        PosizioneDebitoria riletta = em.find(PosizioneDebitoria.class, posizione.getId());

        assertThat(riletta.getIdApplicazione()).isEqualTo(1L);
        assertThat(riletta.getIdPosizioneDebitoria()).isEqualTo("abcdef12345");
        assertThat(riletta.getDataCreazione().toInstant()).isEqualTo(ADESSO.toInstant());
        assertThat(riletta.getDataPubblicazione()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(riletta.isNotificaSend()).isFalse();

        assertThat(riletta.getSoggettiDebitori()).hasSize(1);
        SoggettoDebitore soggettoLetto = riletta.getSoggettiDebitori().get(0);
        assertThat(soggettoLetto.getTipo()).isEqualTo(TipoSoggetto.F);
        assertThat(soggettoLetto.getIdentificativo()).isEqualTo("RSSMRA80A01H501U");
        assertThat(soggettoLetto.getPosizioneDebitoria().getId()).isEqualTo(riletta.getId());

        assertThat(riletta.getOpzioniPagamento()).hasSize(1);
        OpzionePagamento opzioneLetta = riletta.getOpzioniPagamento().get(0);
        assertThat(opzioneLetta.getTipologia()).isEqualTo(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        assertThat(opzioneLetta.getStato()).isEqualTo(StatoOpzionePagamento.DISPONIBILE);
        assertThat(opzioneLetta.getIdOpzionePagamento()).isEqualTo(opzione.getIdOpzionePagamento());
        assertThat(opzioneLetta.getVersione()).isZero();

        assertThat(opzioneLetta.getPendenze()).hasSize(1);
        Pendenza pendenzaLetta = opzioneLetta.getPendenze().get(0);
        assertThat(pendenzaLetta.getStato()).isEqualTo(StatoPendenza.NON_ESEGUITO);
        assertThat(pendenzaLetta.getImporto()).isEqualTo(100.50);
        assertThat(pendenzaLetta.getIdDominio()).isEqualTo(riletta.getIdDominio());

        assertThat(pendenzaLetta.getVoci()).hasSize(1);
        VocePendenza voceLetta = pendenzaLetta.getVoci().get(0);
        assertThat(voceLetta.getTipoRiferimento()).isEqualTo(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        assertThat(voceLetta.getCodEntrata()).isEqualTo("SRV-12345");
        assertThat(voceLetta.getIbanAccredito()).isNull();
        assertThat(voceLetta.getDettaglioContabile()).isEmpty();
    }

    @Test
    @DisplayName("dettaglioContabile sopravvive alla rilettura tramite il converter JPA")
    void dettaglioContabileSopravviveAllaRilettura() {
        PosizioneDebitoria posizione = posizioneMinima();
        posizione.addSoggettoDebitore(soggetto(0, "RSSMRA80A01H501U", "Mario Rossi"));
        OpzionePagamento opzione = opzione(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.addOpzionePagamento(opzione);
        Pendenza pendenza = pendenza(posizione.getIdDominio(), "10000000001", "300000000000000001");
        opzione.addPendenza(pendenza);

        VocePendenza conDettaglio = voceRiferimentoEntrata(1);
        conDettaglio.setDettaglioContabile(List.of(
                new DettaglioContabile.Civilistico("2026", "UFF1", "14.01.03", null, null,
                        new BigDecimal("100.50"))));
        pendenza.addVocePendenza(conDettaglio);

        em.persistAndFlush(posizione);
        em.clear();

        PosizioneDebitoria riletta = em.find(PosizioneDebitoria.class, posizione.getId());
        VocePendenza voceLetta = riletta.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci().get(0);

        assertThat(voceLetta.getDettaglioContabile()).containsExactly(
                new DettaglioContabile.Civilistico("2026", "UFF1", "14.01.03", null, null,
                        new BigDecimal("100.50")));
    }

    @Test
    @DisplayName("l'ordine di soggetti, pendenze e voci sopravvive alla rilettura")
    void ordineMantenuto() {
        PosizioneDebitoria posizione = posizioneMinima();
        posizione.addSoggettoDebitore(soggetto(1, "VRDGNN80A01H501W", "Giovanna Verdi"));
        posizione.addSoggettoDebitore(soggetto(0, "RSSMRA80A01H501U", "Mario Rossi"));

        OpzionePagamento opzione = opzione(TipologiaOpzionePagamento.PIANO_RATEALE);
        posizione.addOpzionePagamento(opzione);

        Pendenza rata2 = pendenza(posizione.getIdDominio(), "rata-2", "300000000000000002");
        rata2.setNumeroRata(2);
        opzione.addPendenza(rata2);
        Pendenza rata1 = pendenza(posizione.getIdDominio(), "rata-1", "300000000000000001");
        rata1.setNumeroRata(1);
        opzione.addPendenza(rata1);

        rata1.addVocePendenza(voceRiferimentoEntrataConIndice(2));
        rata1.addVocePendenza(voceRiferimentoEntrataConIndice(1));

        em.persistAndFlush(posizione);
        em.clear();

        PosizioneDebitoria riletta = em.find(PosizioneDebitoria.class, posizione.getId());

        assertThat(riletta.getSoggettiDebitori()).extracting(SoggettoDebitore::getOrdine)
                .containsExactly(0, 1);
        assertThat(riletta.getOpzioniPagamento().get(0).getPendenze())
                .extracting(Pendenza::getNumeroRata)
                .containsExactly(1, 2);
        assertThat(riletta.getOpzioniPagamento().get(0).getPendenze().get(0).getVoci())
                .extracting(VocePendenza::getIndice)
                .containsExactly(1, 2);
    }

    // L'unicita' di IUV/numeroAvviso per dominio (M13) non e' piu' un vincolo DB da
    // verificare qui (decisione del lead, 2026-09-25: versamenti non ne ha mai avuto uno in
    // produzione — solo un indice non univoco — il legacy la applica a livello applicativo,
    // VER_025): il test e' ora in PosizioneDebitoriaServiceTest, dove vive il controllo
    // (PosizioneDebitoriaService.verificaNumeroAvvisoNonDuplicato).

    private PosizioneDebitoria posizioneMinima() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdApplicazione(1L);
        posizione.setIdPosizioneDebitoria("abcdef12345");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("Sanzione CdS n. abc00000");
        posizione.setDataPubblicazione(LocalDate.of(2026, 8, 1));
        posizione.setNotificaSend(false);
        posizione.setDataCreazione(ADESSO);
        posizione.setDataUltimoAggiornamento(ADESSO);
        return posizione;
    }

    private SoggettoDebitore soggetto(int ordine, String identificativo, String anagrafica) {
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setOrdine(ordine);
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo(identificativo);
        soggetto.setAnagrafica(anagrafica);
        return soggetto;
    }

    private OpzionePagamento opzione(TipologiaOpzionePagamento tipologia) {
        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setIdOpzionePagamento(UUID.randomUUID());
        opzione.setTipologia(tipologia);
        opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
        opzione.setDataCreazione(ADESSO);
        opzione.setDataUltimoAggiornamento(ADESSO);
        return opzione;
    }

    private Pendenza pendenza(Long idDominio, String idPendenza, String numeroAvviso) {
        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(idDominio);
        pendenza.setIdApplicazione(1L);
        pendenza.setIdPendenza(idPendenza);
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setNumeroRata(1);
        pendenza.setImporto(100.50);
        pendenza.setNumeroAvviso(numeroAvviso);
        pendenza.setIuv(numeroAvviso);
        pendenza.setSrcIuv(numeroAvviso.toUpperCase());
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setDataCaricamento(LocalDate.of(2026, 7, 29));
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataUltimoAggiornamento(ADESSO);
        return pendenza;
    }

    private VocePendenza voceRiferimentoEntrata(int indice) {
        return voceRiferimentoEntrataConIndice(indice);
    }

    private VocePendenza voceRiferimentoEntrataConIndice(int indice) {
        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-" + indice);
        voce.setImporto(100.50);
        voce.setDescrizione("Sanzione CdS n. abc00000");
        voce.setIndice(indice);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-12345");
        return voce;
    }
}
