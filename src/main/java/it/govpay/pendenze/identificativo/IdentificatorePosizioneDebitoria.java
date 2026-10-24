package it.govpay.pendenze.identificativo;

import java.util.Objects;
import java.util.Optional;

/**
 * Composizione/decomposizione di {@code idPosizioneDebitoria} dello YAML v3 — l'IUPD
 * (standard pagoPA GPD): concatenazione, senza separatore, di {@code idA2A}+{@code idDominio}+
 * {@code codDocumento}. Nessuna colonna persiste questo valore (vedi Javadoc di
 * {@code it.govpay.pendenze.entity.PosizioneDebitoria}) — si compone/decompone al volo a ogni
 * richiesta.
 *
 * <p>La decomposizione e' deterministica senza separatore solo grazie a due vincoli: {@code idA2A}
 * e' sempre gia' noto a priori dal chiamante (arriva separatamente dal path HTTP, mai estratto da
 * questa stringa), e {@code idDominio} ha lunghezza fissa — {@value #LUNGHEZZA_ID_DOMINIO} cifre,
 * il codice fiscale ente pagoPA (verificato nel pattern {@code ^([0-9]){11}$} dello YAML v3). Senza
 * questi due vincoli non ci sarebbe modo di sapere dove finisce {@code idDominio} e inizia
 * {@code codDocumento} in una concatenazione di tre stringhe a lunghezza variabile.</p>
 */
public final class IdentificatorePosizioneDebitoria {

    /** Lunghezza fissa di {@code idDominio} (codice fiscale ente pagoPA): sempre 11 cifre. */
    public static final int LUNGHEZZA_ID_DOMINIO = 11;

    private IdentificatorePosizioneDebitoria() {
    }

    /**
     * @param idA2A        identificativo del gestionale (= {@code Applicazione.codApplicazione})
     * @param idDominio    identificativo del dominio, esattamente {@value #LUNGHEZZA_ID_DOMINIO} cifre
     * @param codDocumento business key scelta dal gestionale
     * @return {@code idPosizioneDebitoria} pubblico (IUPD)
     * @throws IllegalArgumentException se {@code idDominio} non ha la lunghezza attesa
     */
    public static String comporre(String idA2A, String idDominio, String codDocumento) {
        Objects.requireNonNull(idA2A, "idA2A non puo' essere nullo");
        Objects.requireNonNull(idDominio, "idDominio non puo' essere nullo");
        Objects.requireNonNull(codDocumento, "codDocumento non puo' essere nullo");
        if (idDominio.length() != LUNGHEZZA_ID_DOMINIO) {
            throw new IllegalArgumentException("idDominio [" + idDominio + "] deve avere esattamente "
                    + LUNGHEZZA_ID_DOMINIO + " caratteri, ne ha " + idDominio.length());
        }
        return idA2A + idDominio + codDocumento;
    }

    /**
     * Inversa di {@link #comporre}. {@code idA2A} non viene estratto dalla stringa: deve essere
     * fornito dal chiamante (lo conosce gia', dallo stesso path HTTP) e viene usato solo per
     * verificare/strippare il prefisso — un {@code idPosizioneDebitoria} che non inizia con
     * quell'esatto {@code idA2A}, o troppo corto per contenere anche {@code idDominio} e almeno un
     * carattere di {@code codDocumento}, non corrisponde a nessuna posizione reale: {@link Optional#empty()},
     * non un'eccezione — un path sintatticamente malformato per questo schema e' semplicemente "non
     * trovato", non un errore diverso.
     *
     * @param idA2A                identificativo del gestionale, dal path HTTP
     * @param idPosizioneDebitoria valore pubblico opaco da decomporre, dal path HTTP
     * @return i due componenti variabili, se la stringa e' sintatticamente valida per questo schema
     */
    public static Optional<ComponentiIdentificativo> scomponi(String idA2A, String idPosizioneDebitoria) {
        if (idA2A == null || idPosizioneDebitoria == null || !idPosizioneDebitoria.startsWith(idA2A)) {
            return Optional.empty();
        }
        String resto = idPosizioneDebitoria.substring(idA2A.length());
        if (resto.length() <= LUNGHEZZA_ID_DOMINIO) {
            return Optional.empty();
        }
        String idDominio = resto.substring(0, LUNGHEZZA_ID_DOMINIO);
        String codDocumento = resto.substring(LUNGHEZZA_ID_DOMINIO);
        return Optional.of(new ComponentiIdentificativo(idDominio, codDocumento));
    }

    /**
     * I due componenti di {@code idPosizioneDebitoria} variabili rispetto al chiamante
     * ({@code idA2A} e' fisso, noto a priori — vedi Javadoc di {@link #scomponi}).
     */
    public record ComponentiIdentificativo(String idDominio, String codDocumento) {
    }
}
