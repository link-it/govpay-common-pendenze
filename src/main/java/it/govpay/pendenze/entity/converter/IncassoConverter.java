package it.govpay.pendenze.entity.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converte il flag {@code versamenti.incasso}, persistito come singolo carattere
 * {@code 't'}/{@code 'f'}, in {@link Boolean}.
 *
 * <p>Il {@code null} e' significativo e viene preservato: indica che l'informazione non e'
 * stata specificata, che non e' la stessa cosa di {@code false}. Un carattere diverso da
 * quelli attesi produce {@code null} e un log a {@code WARN}.</p>
 */
@Converter
public class IncassoConverter implements AttributeConverter<Boolean, String> {

    private static final Logger log = LoggerFactory.getLogger(IncassoConverter.class);

    /** Valore persistito per {@code true}. */
    public static final String VERO = "t";

    /** Valore persistito per {@code false}. */
    public static final String FALSO = "f";

    @Override
    public String convertToDatabaseColumn(Boolean incasso) {
        if (incasso == null) {
            return null;
        }
        return Boolean.TRUE.equals(incasso) ? VERO : FALSO;
    }

    @Override
    public Boolean convertToEntityAttribute(String valore) {
        if (valore == null || valore.isBlank()) {
            return null;
        }
        String normalizzato = valore.trim();
        if (VERO.equalsIgnoreCase(normalizzato)) {
            return Boolean.TRUE;
        }
        if (FALSO.equalsIgnoreCase(normalizzato)) {
            return Boolean.FALSE;
        }
        log.warn("Valore di versamenti.incasso non riconosciuto, trattato come assente: [{}]",
                normalizzato);
        return null;
    }
}
