package it.govpay.pendenze.codec;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import it.govpay.pendenze.model.LinguaSecondaria;
import it.govpay.pendenze.model.ProprietaPendenza;

/**
 * Codifica e decodifica il JSON della colonna {@code versamenti.proprieta}.
 *
 * <p><b>I nomi delle proprieta' sono vincolati dai dati esistenti</b>: la mappatura e'
 * scritta a mano, campo per campo, invece di affidarsi alla convenzione di Jackson,
 * perche' uno dei nomi contiene un refuso storico ({@code dataScandenzaAvviso}) che va
 * riprodotto identico. Se la mappatura fosse automatica, una rinomina del campo Java
 * romperebbe silenziosamente la lettura dello storico.</p>
 *
 * <p>La decodifica e' tollerante: un JSON malformato produce un risultato vuoto e un log
 * a {@code WARN}, non un errore.</p>
 */
public final class ProprietaPendenzaCodec {

    private static final Logger log = LoggerFactory.getLogger(ProprietaPendenzaCodec.class);

    static final String LINGUA_SECONDARIA = "linguaSecondaria";
    static final String DESCRIZIONE_IMPORTO = "descrizioneImporto";
    static final String LINEA_TESTO_RICEVUTA_1 = "lineaTestoRicevuta1";
    static final String LINEA_TESTO_RICEVUTA_2 = "lineaTestoRicevuta2";
    static final String LINGUA_SECONDARIA_CAUSALE = "linguaSecondariaCausale";
    static final String INFORMATIVA_IMPORTO_AVVISO = "informativaImportoAvviso";
    static final String LINGUA_SECONDARIA_INFORMATIVA = "linguaSecondariaInformativaImportoAvviso";
    /** Nome storico, con refuso: non correggere, e' quello presente nei dati. */
    static final String DATA_SCADENZA_AVVISO = "dataScandenzaAvviso";
    static final String VOCE = "voce";
    static final String IMPORTO = "importo";

    private final ObjectMapper objectMapper;

    public ProprietaPendenzaCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Decodifica il JSON persistito.
     *
     * @param json contenuto della colonna, eventualmente {@code null}
     * @return le proprieta', vuoto se la colonna e' vuota o il JSON non e' leggibile
     */
    public Optional<ProprietaPendenza> decodifica(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode radice = objectMapper.readTree(json);
            if (!radice.isObject()) {
                log.warn("Proprieta' pendenza: JSON non e' un oggetto, ignorato");
                return Optional.empty();
            }
            return Optional.of(new ProprietaPendenza(
                    LinguaSecondaria.daCodifica(testo(radice, LINGUA_SECONDARIA)).orElse(null),
                    descrizioneImporto(radice.get(DESCRIZIONE_IMPORTO)),
                    testo(radice, LINEA_TESTO_RICEVUTA_1),
                    testo(radice, LINEA_TESTO_RICEVUTA_2),
                    testo(radice, LINGUA_SECONDARIA_CAUSALE),
                    testo(radice, INFORMATIVA_IMPORTO_AVVISO),
                    testo(radice, LINGUA_SECONDARIA_INFORMATIVA),
                    data(radice, DATA_SCADENZA_AVVISO)));
        } catch (RuntimeException e) {
            log.warn("Proprieta pendenza: JSON non leggibile, ignorato: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Codifica le proprieta' nel JSON da scrivere in colonna. I campi assenti sono omessi.
     *
     * @param proprieta proprieta' da codificare, eventualmente {@code null}
     * @return il JSON da scrivere, {@code null} se le proprieta' sono assenti
     */
    public String codifica(ProprietaPendenza proprieta) {
        if (proprieta == null) {
            return null;
        }
        ObjectNode radice = objectMapper.createObjectNode();

        if (proprieta.linguaSecondaria() != null) {
            radice.put(LINGUA_SECONDARIA, proprieta.linguaSecondaria().codifica());
        }
        if (proprieta.descrizioneImporto() != null) {
            ArrayNode voci = radice.putArray(DESCRIZIONE_IMPORTO);
            for (ProprietaPendenza.VoceDescrizioneImporto voce : proprieta.descrizioneImporto()) {
                ObjectNode nodo = voci.addObject();
                if (voce.voce() != null) {
                    nodo.put(VOCE, voce.voce());
                }
                if (voce.importo() != null) {
                    nodo.put(IMPORTO, voce.importo());
                }
            }
        }
        metti(radice, LINEA_TESTO_RICEVUTA_1, proprieta.lineaTestoRicevuta1());
        metti(radice, LINEA_TESTO_RICEVUTA_2, proprieta.lineaTestoRicevuta2());
        metti(radice, LINGUA_SECONDARIA_CAUSALE, proprieta.linguaSecondariaCausale());
        metti(radice, INFORMATIVA_IMPORTO_AVVISO, proprieta.informativaImportoAvviso());
        metti(radice, LINGUA_SECONDARIA_INFORMATIVA,
                proprieta.linguaSecondariaInformativaImportoAvviso());
        if (proprieta.dataScandenzaAvviso() != null) {
            radice.put(DATA_SCADENZA_AVVISO, proprieta.dataScandenzaAvviso().toString());
        }

        return objectMapper.writeValueAsString(radice);
    }

    private static void metti(ObjectNode nodo, String nome, String valore) {
        if (valore != null) {
            nodo.put(nome, valore);
        }
    }

    private static String testo(JsonNode radice, String nome) {
        JsonNode nodo = radice.get(nome);
        return nodo == null || nodo.isNull() ? null : nodo.asString();
    }

    private static OffsetDateTime data(JsonNode radice, String nome) {
        String valore = testo(radice, nome);
        if (valore == null || valore.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(valore);
        } catch (RuntimeException e) {
            log.warn("Proprieta' pendenza: data [{}] non parseabile: [{}]", nome, valore);
            return null;
        }
    }

    private static List<ProprietaPendenza.VoceDescrizioneImporto> descrizioneImporto(JsonNode nodo) {
        if (nodo == null || !nodo.isArray()) {
            return null;
        }
        List<ProprietaPendenza.VoceDescrizioneImporto> voci = new ArrayList<>();
        for (JsonNode elemento : nodo) {
            JsonNode importo = elemento.get(IMPORTO);
            voci.add(new ProprietaPendenza.VoceDescrizioneImporto(
                    testo(elemento, VOCE),
                    importo == null || importo.isNull() ? null : new BigDecimal(importo.asString())));
        }
        return voci;
    }
}
