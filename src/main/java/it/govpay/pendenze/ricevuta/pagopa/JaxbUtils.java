package it.govpay.pendenze.ricevuta.pagopa;

import java.io.ByteArrayInputStream;

import javax.xml.transform.stream.StreamSource;

import it.gov.digitpa.schemas._2011.pagamenti.RT;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaSendRTV2Request;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;

/**
 * Unmarshalling JAXB dei tracciati XML PagoPA (RT) verso i modelli generati — stesso pattern
 * e stesse XSD vendorizzate di {@code govpay-console-api.JaxbUtils}, qui ridotto alle sole
 * varianti RT: questa libreria modella ricevute di pagamento ({@link it.govpay.pendenze.entity.Rpt}),
 * non richieste (RPT).
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

    public static PaSendRTReq toPaSendRTReqRT(byte[] xml) throws JAXBException {
        return unmarshal(PA_FOR_NODE_CONTEXT, xml, PaSendRTReq.class);
    }

    public static PaSendRTV2Request toPaSendRTV2RequestRT(byte[] xml) throws JAXBException {
        return unmarshal(PA_FOR_NODE_CONTEXT, xml, PaSendRTV2Request.class);
    }
}
