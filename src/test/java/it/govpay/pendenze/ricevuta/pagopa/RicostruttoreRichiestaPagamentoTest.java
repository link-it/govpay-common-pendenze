package it.govpay.pendenze.ricevuta.pagopa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferListPAReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Request;
import it.gov.pagopa.pagopa_api.pa.pafornode.StOutcome;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.VocePendenza;

/**
 * Stesse fixture XML di {@code RicevutaCtReceiptMapperTest}/{@code RicevutaCtRicevutaTelematicaMapperTest}
 * in {@code govpay-pendenze-api} (copiate qui, vedi {@code src/test/resources/rt}): verificano
 * la ricostruzione contro RT reali, non solo oggetti costruiti a mano.
 */
class RicostruttoreRichiestaPagamentoTest {

    private static byte[] fixture(String nome) throws IOException {
        try (InputStream in = RicostruttoreRichiestaPagamentoTest.class.getResourceAsStream("/rt/" + nome)) {
            return in.readAllBytes();
        }
    }

    private static Pendenza pendenzaConDataValidita(OffsetDateTime dataValidita) {
        Pendenza pendenza = new Pendenza();
        pendenza.setDataValidita(dataValidita);
        return pendenza;
    }

    @Test
    @DisplayName("ricostruisci (ctReceipt) da XML reale: companyName/importo/debtor/transferList riportati dalla RT, dueDate dalla pendenza")
    void ricostruisciCtReceiptDaXmlReale() throws Exception {
        PaSendRTReq paSendRTReq = JaxbUtils.toPaSendRTReqRT(fixture("rt-v2-ok.xml"));
        OffsetDateTime dataValidita = OffsetDateTime.of(2026, 10, 15, 0, 0, 0, 0, ZoneOffset.UTC);

        var ctPaymentPA = RicostruttoreRichiestaPagamento.ricostruisci(paSendRTReq,
                pendenzaConDataValidita(dataValidita), null);

        assertThat(ctPaymentPA.getCompanyName()).isEqualTo("Comune di Prova");
        assertThat(ctPaymentPA.getCreditorReferenceId()).isEqualTo("IUV900000003");
        assertThat(ctPaymentPA.getPaymentAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(ctPaymentPA.getDescription()).isEqualTo("Pagamento TARI 2026");
        assertThat(ctPaymentPA.getDebtor().getFullName()).isEqualTo("Mario Rossi");
        assertThat(ctPaymentPA.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(ctPaymentPA.getTransferList().getTransfers()).hasSize(1);
        assertThat(ctPaymentPA.getTransferList().getTransfers().get(0).getIBAN())
                .isEqualTo("IT60X0542811101000000123456");
        // mai scritti dalla ricostruzione, a differenza di quanto conterrebbe una vera RPT:
        assertThat(ctPaymentPA.isLastPayment()).isNull();
        assertThat(ctPaymentPA.getRetentionDate()).isNull();
    }

    @Test
    @DisplayName("ricostruisci (ctReceiptV2) da XML reale senza marca da bollo: nessuna richiestaMarcaDaBollo")
    void ricostruisciCtReceiptV2DaXmlReale() throws Exception {
        PaSendRTV2Request paSendRTV2Request = JaxbUtils.toPaSendRTV2RequestRT(fixture("rt-v2_2-ok.xml"));
        Pendenza pendenza = pendenzaConDataValidita(null); // nessuna data -> sentinella

        var ctPaymentPA = RicostruttoreRichiestaPagamento.ricostruisci(paSendRTV2Request, pendenza, null);

        assertThat(ctPaymentPA.getCompanyName()).isEqualTo("Comune di Prova");
        assertThat(ctPaymentPA.getCreditorReferenceId()).isEqualTo("IUV900000001");
        assertThat(ctPaymentPA.getDueDate()).isEqualTo(LocalDate.of(2999, 12, 31));
        assertThat(ctPaymentPA.getTransferList().getTransfers()).hasSize(1);
        assertThat(ctPaymentPA.getTransferList().getTransfers().get(0).getIBAN())
                .isEqualTo("IT60X0542811101000000123456");
        assertThat(ctPaymentPA.getTransferList().getTransfers().get(0).getRichiestaMarcaDaBollo()).isNull();
    }

    @Test
    @DisplayName("dueDate usa dataScadenzaAvviso solo se dataValidita e' assente")
    void dueDateUsaScadenzaAvvisoSoloSeValiditaAssente() throws Exception {
        PaSendRTReq paSendRTReq = JaxbUtils.toPaSendRTReqRT(fixture("rt-v2-ok.xml"));
        Pendenza pendenza = new Pendenza();
        pendenza.setDataScadenzaAvviso(OffsetDateTime.of(2026, 11, 20, 0, 0, 0, 0, ZoneOffset.UTC));

        var ctPaymentPA = RicostruttoreRichiestaPagamento.ricostruisci(paSendRTReq, pendenza, null);

        assertThat(ctPaymentPA.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("dueDate usa dataCreazione + numeroGiorniValiditaPendenzaSenzaData solo se validita'/scadenza sono entrambe assenti")
    void dueDateUsaGiorniValiditaConfiguratiComeFallbackIntermedio() throws Exception {
        PaSendRTReq paSendRTReq = JaxbUtils.toPaSendRTReqRT(fixture("rt-v2-ok.xml"));
        Pendenza pendenza = new Pendenza();
        pendenza.setDataCreazione(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        var ctPaymentPA = RicostruttoreRichiestaPagamento.ricostruisci(paSendRTReq, pendenza, 30);

        assertThat(ctPaymentPA.getDueDate()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    @DisplayName("dueDate resta sulla sentinella 31/12/2999 se validita'/scadenza/giorni sono tutti assenti")
    void dueDateUsaSentinellaSeNessunFallbackDisponibile() throws Exception {
        PaSendRTReq paSendRTReq = JaxbUtils.toPaSendRTReqRT(fixture("rt-v2-ok.xml"));
        Pendenza pendenza = new Pendenza();
        pendenza.setDataCreazione(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        var ctPaymentPA = RicostruttoreRichiestaPagamento.ricostruisci(paSendRTReq, pendenza, null);

        assertThat(ctPaymentPA.getDueDate()).isEqualTo(LocalDate.of(2999, 12, 31));
    }

    @Test
    @DisplayName("un trasferimento con MBDAttachment recupera hash/tipo/provincia dalla voce corrispondente (stesso indice)")
    void ricostruisciCtReceiptV2ConMarcaDaBollo() {
        CtTransferPAReceiptV2 trasferimento = new CtTransferPAReceiptV2();
        trasferimento.setIdTransfer(1);
        trasferimento.setTransferAmount(new BigDecimal("16.00"));
        trasferimento.setFiscalCodePA("12345678901");
        trasferimento.setIBAN("IT60X0542811101000000123456");
        trasferimento.setRemittanceInformation("Marca da bollo digitale");
        trasferimento.setTransferCategory("9/BOLLO/");
        trasferimento.setMBDAttachment(new byte[] {1, 2, 3});

        CtTransferListPAReceiptV2 transferList = new CtTransferListPAReceiptV2();
        transferList.getTransfers().add(trasferimento);

        CtReceiptV2 receipt = new CtReceiptV2();
        receipt.setOutcome(StOutcome.OK);
        receipt.setCreditorReferenceId("IUV-BOLLO-1");
        receipt.setPaymentAmount(new BigDecimal("16.00"));
        receipt.setTransferList(transferList);

        PaSendRTV2Request paSendRTV2Request = new PaSendRTV2Request();
        paSendRTV2Request.setReceipt(receipt);

        VocePendenza voce = new VocePendenza();
        voce.setHashDocumento("aGFzaC1kaS1wcm92YQ==");
        voce.setTipoBollo("01");
        voce.setProvinciaResidenza("RM");

        Pendenza pendenza = new Pendenza();
        pendenza.addVocePendenza(voce);

        var ctPaymentPA = RicostruttoreRichiestaPagamento.ricostruisci(paSendRTV2Request, pendenza, null);

        var marcaBollo = ctPaymentPA.getTransferList().getTransfers().get(0).getRichiestaMarcaDaBollo();
        assertThat(marcaBollo).isNotNull();
        assertThat(marcaBollo.getHashDocumento()).isEqualTo(voce.getHashDocumento().getBytes());
        assertThat(marcaBollo.getTipoBollo()).isEqualTo("01");
        assertThat(marcaBollo.getProvinciaResidenza()).isEqualTo("RM");
    }

    @Test
    @DisplayName("un trasferimento con MBDAttachment ma voce senza tipoBollo ricade sul default \"01\" (IMPOSTA_BOLLO), come il legacy")
    void ricostruisciCtReceiptV2ConMarcaDaBolloSenzaTipoBolloRicadeSuDefault() {
        CtTransferPAReceiptV2 trasferimento = new CtTransferPAReceiptV2();
        trasferimento.setIdTransfer(1);
        trasferimento.setTransferAmount(new BigDecimal("16.00"));
        trasferimento.setIBAN("IT60X0542811101000000123456");
        trasferimento.setRemittanceInformation("Marca da bollo digitale");
        trasferimento.setMBDAttachment(new byte[] {1, 2, 3});

        CtTransferListPAReceiptV2 transferList = new CtTransferListPAReceiptV2();
        transferList.getTransfers().add(trasferimento);

        CtReceiptV2 receipt = new CtReceiptV2();
        receipt.setOutcome(StOutcome.OK);
        receipt.setCreditorReferenceId("IUV-BOLLO-2");
        receipt.setPaymentAmount(new BigDecimal("16.00"));
        receipt.setTransferList(transferList);

        PaSendRTV2Request paSendRTV2Request = new PaSendRTV2Request();
        paSendRTV2Request.setReceipt(receipt);

        VocePendenza voce = new VocePendenza();
        voce.setProvinciaResidenza("RM");
        // tipoBollo deliberatamente non impostato

        Pendenza pendenza = new Pendenza();
        pendenza.addVocePendenza(voce);

        var ctPaymentPA = RicostruttoreRichiestaPagamento.ricostruisci(paSendRTV2Request, pendenza, null);

        var marcaBollo = ctPaymentPA.getTransferList().getTransfers().get(0).getRichiestaMarcaDaBollo();
        assertThat(marcaBollo.getTipoBollo()).isEqualTo("01");
    }
}
