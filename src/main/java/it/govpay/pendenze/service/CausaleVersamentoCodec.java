package it.govpay.pendenze.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import it.govpay.pendenze.exception.CausaleNonLeggibileException;

/**
 * Codifica/decodifica di {@code versamenti.causale_versamento} nel formato legacy
 * {@code CausaleSemplice} del monolite ({@code it.govpay.model.Versamento.Causale}):
 * {@code "01 "+base64(causale)}. L'unico formato che questo codec produce o accetta — vedi
 * {@link CausaleNonLeggibileException}.
 *
 * <p>Il formato persiste cosi' com'e' nella colonna anche dopo il passaggio da questa classe
 * (mai decodificato a livello di entity, vedi Javadoc di {@link it.govpay.pendenze.entity.Pendenza
 * #getCausaleVersamento}): {@link #decode}/{@link #encode} traducono verso/da il valore in
 * chiaro usato dai comandi ({@code DatiPendenza}/{@code AggiornamentoPendenza}) e dai mapper
 * delle API (v2 e v3-nativa) che espongono {@code causale} ai chiamanti.</p>
 */
public final class CausaleVersamentoCodec {

    private static final String PREFISSO = "01";

    private CausaleVersamentoCodec() {
    }

    /**
     * @param causaleVersamento valore grezzo della colonna, oppure {@code null}
     * @return la causale in chiaro, {@code null} se l'ingresso e' {@code null}
     * @throws CausaleNonLeggibileException se il formato non e' {@code "01"}
     */
    public static String decode(String causaleVersamento) {
        if (causaleVersamento == null) {
            return null;
        }
        if (!causaleVersamento.startsWith(PREFISSO)) {
            throw new CausaleNonLeggibileException(
                    "causale_versamento non e' in formato \"01\" (CausaleSemplice): [" + causaleVersamento + "]");
        }
        // Il segmento base64 segue il prefisso e uno spazio ("01 xxxx") — strip() invece di un
        // offset fisso perche' il decoder Java, a differenza di quello usato dalla vista SQL
        // di govpay-aca-batch (v_versamenti_gpd), non tollera lo spazio iniziale.
        String base64 = causaleVersamento.substring(PREFISSO.length()).strip();
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    /**
     * @param causale testo in chiaro, oppure {@code null}
     * @return il valore da scrivere nella colonna, {@code null} se l'ingresso e' {@code null}
     */
    public static String encode(String causale) {
        if (causale == null) {
            return null;
        }
        return PREFISSO + " " + Base64.getEncoder().encodeToString(causale.getBytes(StandardCharsets.UTF_8));
    }
}
