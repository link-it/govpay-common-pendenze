package it.govpay.pendenze.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoSingoloVersamento;
import it.govpay.pendenze.model.StatoVersamento;
import it.govpay.pendenze.model.TipoBollo;
import it.govpay.pendenze.model.TipoContabilita;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaTipoVersamento;

/**
 * Verifica il mapping dell'aggregato contro lo schema reale.
 *
 * <p>Il contesto usa {@code ddl-auto=validate} su H2 con il DDL di produzione: se una
 * colonna avesse il nome o il tipo sbagliato, il contesto non partirebbe nemmeno. Il fatto
 * che questi test girino e' quindi la prima asserzione, implicita.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@ActiveProfiles("test")
class VersamentoMappingTest {

    private static final OffsetDateTime ADESSO =
            OffsetDateTime.of(2026, 7, 29, 10, 30, 0, 0, ZoneOffset.ofHours(2));

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("una pendenza con tutte le colonne valorizzate viene salvata e riletta")
    void tutteLeColonne() {
        Documento documento = documento();
        em.persist(documento);

        Versamento pendenza = pendenzaMinima();
        // anagrafica
        pendenza.setNome("Sanzione CDS");
        pendenza.setCausaleVersamento("01 U2FuemlvbmU=");
        pendenza.setTassonomia("tassonomia");
        pendenza.setTassonomiaAvviso("0801102IM");
        pendenza.setCodLotto("lotto-1");
        pendenza.setCodVersamentoLotto("vers-lotto-1");
        pendenza.setCodAnnoTributario("2026");
        pendenza.setCodBundlekey("bundle-1");
        pendenza.setDatiAllegati("{\"chiave\":\"valore\"}");
        pendenza.setProprieta("{\"lineaTestoRicevuta1\":\"riga\"}");
        pendenza.setCodRata("ENTRO15");
        pendenza.setDivisione("divisione");
        pendenza.setDirezione("direzione");
        // debitore
        pendenza.setDebitoreTipo(TipoSoggetto.PERSONA_FISICA);
        pendenza.setDebitoreIndirizzo("Via Roma");
        pendenza.setDebitoreCivico("1");
        pendenza.setDebitoreCap("00100");
        pendenza.setDebitoreLocalita("Roma");
        pendenza.setDebitoreProvincia("RM");
        pendenza.setDebitoreNazione("IT");
        pendenza.setDebitoreEmail("mario@example.org");
        pendenza.setDebitoreTelefono("0600000");
        pendenza.setDebitoreCellulare("3330000000");
        pendenza.setDebitoreFax("0611111");
        // stato
        pendenza.setDescrizioneStato("in attesa");
        pendenza.setAnomalie("nessuna");
        pendenza.setAck(true);
        pendenza.setAnomalo(true);
        // avviso e iuv
        pendenza.setIuvVersamento("RF12345678901234567");
        pendenza.setNumeroAvviso("301234567890123456");
        pendenza.setIuvPagamento("RF12345678901234567");
        pendenza.setSrcIuv("RF12345678901234567");
        // pagamento
        pendenza.setDataPagamento(ADESSO);
        pendenza.setIncasso(Boolean.TRUE);
        pendenza.setImportoPagato(new BigDecimal("10.00"));
        pendenza.setImportoIncassato(new BigDecimal("5.00"));
        // date
        pendenza.setDataValidita(ADESSO.plusDays(30));
        pendenza.setDataScadenza(ADESSO.plusDays(60));
        // avvisatura
        pendenza.setDataNotificaAvviso(ADESSO);
        pendenza.setAvvisoNotificato(Boolean.FALSE);
        pendenza.setAvvMailDataPromemoriaScadenza(ADESSO.plusDays(50));
        pendenza.setAvvMailPromemoriaScadenzaNotificato(Boolean.FALSE);
        pendenza.setAvvAppIoDataPromemoriaScadenza(ADESSO.plusDays(55));
        pendenza.setAvvAppIoPromemoriaScadenzaNotificato(Boolean.FALSE);
        // aca e sessione
        pendenza.setDataUltimaModificaAca(ADESSO);
        pendenza.setIdSessione("6f1e9c2a4b5d47f8a1b2c3d4e5f60718");
        pendenza.setDocumento(documento);

        pendenza.addSingoloVersamento(voce());

        em.persist(pendenza);
        em.flush();
        em.clear();

        Versamento riletta = em.find(Versamento.class, pendenza.getId());

        assertThat(riletta.getCodVersamentoEnte()).isEqualTo("PENDENZA-1");
        assertThat(riletta.getNome()).isEqualTo("Sanzione CDS");
        assertThat(riletta.getImportoTotale()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(riletta.getStatoVersamento()).isEqualTo(StatoVersamento.NON_ESEGUITO);
        assertThat(riletta.getStatoPagamento()).isEqualTo(StatoPagamento.NON_PAGATO);
        assertThat(riletta.getTipo()).isEqualTo(TipologiaTipoVersamento.DOVUTO);
        assertThat(riletta.getDebitoreTipo()).isEqualTo(TipoSoggetto.PERSONA_FISICA);
        assertThat(riletta.getIncasso()).isTrue();
        assertThat(riletta.getCodRata()).isEqualTo("ENTRO15");
        assertThat(riletta.isAck()).isTrue();
        assertThat(riletta.isAnomalo()).isTrue();
        assertThat(riletta.getDataUltimaModificaAca()).isNotNull();
        assertThat(riletta.getDataUltimaComunicazioneAca()).isNull();
        assertThat(riletta.getDocumento().getCodDocumento()).isEqualTo("DOC-1");
        assertThat(riletta.getSingoliVersamenti()).hasSize(1);

        SingoloVersamento voce = riletta.getSingoliVersamenti().get(0);
        assertThat(voce.getCodSingoloVersamentoEnte()).isEqualTo("VOCE-1");
        assertThat(voce.getImportoSingoloVersamento()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(voce.getStatoSingoloVersamento()).isEqualTo(StatoSingoloVersamento.NON_ESEGUITO);
        assertThat(voce.getTipoBollo()).isEqualTo(TipoBollo.IMPOSTA_BOLLO);
        assertThat(voce.getTipoContabilita()).isEqualTo(TipoContabilita.SIOPE);
        assertThat(voce.getIndiceDati()).isEqualTo(1);
    }

    @Test
    @DisplayName("la colonna troncata dell'avvisatura AppIO e' scritta e riletta")
    void colonnaTroncataAppIo() {
        Versamento pendenza = pendenzaMinima();
        pendenza.setAvvAppIoDataPromemoriaScadenza(ADESSO);
        pendenza.setAvvAppIoPromemoriaScadenzaNotificato(Boolean.FALSE);

        em.persist(pendenza);
        em.flush();
        em.clear();

        // La colonna si chiama avv_app_io_prom_scad_notificat, troncata a 30 caratteri
        // per il limite di Oracle: se il mapping usasse il nome "logico" completo, il
        // contesto non partirebbe con ddl-auto=validate.
        assertThat(em.find(Versamento.class, pendenza.getId())
                .getAvvAppIoPromemoriaScadenzaNotificato()).isFalse();
    }

    @Test
    @DisplayName("i tre flag di avvisatura sono tri-stato: null non equivale a false")
    void flagAvvisaturaTriStato() {
        Versamento pendenza = pendenzaMinima();

        em.persist(pendenza);
        em.flush();
        em.clear();

        Versamento riletta = em.find(Versamento.class, pendenza.getId());
        assertThat(riletta.getAvvisoNotificato()).isNull();
        assertThat(riletta.getAvvMailPromemoriaScadenzaNotificato()).isNull();
        assertThat(riletta.getAvvAppIoPromemoriaScadenzaNotificato()).isNull();
    }

    @Test
    @DisplayName("una pendenza con i soli campi obbligatori e' valida")
    void soliCampiObbligatori() {
        Versamento pendenza = pendenzaMinima();

        em.persist(pendenza);
        em.flush();

        assertThat(pendenza.getId()).isNotNull();
    }

    @Test
    @DisplayName("la chiave logica (idPendenza, applicazione) e' unica")
    void chiaveLogicaUnica() {
        em.persist(pendenzaMinima());
        em.flush();

        Versamento duplicata = pendenzaMinima();

        assertThatThrownBy(() -> {
            em.persist(duplicata);
            em.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("anno tributario: valore numerico letto, valore sporco tollerato")
    void annoTributario() {
        Versamento pendenza = pendenzaMinima();
        pendenza.setCodAnnoTributario("2026");
        em.persist(pendenza);
        em.flush();
        em.clear();

        Versamento riletta = em.find(Versamento.class, pendenza.getId());
        assertThat(riletta.annoTributario()).contains(2026);
        assertThat(riletta.getCodAnnoTributario()).isEqualTo("2026");

        riletta.setCodAnnoTributario("duemilaventisei");
        assertThat(riletta.annoTributario()).isEmpty();
        // il valore grezzo resta quello scritto, non viene normalizzato
        assertThat(riletta.getCodAnnoTributario()).isEqualTo("duemilaventisei");
    }

    @Test
    @DisplayName("data_ora_ultimo_aggiornamento conserva l'istante assegnato dal chiamante")
    void ultimoAggiornamentoNonVieneGenerato() {
        // Con @UpdateTimestamp l'istante sarebbe rigenerato al flush, in insert e in
        // update: un caricamento che deve conservare l'istante originale non potrebbe.
        OffsetDateTime storico = OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(1));
        Versamento pendenza = pendenzaMinima();
        pendenza.setDataOraUltimoAggiornamento(storico);

        em.persist(pendenza);
        em.flush();
        em.clear();

        Versamento riletta = em.find(Versamento.class, pendenza.getId());
        assertThat(riletta.getDataOraUltimoAggiornamento().toInstant())
                .isEqualTo(storico.toInstant());

        // e non si muove da sola su un update: e' il livello di aggiornamento (F3) che
        // deve riscriverla con il Clock della libreria.
        riletta.setDescrizioneStato("aggiornata");
        em.flush();
        assertThat(riletta.getDataOraUltimoAggiornamento().toInstant())
                .isEqualTo(storico.toInstant());
    }

    @Test
    @DisplayName("l'hash di un'entita' non cambia quando viene persistita")
    void hashStabileAllaPersistenza() {
        Versamento pendenza = pendenzaMinima();
        SingoloVersamento voce = voce();
        pendenza.addSingoloVersamento(voce);
        Set<Object> raccolte = new HashSet<>(Set.of(pendenza, voce));

        em.persist(pendenza);
        em.flush();

        // Se l'hash dipendesse dall'id, dopo il flush l'istanza finirebbe in un altro
        // bucket e non sarebbe piu' ritrovabile nella raccolta che la contiene.
        assertThat(pendenza.getId()).isNotNull();
        assertThat(voce.getId()).isNotNull();
        assertThat(raccolte).contains(pendenza, voce);
    }

    @Test
    @DisplayName("rimuovere una voce dalla collezione non la cancella: orphanRemoval e' disattivato")
    void vociNonCancellabiliDallaCollezione() {
        Versamento pendenza = pendenzaMinima();
        pendenza.addSingoloVersamento(voce());
        em.persist(pendenza);
        em.flush();
        Long idVoce = pendenza.getSingoliVersamenti().get(0).getId();

        pendenza.getSingoliVersamenti().clear();
        em.flush();
        em.clear();

        // La regola di dominio non ammette la rimozione di voci: senza orphanRemoval una
        // lista ricostruita male non cancella righe di nascosto.
        assertThat(em.find(SingoloVersamento.class, idVoce)).isNotNull();
    }

    private Versamento pendenzaMinima() {
        Versamento pendenza = new Versamento();
        pendenza.setIdApplicazione(1L);
        pendenza.setIdDominio(2L);
        pendenza.setIdTipoVersamento(3L);
        pendenza.setIdTipoVersamentoDominio(4L);
        pendenza.setCodVersamentoEnte("PENDENZA-1");
        pendenza.setImportoTotale(new BigDecimal("15.00"));
        pendenza.setStatoVersamento(StatoVersamento.NON_ESEGUITO);
        pendenza.setTipo(TipologiaTipoVersamento.DOVUTO);
        pendenza.setAggiornabile(true);
        pendenza.setAck(false);
        pendenza.setAnomalo(false);
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStatoPagamento(StatoPagamento.NON_PAGATO);
        pendenza.setImportoPagato(BigDecimal.ZERO);
        pendenza.setImportoIncassato(BigDecimal.ZERO);
        pendenza.setDataCreazione(ADESSO);
        pendenza.setDataOraUltimoAggiornamento(ADESSO);
        return pendenza;
    }

    private SingoloVersamento voce() {
        SingoloVersamento voce = new SingoloVersamento();
        voce.setCodSingoloVersamentoEnte("VOCE-1");
        voce.setStatoSingoloVersamento(StatoSingoloVersamento.NON_ESEGUITO);
        voce.setImportoSingoloVersamento(new BigDecimal("15.00"));
        voce.setIndiceDati(1);
        voce.setDescrizione("descrizione");
        voce.setDescrizioneCausaleRpt("causale rpt");
        voce.setDatiAllegati("{}");
        voce.setContabilita("{}");
        voce.setMetadata("{}");
        voce.setTipoBollo(TipoBollo.IMPOSTA_BOLLO);
        voce.setHashDocumento("hash");
        voce.setProvinciaResidenza("RM");
        voce.setTipoContabilita(TipoContabilita.SIOPE);
        voce.setCodiceContabilita("cap-1");
        voce.setIdTributo(10L);
        voce.setIdIbanAccredito(11L);
        voce.setIdIbanAppoggio(12L);
        voce.setIdDominio(2L);
        return voce;
    }

    private Documento documento() {
        Documento documento = new Documento();
        documento.setCodDocumento("DOC-1");
        documento.setDescrizione("Documento di test");
        documento.setIdDominio(2L);
        documento.setIdApplicazione(1L);
        return documento;
    }
}
