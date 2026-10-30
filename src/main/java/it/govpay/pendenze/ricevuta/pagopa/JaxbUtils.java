package it.govpay.pendenze.ricevuta.pagopa;

import java.io.ByteArrayInputStream;

import javax.xml.transform.stream.StreamSource;

import it.gov.digitpa.schemas._2011.pagamenti.RPT;
import it.gov.digitpa.schemas._2011.pagamenti.RT;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Response;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Request;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

/**
 * Unmarshalling JAXB dei tracciati XML PagoPA (RT e RPT, tutti e 3 i formati) verso i modelli
 * generati — stesso pattern e stesse XSD vendorizzate di {@code govpay-console-api.JaxbUtils}.
 *
 * <p>{@link #toRPT(byte[])} (SANP_230) serve solo a leggere {@code dataOraMessaggioRichiesta}
 * per la stampa PDF della ricevuta in quel formato (vedi {@code RicevutaPdfPayloadMapper} in
 * {@code govpay-pendenze-api}) — per {@code ctReceipt}/{@code ctReceiptV2} lo stesso dato e'
 * gia' nella RT ({@code paymentDateTime}). {@link #toPaGetPaymentResRPT(byte[])}/
 * {@link #toPaGetPaymentV2ResponseRPT(byte[])} servono invece l'intero contenuto della
 * richiesta, per il wrapper verso i client v2 (vedi
 * {@link RicostruttoreRichiestaPagamento}) — letta solo se {@code rpt.xml_rpt} e' presente,
 * altrimenti va ricostruita dalla RT.</p>
 *
 * <p>Solo lettura: l'XML proviene dal DB e non viene validato contro gli XSD (la validazione
 * e' gia' garantita a monte dal Nodo).</p>
 */
public final class JaxbUtils {

    /** Tracciati digitpa SANP 2.3.0 (RT "storico", {@code ctRicevutaTelematica}). */
    private static final JAXBContext RPT_RT_CONTEXT = newContext("it.gov.digitpa.schemas._2011.pagamenti");
    /** Tracciati paForNode (SANP 2.4.0 {@code ctReceipt} e 3.2.1 V2 {@code ctReceiptV2}). */
    private static final JAXBContext PA_FOR_NODE_CONTEXT = newContext("it.gov.pagopa.pagopa_api.pa.pafornode");

    private JaxbUtils() {
    }

    private static JAXBContext newContext(String packageName) {
        try {
            return JAXBContext.newInstance(packageName);
        } catch (JAXBException e) {
            throw new IllegalStateException(
                    "Impossibile inizializzare il contesto JAXB per il package " + packageName, e);
        }
    }

    private static <T> T unmarshal(JAXBContext context, byte[] xml, Class<T> type) throws JAXBException {
        Unmarshaller unmarshaller = context.createUnmarshaller();
        return unmarshaller.unmarshal(new StreamSource(new ByteArrayInputStream(xml)), type).getValue();
    }

    public static RT toRT(byte[] xml) throws JAXBException {
        return unmarshal(RPT_RT_CONTEXT, xml, RT.class);
    }

    public static RPT toRPT(byte[] xml) throws JAXBException {
        return unmarshal(RPT_RT_CONTEXT, xml, RPT.class);
    }

    public static PaSendRTReq toPaSendRTReqRT(byte[] xml) throws JAXBException {
        return unmarshal(PA_FOR_NODE_CONTEXT, xml, PaSendRTReq.class);
    }

    public static PaSendRTV2Request toPaSendRTV2RequestRT(byte[] xml) throws JAXBException {
        return unmarshal(PA_FOR_NODE_CONTEXT, xml, PaSendRTV2Request.class);
    }

    /**
     * Richiesta di pagamento (RPT) in formato {@code ctReceipt} (SANP_240/RPTV1_RTV2), quando
     * {@code rpt.xml_rpt} e' presente. Non sempre lo e' (vedi
     * {@link it.govpay.pendenze.ricevuta.pagopa.RicostruttoreRichiestaPagamento}): a
     * differenza di {@link #toRPT(byte[])} (sola lettura di
     * {@code dataOraMessaggioRichiesta} per la stampa SANP_230), qui serve l'intero
     * {@code CtPaymentPA} per popolare il wrapper verso i client v2.
     */
    public static PaGetPaymentRes toPaGetPaymentResRPT(byte[] xml) throws JAXBException {
        return unmarshal(PA_FOR_NODE_CONTEXT, xml, PaGetPaymentRes.class);
    }

    /** Come {@link #toPaGetPaymentResRPT(byte[])}, per il formato {@code ctReceiptV2} (SANP_321_V2/RPTV2_RTV1). */
    public static PaGetPaymentV2Response toPaGetPaymentV2ResponseRPT(byte[] xml) throws JAXBException {
        return unmarshal(PA_FOR_NODE_CONTEXT, xml, PaGetPaymentV2Response.class);
    }
}
