package it.govpay.pendenze.model;

/**
 * Tipologia della pendenza, persistita in {@code versamenti.tipo}: distingue le pendenze
 * caricate dall'ente ({@link #DOVUTO}) da quelle generate dal cittadino sul portale dei
 * pagamenti ({@link #SPONTANEO}). La codifica coincide con il nome della costante.
 */
public enum TipologiaTipoVersamento {

    DOVUTO,
    SPONTANEO
}
