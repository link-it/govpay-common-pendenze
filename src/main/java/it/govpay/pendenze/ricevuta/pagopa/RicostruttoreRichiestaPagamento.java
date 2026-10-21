package it.govpay.pendenze.ricevuta.pagopa;

import java.time.LocalDate;
import java.util.List;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceipt;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtRichiestaMarcaDaBollo;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferListPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Request;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.VocePendenza;

/**
 * Ricostruisce il contenuto della richiesta di pagamento (RPT, formati {@code ctReceipt}/
 * {@code ctReceiptV2}) quando {@code rpt.xml_rpt} non e' disponibile — porting di
 * {@code MessaggiPagoPAUtils.ricostruisciPaGetPaymentRes}/{@code ricostruisciPaGetPaymentV2Response}
 * del monolite (jars/core), adattato a {@link Pendenza}/{@link VocePendenza}.
 *
 * <p><b>Perche' serve una ricostruzione</b>: a differenza di SANP_230 (dove GovPay genera
 * davvero un documento RPT e lo persiste), nei formati paForNode GovPay puo' non aver mai
 * ricevuto/salvato l'XML della richiesta (es. pagamento avviato dal Nodo, "standin") — tutta
 * l'informazione utile e' comunque presente nella ricevuta (RT) gia' acquisita, piu' i dati
 * della pendenza stessa (scadenza, voci).</p>
 *
 * <p><b>Restituisce direttamente {@code CtPaymentPA}/{@code CtPaymentPAV2}</b> (il contenuto
 * di {@code PaGetPaymentRes.getData()}/{@code PaGetPaymentV2Response.getData()}), non il
 * wrapper esterno: e' l'unica parte che il chiamante (il wrapper verso i client v2) deve
 * poi serializzare — stessa scelta del legacy ({@code ConverterUtils.getRptJson}, che
 * serializza sempre solo {@code .getData()}).</p>
 *
 * <p><b>Non porta la conversione di retrocompatibilita' verso il vecchio formato V1</b>
 * ({@code CtRichiestaPagamentoTelematico}, {@code MessaggiPagoPAUtils.toCtRichiestaPagamentoTelematico}
 * nel monolite): e' attiva nel legacy solo dietro un flag di configurazione spento di
 * default ({@code it.govpay.retrocompatibilitaMessaggiPagoPA.v1.enable}), pensato per
 * installazioni in migrazione da v1 con client ancora legati al formato vecchio — fuori
 * perimetro finche' non si presenta quel caso.</p>
 */
public final class RicostruttoreRichiestaPagamento {

    /** Sentinella "nessuna scadenza nota" — stesso valore letterale del fallback ultimo di
     *  {@code CtPaymentPABuilder.calcolaDueDate} nel monolite ({@code new Date(32503590000000L)}),
     *  usata solo quando {@code numeroGiorniValiditaPendenzaSenzaData} e' anch'esso assente. */
    private static final LocalDate NESSUNA_SCADENZA_NOTA = LocalDate.of(2999, 12, 31);

    private RicostruttoreRichiestaPagamento() {
    }

