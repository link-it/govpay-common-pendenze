package it.govpay.pendenze.entity.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.govpay.pendenze.model.TipoBollo;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converte {@code singoli_versamenti.tipo_bollo} (codifica pagoPA, es. {@code 01}) in
 * {@link TipoBollo}. Valore non riconosciuto: {@code null} e log a {@code WARN}.
 */
@Converter
public class TipoBolloConverter implements AttributeConverter<TipoBollo, String> {

    private static final Logger log = LoggerFactory.getLogger(TipoBolloConverter.class);

    @Override
    public String convertToDatabaseColumn(TipoBollo tipo) {
        return tipo == null ? null : tipo.codificaPagoPa();
    }

    @Override
    public TipoBollo convertToEntityAttribute(String codifica) {
        if (codifica == null || codifica.isBlank()) {
            return null;
        }
        return TipoBollo.daCodificaPagoPa(codifica).orElseGet(() -> {
            log.warn("Valore di tipo_bollo non riconosciuto, trattato come assente: [{}]", codifica);
            return null;
        });
    }
}
