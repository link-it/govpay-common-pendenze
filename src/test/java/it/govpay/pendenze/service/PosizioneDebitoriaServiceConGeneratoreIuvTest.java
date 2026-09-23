package it.govpay.pendenze.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import it.govpay.pendenze.config.PendenzeAutoConfiguration;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
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
@ActiveProfiles("test")
class PosizioneDebitoriaServiceConGeneratoreIuvTest {

    @Autowired
    private PosizioneDebitoriaService service;

    @Test
    @DisplayName("crea genera IUV/numero avviso per le pendenze che ne sono prive")
    void creaGeneraIdentificativiMancanti() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdA2A("A2A-1");
        posizione.setIdPosizioneDebitoria("pos-1");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdPendenza("pendenza-1");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setImporto(new BigDecimal("10.00"));
        pendenza.setStato(StatoPendenza.NON_ESEGUITA);
        // IUV/numeroAvviso volutamente non impostati: deve generarli il GeneratoreIuv.
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-1");
        voce.setImporto(new BigDecimal("10.00"));
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-1");
        pendenza.addVocePendenza(voce);

        service.crea(posizione);

        assertThat(pendenza.getIuv()).isEqualTo("IUV-pendenza-1");
        assertThat(pendenza.getNumeroAvviso()).isEqualTo("999999999999999999");
    }

    @Test
    @DisplayName("crea non tocca IUV/numero avviso se il chiamante li fornisce entrambi, anche con un GeneratoreIuv disponibile")
    void creaNonSovrascriveIdentificativiForniti() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdA2A("A2A-2");
        posizione.setIdPosizioneDebitoria("pos-2");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdPendenza("pendenza-2");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setImporto(new BigDecimal("10.00"));
        pendenza.setStato(StatoPendenza.NON_ESEGUITA);
        pendenza.setIuv("IUV-FORNITO-DAL-CHIAMANTE");
        pendenza.setNumeroAvviso("111111111111111111");
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-2");
        voce.setImporto(new BigDecimal("10.00"));
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-1");
        pendenza.addVocePendenza(voce);

        service.crea(posizione);

        assertThat(pendenza.getIuv()).isEqualTo("IUV-FORNITO-DAL-CHIAMANTE");
        assertThat(pendenza.getNumeroAvviso()).isEqualTo("111111111111111111");
    }

    @Test
    @DisplayName("crea ricava lo iuv dal numeroAvviso fornito senza generare una coppia nuova (conversione, non generazione)")
    void creaRicavaIuvDaNumeroAvvisoFornito() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdA2A("A2A-3");
        posizione.setIdPosizioneDebitoria("pos-3");
        posizione.setIdDominio(1L);
        posizione.setDescrizione("test");
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdPendenza("pendenza-3");
        pendenza.setIdTipoPendenza(1L);
        pendenza.setImporto(new BigDecimal("10.00"));
        pendenza.setStato(StatoPendenza.NON_ESEGUITA);
        // solo il numeroAvviso e' fornito: lo iuv va ricavato da esso (conversione), non generato.
        pendenza.setNumeroAvviso("222222222222222222");
        opzione.addPendenza(pendenza);

        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-3");
        voce.setImporto(new BigDecimal("10.00"));
        voce.setDescrizione("test");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
        voce.setCodEntrata("SRV-1");
        pendenza.addVocePendenza(voce);

        service.crea(posizione);

        assertThat(pendenza.getIuv()).isEqualTo("IUV-DA-222222222222222222");
        assertThat(pendenza.getNumeroAvviso()).isEqualTo("222222222222222222");
    }

    @TestConfiguration
    static class Config {
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
                    return new IdentificativiPagamento("IUV-DA-" + numeroAvviso, numeroAvviso);
                }
            };
        }
    }
}
