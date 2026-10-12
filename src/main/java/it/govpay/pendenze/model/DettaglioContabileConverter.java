package it.govpay.pendenze.model;

import java.util.List;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converte {@code VocePendenza.dettaglioContabile} da/verso il JSON persistito in colonna.
 * A differenza del vecchio {@code ProprietaPendenzaCodec} (dati storici, decodifica
 * tollerante con log a WARN su JSON malformato), qui non c'e' alcun dato legacy da
 * tollerare: un JSON illeggibile in questa colonna e' un bug di questa libreria, non un
 * dato esterno sporco — fallisce esplicitamente invece di restituire una lista vuota in
 * silenzio.
 *
 * <p><b>{@code writerFor(TIPO_LISTA)}, non {@code writeValueAsString(Object)}.</b> Con
 * quest'ultimo, per l'erasure dei generici, Jackson risolve il serializzatore di ogni
 * elemento sulla sua classe concreta (es. {@code Civilistico}), non sul tipo dichiarato
 * della lista ({@code DettaglioContabile}): il discriminatore {@code tipo} di
 * {@code @JsonTypeInfo} — dichiarato sull'interfaccia, non sulle singole classi — sparisce
 * in scrittura (verificato: funziona per un singolo valore dichiarato come
 * {@code DettaglioContabile}, non per gli elementi di una {@code List<DettaglioContabile>}),
 * e la lettura fallisce poi con "missing type id property". Passare il {@link JavaType}
 * esplicito sia in lettura sia in scrittura rende simmetrico il tipo usato in entrambe le
 * direzioni.</p>
 */
@Converter
public class DettaglioContabileConverter implements AttributeConverter<List<DettaglioContabile>, String> {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final JavaType TIPO_LISTA =
            OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, DettaglioContabile.class);

    @Override
    public String convertToDatabaseColumn(List<DettaglioContabile> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return OBJECT_MAPPER.writerFor(TIPO_LISTA).writeValueAsString(attribute);
    }

    @Override
    public List<DettaglioContabile> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        return OBJECT_MAPPER.readValue(dbData, TIPO_LISTA);
    }
}
