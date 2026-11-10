package it.govpay.pendenze.comando;

import java.time.LocalDate;
import java.util.Objects;

/** Aggiornamento parziale dei soli dati modificabili della posizione. */
public record AggiornamentoPosizioneDebitoria(
        ModificaCampo<String> descrizione,
        ModificaCampo<LocalDate> dataPubblicazione) {
    public AggiornamentoPosizioneDebitoria {
        Objects.requireNonNull(descrizione);
        Objects.requireNonNull(dataPubblicazione);
    }
    public static AggiornamentoPosizioneDebitoria nessunaModifica() {
        return new AggiornamentoPosizioneDebitoria(ModificaCampo.invariato(), ModificaCampo.invariato());
    }
}
