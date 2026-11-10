package it.govpay.pendenze.comando;

import java.util.Objects;

import it.govpay.pendenze.model.TipoSoggetto;

/**
 * Aggiornamento parziale di un debitore di una posizione: anagrafica, notifica SEND. Un campo
 * non indicato resta invariato; {@code ModificaCampo.imposta(null)} azzera i campi facoltativi.
 */
public record DatiAggiornamentoDebitore(
        ModificaCampo<TipoSoggetto> tipo,
        ModificaCampo<String> identificativo,
        ModificaCampo<String> anagrafica,
        ModificaCampo<String> indirizzo,
        ModificaCampo<String> civico,
        ModificaCampo<String> cap,
        ModificaCampo<String> localita,
        ModificaCampo<String> provincia,
        ModificaCampo<String> nazione,
        ModificaCampo<String> email,
        ModificaCampo<String> cellulare,
        ModificaCampo<Boolean> notificaSend,
        ModificaCampo<String> navNotifica) {
    public DatiAggiornamentoDebitore {
        Objects.requireNonNull(tipo);
        Objects.requireNonNull(identificativo);
        Objects.requireNonNull(anagrafica);
        Objects.requireNonNull(indirizzo);
        Objects.requireNonNull(civico);
        Objects.requireNonNull(cap);
        Objects.requireNonNull(localita);
        Objects.requireNonNull(provincia);
        Objects.requireNonNull(nazione);
        Objects.requireNonNull(email);
        Objects.requireNonNull(cellulare);
        Objects.requireNonNull(notificaSend);
        Objects.requireNonNull(navNotifica);
    }

    public static DatiAggiornamentoDebitore nessunaModifica() {
        return new DatiAggiornamentoDebitore(ModificaCampo.invariato(), ModificaCampo.invariato(),
                ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato(),
                ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato(),
                ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato(),
                ModificaCampo.invariato(), ModificaCampo.invariato());
    }
}
