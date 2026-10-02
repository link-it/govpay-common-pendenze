package it.govpay.pendenze.model;

/**
 * Stato di una {@link it.govpay.pendenze.entity.Rendicontazione} (schema
 * {@code StatoRendicontazione} dello YAML v3).
 */
public enum StatoRendicontazione {
    OK,
    ANOMALA,
    ALTRO_INTERMEDIARIO
}
