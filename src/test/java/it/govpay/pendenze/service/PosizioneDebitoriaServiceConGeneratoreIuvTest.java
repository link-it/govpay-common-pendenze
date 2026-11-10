package it.govpay.pendenze.service;

import it.govpay.pendenze.comando.DatiPosizioneDebitoria;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Debitore;
import it.govpay.pendenze.entity.PdDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.spi.GeneratoreIuv;
import it.govpay.pendenze.spi.IdentificativiPagamento;

/**
 * Verifica {@link PosizioneDebitoriaService#crea} quando un {@link GeneratoreIuv} e'
 * disponibile nel contesto — classe separata da {@link PosizioneDebitoriaServiceTest}
 * proprio perche' quella verifica il caso opposto (SPI assente).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(PendenzeAutoConfiguration.class)
@Import({PosizioneDebitoriaService.class, PosizioneDebitoriaServiceConGeneratoreIuvTest.Config.class})
@EntityScan(basePackages = {"it.govpay.pendenze.entity", "it.govpay.common.entity"})
@EnableJpaRepositories(basePackages = {"it.govpay.pendenze.repository", "it.govpay.common.repository"})
@ActiveProfiles("test")
class PosizioneDebitoriaServiceConGeneratoreIuvTest {

    @Autowired
    private PosizioneDebitoriaService service;

    @Autowired
    private TestEntityManager em;

    private Long applicazionePersistita(String codApplicazione) {
        ApplicazioneEntity applicazione = ApplicazioneEntity.builder()
                .codApplicazione(codApplicazione).autoIuv(true).firmaRicevuta("N").trusted(true).idUtenza(1L).build();
        em.persistAndFlush(applicazione);
        return applicazione.getId();
    }

    @Test
    @DisplayName("crea genera IUV/numero avviso per le pendenze che ne sono prive")
    void creaGeneraIdentificativiMancanti() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        Long idApplicazione = applicazionePersistita("A2A-1");
        posizione.setIdApplicazione(idApplicazione);
        posizione.setCodDocumento("pos-1");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        Debitore soggetto = new Debitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        PdDebitore pdDebitore = new PdDebitore();
        pdDebitore.setDebitore(soggetto);
        posizione.addPdDebitore(pdDebitore);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        pdDebitore.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdApplicazione(idApplicazione);
        pendenza.setIdPendenza("pendenza-1");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setImporto(10.00);
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        // IUV/numeroAvviso volutamente non impostati: deve generarli il GeneratoreIuv.
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-1");
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        pendenza.addVocePendenza(voce);

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getIuv()).isEqualTo("IUV-pendenza-1");
        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso()).isEqualTo("999999999999999999");
    }

    @Test
    @DisplayName("crea non tocca IUV/numero avviso se il chiamante li fornisce entrambi, anche con un GeneratoreIuv disponibile")
    void creaNonSovrascriveIdentificativiForniti() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        Long idApplicazione = applicazionePersistita("A2A-2");
        posizione.setIdApplicazione(idApplicazione);
        posizione.setCodDocumento("pos-2");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        Debitore soggetto = new Debitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        PdDebitore pdDebitore = new PdDebitore();
        pdDebitore.setDebitore(soggetto);
        posizione.addPdDebitore(pdDebitore);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        pdDebitore.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdApplicazione(idApplicazione);
        pendenza.setIdPendenza("pendenza-2");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setImporto(10.00);
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setIuv("IUV-FORNITO-DAL-CHIAMANTE");
        pendenza.setNumeroAvviso("111111111111111111");
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-2");
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        pendenza.addVocePendenza(voce);

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getIuv()).isEqualTo("IUV-FORNITO-DAL-CHIAMANTE");
        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso()).isEqualTo("111111111111111111");
    }

    @Test
    @DisplayName("crea ricava lo iuv dal numeroAvviso fornito senza generare una coppia nuova (conversione, non generazione)")
    void creaRicavaIuvDaNumeroAvvisoFornito() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        Long idApplicazione = applicazionePersistita("A2A-3");
        posizione.setIdApplicazione(idApplicazione);
        posizione.setCodDocumento("pos-3");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        Debitore soggetto = new Debitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        PdDebitore pdDebitore = new PdDebitore();
        pdDebitore.setDebitore(soggetto);
        posizione.addPdDebitore(pdDebitore);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        pdDebitore.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdApplicazione(idApplicazione);
        pendenza.setIdPendenza("pendenza-3");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setImporto(10.00);
        pendenza.setDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setDebitoreAnagrafica("Mario Rossi");
        pendenza.setSrcDebitoreIdentificativo("RSSMRA80A01H501U");
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        // solo il numeroAvviso e' fornito: lo iuv va ricavato da esso (conversione), non generato.
        pendenza.setNumeroAvviso("222222222222222222");
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-3");
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        pendenza.addVocePendenza(voce);

        PosizioneDebitoria salvata = service.crea(DatiPosizioneDebitoria.da(posizione));

        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getIuv()).isEqualTo("IUV-DA-222222222222222222");
        assertThat(salvata.getOpzioniPagamento().get(0).getPendenze().get(0).getNumeroAvviso()).isEqualTo("222222222222222222");
    }

    @Test
    @DisplayName("crea rifiuta con una validazione (non un errore interno) un numeroAvviso non valido per il dominio")
    void creaRifiutaNumeroAvvisoNonValido() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        Long idApplicazione = applicazionePersistita("A2A-4");
        posizione.setIdApplicazione(idApplicazione);
        posizione.setCodDocumento("pos-4");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        Debitore soggetto = new Debitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        PdDebitore pdDebitore = new PdDebitore();
        pdDebitore.setDebitore(soggetto);
        posizione.addPdDebitore(pdDebitore);
        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        pdDebitore.addOpzionePagamento(opzione);
        Pendenza pendenza = new Pendenza();
        pendenza.setIdApplicazione(idApplicazione);
        pendenza.setIdPendenza("pendenza-4");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setIdTipoVersamento(1L);
        pendenza.setImporto(10.00);
        pendenza.setStato(StatoPendenza.NON_ESEGUITO);
        pendenza.setNumeroAvviso(Config.NUMERO_AVVISO_NON_VALIDO);
        opzione.addPendenza(pendenza);
        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-4");
        voce.setImporto(10.00);
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdTributo(42L);
        pendenza.addVocePendenza(voce);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.crea(DatiPosizioneDebitoria.da(posizione)))
                .isInstanceOf(it.govpay.pendenze.exception.ValidazioneNonSuperataException.class)
                .hasMessageContaining("non valido")
                .hasMessageContaining("pendenza-4");
    }

    @TestConfiguration
    static class Config {
        static final String NUMERO_AVVISO_NON_VALIDO = "333333333333333333";

        /** Generatore fittizio, deterministico: solo per verificare che venga invocato e usato. */
        @Bean
        GeneratoreIuv generatoreIuv() {
            return new GeneratoreIuv() {
                @Override
                public IdentificativiPagamento genera(Long idDominio, String idA2A, String idPendenza,
                        String codificaIuvTipoPendenza) {
                    return new IdentificativiPagamento("IUV-" + idPendenza, "999999999999999999");
                }

                @Override
                public IdentificativiPagamento convertiDaNumeroAvviso(Long idDominio, String numeroAvviso) {
                    if (NUMERO_AVVISO_NON_VALIDO.equals(numeroAvviso)) {
                        throw new IllegalArgumentException("numeroAvviso [" + numeroAvviso + "] ha check digit non coerente");
                    }
                    return new IdentificativiPagamento("IUV-DA-" + numeroAvviso, numeroAvviso);
                }
            };
        }
    }
}
