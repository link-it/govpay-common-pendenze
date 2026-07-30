package it.govpay.pendenze.codec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import it.govpay.pendenze.model.Causale;

/**
 * Codifica e decodifica la causale di una pendenza nel formato usato dalla colonna
 * {@code versamenti.causale_versamento}:
 *
 * <pre>
 * 01 &lt;base64&gt;                          causale semplice
 * 02 &lt;base64&gt; [&lt;base64&gt; ...]           causale a spezzoni
 * 03 &lt;base64&gt; &lt;base64importo&gt; [...]    spezzoni con importo, a coppie
 * </pre>
 *
 * <p><b>Tolleranza sui dati legacy.</b> In banca dati esistono valori non conformi
 * (causali salvate in chiaro, base64 malformati). La decodifica non solleva mai
 * eccezioni: quando non riconosce il contenuto restituisce il valore cosi' com'e' come
 * causale semplice, replicando il comportamento della versione 3.x.</p>
 */
public final class CausaleCodec {

    private static final String SEPARATORE = " ";
    private static final String TIPO_SEMPLICE = "01";
    private static final String TIPO_SPEZZONI = "02";
    private static final String TIPO_SPEZZONI_IMPORTO = "03";

    private CausaleCodec() {
    }

    /**
     * Decodifica il valore persistito.
     *
     * @param valore contenuto della colonna, eventualmente {@code null}
     * @return la causale, vuoto se la colonna e' vuota
     */
    public static Optional<Causale> decodifica(String valore) {
        if (valore == null || valore.isBlank()) {
            return Optional.empty();
        }
        String[] parti = valore.trim().split(SEPARATORE);
        try {
            return Optional.of(switch (parti[0]) {
                case TIPO_SEMPLICE -> semplice(parti);
                case TIPO_SPEZZONI -> spezzoni(parti);
                case TIPO_SPEZZONI_IMPORTO -> spezzoniConImporto(parti);
                default -> new Causale.Semplice(valore);
            });
        } catch (RuntimeException e) {
            // Dato non conforme: meglio restituire il valore grezzo che far fallire la
            // lettura dell'intera pendenza.
            return Optional.of(new Causale.Semplice(valore));
        }
    }

    /**
     * Codifica la causale nel formato della colonna.
     *
     * @param causale causale da codificare, eventualmente {@code null}
     * @return il valore da scrivere in colonna, {@code null} se la causale e' assente
     */
    public static String codifica(Causale causale) {
        if (causale == null) {
            return null;
        }
        return switch (causale) {
            case Causale.Semplice semplice ->
                    TIPO_SEMPLICE + SEPARATORE + codificaBase64(semplice.testo());
            case Causale.Spezzoni spezzoni -> {
                StringBuilder sb = new StringBuilder(TIPO_SPEZZONI);
                for (String spezzone : spezzoni.spezzoni()) {
                    sb.append(SEPARATORE).append(codificaBase64(spezzone));
                }
                yield sb.toString();
            }
            case Causale.SpezzoniConImporto conImporto -> {
                StringBuilder sb = new StringBuilder(TIPO_SPEZZONI_IMPORTO);
                for (Causale.VoceCausale voce : conImporto.voci()) {
                    sb.append(SEPARATORE).append(codificaBase64(voce.testo()))
                      .append(SEPARATORE).append(codificaBase64(voce.importo().toPlainString()));
                }
                yield sb.toString();
            }
        };
    }

    /**
     * Forma sintetica della causale, equivalente al {@code getSimple()} della versione
     * 3.x: e' quella che le API espongono quando serve una sola riga di testo. Per gli
     * spezzoni restituisce il primo, per gli spezzoni con importo
     * {@code "<importo>: <spezzone>"}.
     *
     * @param valore contenuto della colonna, eventualmente {@code null}
     * @return la causale sintetica, {@code null} se la colonna e' vuota
     */
    public static String sintesiDa(String valore) {
        return decodifica(valore).map(CausaleCodec::sintesi).orElse(null);
    }

    /**
     * Forma sintetica di una causale gia' decodificata.
     *
     * @param causale causale, non nulla
     * @return la causale sintetica
     */
    public static String sintesi(Causale causale) {
        return switch (causale) {
            case Causale.Semplice semplice -> semplice.testo();
            case Causale.Spezzoni spezzoni -> spezzoni.spezzoni().get(0);
            case Causale.SpezzoniConImporto conImporto -> {
                Causale.VoceCausale prima = conImporto.voci().get(0);
                yield prima.importo().toPlainString() + ": " + prima.testo();
            }
        };
    }

    private static Causale semplice(String[] parti) {
        return new Causale.Semplice(parti.length > 1 ? decodificaBase64(parti[1]) : "");
    }

    private static Causale spezzoni(String[] parti) {
        List<String> spezzoni = new ArrayList<>();
        for (int i = 1; i < parti.length; i++) {
            spezzoni.add(decodificaBase64(parti[i]));
        }
        return new Causale.Spezzoni(spezzoni);
    }

    private static Causale spezzoniConImporto(String[] parti) {
        List<Causale.VoceCausale> voci = new ArrayList<>();
        for (int i = 1; i + 1 < parti.length; i += 2) {
            String testo = decodificaBase64(parti[i]);
            BigDecimal importo = new BigDecimal(decodificaBase64(parti[i + 1]));
            voci.add(new Causale.VoceCausale(testo, importo));
        }
        return new Causale.SpezzoniConImporto(voci);
    }

    private static String codificaBase64(String valore) {
        return Base64.getEncoder().encodeToString(valore.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodificaBase64(String valore) {
        return new String(Base64.getDecoder().decode(valore), StandardCharsets.UTF_8);
    }
}
