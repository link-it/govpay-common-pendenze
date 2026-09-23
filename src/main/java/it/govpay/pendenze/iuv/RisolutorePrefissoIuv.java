package it.govpay.pendenze.iuv;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Risolve i placeholder dinamici del prefisso IUV di dominio (es. {@code %(Y)2026%(a)001}),
 * porting di {@code it.govpay.bd.pagamento.util.CustomIuv.buildPrefix} (che usa
 * {@code org.apache.commons.text.StringSubstitutor} con delimitatori {@code %(}/{@code )}) —
 * reimplementato senza quella dipendenza perché qui il set di chiavi supportate è noto e
 * fisso, non un {@code Map} generico popolato da {@code PagamentoContext}.
 *
 * <p>A differenza del legacy — dove un placeholder senza valore nella mappa resta inalterato
 * nel testo, propagandosi fino a un {@link NumberFormatException} nel calcolo del check digit
 * (vedi {@code Iuv.generaIUV}, che per questo valida esplicitamente il prefisso risolto prima
 * di generare) — qui un placeholder non risolvibile solleva subito un errore esplicito: più
 * chiaro di un crash nel punto sbagliato, e non richiede una validazione separata a valle.</p>
 */
final class RisolutorePrefissoIuv {

    private static final Pattern PLACEHOLDER = Pattern.compile("%\\(([a-zA-Z])\\)");

    private RisolutorePrefissoIuv() {
    }

    /**
     * @param prefix prefisso configurato sul dominio, eventualmente con placeholder; {@code null} trattato come vuoto
     * @param valori valori noti per le chiavi dei placeholder presenti nel prefisso
     * @return il prefisso con ogni {@code %(chiave)} sostituito dal valore corrispondente
     * @throws IllegalStateException se il prefisso contiene un placeholder per cui {@code valori} non ha un valore
     */
    static String risolvi(String prefix, Map<String, String> valori) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(prefix);
        StringBuilder risultato = new StringBuilder();
        while (matcher.find()) {
            String chiave = matcher.group(1);
            String valore = valori.get(chiave);
            if (valore == null) {
                throw new IllegalStateException(
                        "il prefisso IUV [" + prefix + "] contiene il placeholder %(" + chiave
                                + ") che questa libreria non sa ancora risolvere");
            }
            matcher.appendReplacement(risultato, Matcher.quoteReplacement(valore));
        }
        matcher.appendTail(risultato);
        return risultato.toString();
    }
}
