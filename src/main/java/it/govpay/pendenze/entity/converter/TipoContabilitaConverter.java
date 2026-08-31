package it.govpay.pendenze.entity.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.govpay.pendenze.model.TipoContabilita;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converte {@code singoli_versamenti.tipo_contabilita} ({@code 0}..{@code 9}) in
 * {@link TipoContabilita}. Valore non riconosciuto: {@code null} e log a {@code WARN}.
 */
@Converter
public class TipoContabilitaConverter implements AttributeConverter<TipoContabilita, String> {

    private static final Logger log = LoggerFactory.getLogger(TipoContabilitaConverter.class);

    @Override
    public String convertToDatabaseColumn(TipoContabilita tipo) {
        return tipo == null ? null : tipo.codifica();
    }

    @Override
    public TipoContabilita convertToEntityAttribute(String codifica) {
        if (codifica == null || codifica.isBlank()) {
            return null;
        }
        return TipoContabilita.daCodifica(codifica).orElseGet(() -> {
            log.warn("Valore di tipo_contabilita non riconosciuto, trattato come assente: [{}]",
                    codifica);
            return null;
        });
    }
}