    /**
     * Formato {@code ctReceipt} (SANP_240/RPTV1_RTV2).
     *
     * @param paSendRTReq la RT gia' deserializzata ({@link JaxbUtils#toPaSendRTReqRT})
     * @param pendenza    la pendenza associata, per la scadenza (vedi {@link #calcolaDueDate})
     * @param numeroGiorniValiditaPendenzaSenzaData fallback intermedio per la scadenza, da
     *        passare esplicitamente (vedi {@link #calcolaDueDate}): questa libreria non legge
     *        configurazione propria, e' il chiamante (il wrapper) a decidere se e quanto vale
     *        — corrispondente del legacy
     *        {@code it.govpay.modello3.sanp24.giorniValiditaDaAssegnarePendenzaSenzaDataValidita}
     */
    public static CtPaymentPA ricostruisci(PaSendRTReq paSendRTReq, Pendenza pendenza,
            Integer numeroGiorniValiditaPendenzaSenzaData) {
        if (paSendRTReq == null) {
            return null;
        }
        CtReceipt receipt = paSendRTReq.getReceipt();

        CtPaymentPA ctPaymentPA = new CtPaymentPA();
        ctPaymentPA.setCompanyName(receipt.getCompanyName());
        ctPaymentPA.setCreditorReferenceId(receipt.getCreditorReferenceId());
        ctPaymentPA.setDebtor(receipt.getDebtor());
        ctPaymentPA.setDescription(receipt.getDescription());
        ctPaymentPA.setDueDate(calcolaDueDate(pendenza, numeroGiorniValiditaPendenzaSenzaData));
        ctPaymentPA.setLastPayment(null);
        ctPaymentPA.setMetadata(receipt.getMetadata());
        ctPaymentPA.setOfficeName(receipt.getOfficeName());
        ctPaymentPA.setPaymentAmount(receipt.getPaymentAmount());
        ctPaymentPA.setRetentionDate(null);
        // CtReceipt.transferList e' CtTransferListPA, lo stesso tipo richiesto da
        // CtPaymentPA.setTransferList: nessuna conversione, a differenza del formato V2
        // (vedi ricostruisci(PaSendRTV2Request, ...) sotto).
        ctPaymentPA.setTransferList(receipt.getTransferList());

        return ctPaymentPA;
    }

    /**
     * Formato {@code ctReceiptV2} (SANP_321_V2/RPTV2_RTV1).
     *
     * @param paSendRTV2Request la RT gia' deserializzata ({@link JaxbUtils#toPaSendRTV2RequestRT})
     * @param pendenza          la pendenza associata, per la scadenza e per le voci (il dato
     *                          marca da bollo, assente dalla ricevuta, va recuperato da li')
     * @param numeroGiorniValiditaPendenzaSenzaData vedi Javadoc dell'omologo overload sopra
     */
    public static CtPaymentPAV2 ricostruisci(PaSendRTV2Request paSendRTV2Request, Pendenza pendenza,
            Integer numeroGiorniValiditaPendenzaSenzaData) {
        if (paSendRTV2Request == null) {
            return null;
        }
        CtReceiptV2 receipt = paSendRTV2Request.getReceipt();

        CtPaymentPAV2 ctPaymentPA = new CtPaymentPAV2();
        ctPaymentPA.setCompanyName(receipt.getCompanyName());
        ctPaymentPA.setCreditorReferenceId(receipt.getCreditorReferenceId());
        ctPaymentPA.setDebtor(receipt.getDebtor());
        ctPaymentPA.setDescription(receipt.getDescription());
        ctPaymentPA.setDueDate(calcolaDueDate(pendenza, numeroGiorniValiditaPendenzaSenzaData));
        ctPaymentPA.setLastPayment(null);
        ctPaymentPA.setMetadata(receipt.getMetadata());
        ctPaymentPA.setOfficeName(receipt.getOfficeName());
        ctPaymentPA.setPaymentAmount(receipt.getPaymentAmount());
        ctPaymentPA.setRetentionDate(null);

        if (receipt.getTransferList() != null) {
            CtTransferListPAV2 transferList = new CtTransferListPAV2();
            List<CtTransferPAReceiptV2> trasferimentiRicevuta = receipt.getTransferList().getTransfers();
            List<VocePendenza> voci = pendenza.getVoci();
            for (int indice = 0; indice < trasferimentiRicevuta.size(); indice++) {
                VocePendenza voce = indice < voci.size() ? voci.get(indice) : null;
                transferList.getTransfers().add(toCtTransferPAV2(trasferimentiRicevuta.get(indice), voce));
            }
            ctPaymentPA.setTransferList(transferList);
        }

        return ctPaymentPA;
    }

