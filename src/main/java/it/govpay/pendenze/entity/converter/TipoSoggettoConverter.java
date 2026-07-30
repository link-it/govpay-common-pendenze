package it.govpay.pendenze.entity.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.govpay.pendenze.model.TipoSoggetto;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converte {@code versamenti.debitore_tipo} ({@code F}/{@code G}) in
 * {@link TipoSoggetto}.
 *
 * <p>Un valore non riconosciuto produce {@code null} e un log a {@code WARN}: e' un
 * codice accessorio, e una riga anomala non deve far fallire la lettura di un'intera
 * lista di pendenze.</p>
 */
@Converter
public class TipoSoggettoConverter implements AttributeConverter<TipoSoggetto, String> {

    private static final Logger log = LoggerFactory.getLogger(TipoSoggettoConverter.class);

    @Override
    public String convertToDatabaseColumn(TipoSoggetto tipo) {
        return tipo == null ? null : tipo.codifica();
    }

    @Override
    public TipoSoggetto convertToEntityAttribute(String codifica) {
        if (codifica == null || codifica.isBlank()) {
            return null;
        }
        return TipoSoggetto.daCodifica(codifica).orElseGet(() -> {
            log.warn("Valore di debitore_tipo non riconosciuto, trattato come assente: [{}]",
                    codifica);
            return null;
        });
    }
}