    /**
     * Un trasferimento della ricevuta ({@code CtTransferPAReceiptV2}, tipo diverso da quello
     * richiesto dalla richiesta) nell'equivalente lato richiesta ({@code CtTransferPAV2}). Il
     * dato marca da bollo, assente dalla ricevuta, viene recuperato dalla voce corrispondente
     * della pendenza (stesso indice) solo se la ricevuta segnala un allegato per quel
     * trasferimento ({@code getMBDAttachment() != null}) — altrimenti non e' una voce bollo.
     */
    /** Unico valore mai esistito nel legacy ({@code SingoloVersamento.TipoBollo.IMPOSTA_BOLLO}),
     *  usato come default quando la voce non specifica un tipo bollo. */
    private static final String TIPO_BOLLO_DEFAULT = "01";

    private static CtTransferPAV2 toCtTransferPAV2(CtTransferPAReceiptV2 trasferimentoRicevuta, VocePendenza voce) {
        CtTransferPAV2 ctTransferPAV2 = new CtTransferPAV2();
        ctTransferPAV2.setCompanyName(trasferimentoRicevuta.getCompanyName());
        ctTransferPAV2.setFiscalCodePA(trasferimentoRicevuta.getFiscalCodePA());
        ctTransferPAV2.setIBAN(trasferimentoRicevuta.getIBAN());
        ctTransferPAV2.setIdTransfer(trasferimentoRicevuta.getIdTransfer());
        ctTransferPAV2.setMetadata(trasferimentoRicevuta.getMetadata());
        ctTransferPAV2.setRemittanceInformation(trasferimentoRicevuta.getRemittanceInformation());
        ctTransferPAV2.setTransferAmount(trasferimentoRicevuta.getTransferAmount());
        ctTransferPAV2.setTransferCategory(trasferimentoRicevuta.getTransferCategory());

        if (trasferimentoRicevuta.getMBDAttachment() != null && voce != null) {
            CtRichiestaMarcaDaBollo marcaBollo = new CtRichiestaMarcaDaBollo();
            if (voce.getHashDocumento() != null) {
                marcaBollo.setHashDocumento(voce.getHashDocumento().getBytes());
            }
            marcaBollo.setProvinciaResidenza(voce.getProvinciaResidenza());
            marcaBollo.setTipoBollo(voce.getTipoBollo() != null ? voce.getTipoBollo() : TIPO_BOLLO_DEFAULT);
            ctTransferPAV2.setRichiestaMarcaDaBollo(marcaBollo);
        }

        return ctTransferPAV2;
    }

    /**
     * Stessa catena di fallback di {@code CtPaymentPABuilder.calcolaDueDate} nel monolite:
     * {@code dataValidita} → {@code dataScadenzaAvviso} → {@code dataCreazione +}
     * {@code numeroGiorniValiditaPendenzaSenzaData} (se fornito dal chiamante) →
     * {@link #NESSUNA_SCADENZA_NOTA}. Il livello intermedio e' passato esplicitamente invece
     * di essere letto da una configurazione propria: questa libreria non ha un proprio
     * sistema di configurazione (M4), e il valore e' comunque gia' noto al chiamante (il
     * wrapper v2, che legge la configurazione legacy).
     */
    private static LocalDate calcolaDueDate(Pendenza pendenza, Integer numeroGiorniValiditaPendenzaSenzaData) {
        if (pendenza.getDataValidita() != null) {
            return pendenza.getDataValidita().toLocalDate();
        }
        if (pendenza.getDataScadenzaAvviso() != null) {
            return pendenza.getDataScadenzaAvviso().toLocalDate();
        }
        if (numeroGiorniValiditaPendenzaSenzaData != null) {
            return pendenza.getDataCreazione().toLocalDate().plusDays(numeroGiorniValiditaPendenzaSenzaData);
        }
        return NESSUNA_SCADENZA_NOTA;
    }
}
