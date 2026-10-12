package it.govpay.pendenze.comando;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Aggiornamento parziale dei soli dati modificabili della posizione. */
public record AggiornamentoPosizioneDebitoria(
        ModificaCampo<String> descrizione,
        ModificaCampo<LocalDate> dataPubblicazione,
        ModificaCampo<Boolean> notificaSend,
        ModificaCampo<String> navNotifica,
        ModificaCampo<List<DatiSoggettoDebitore>> soggettiDebitori) {
    public AggiornamentoPosizioneDebitoria {
        Objects.requireNonNull(descrizione);
        Objects.requireNonNull(dataPubblicazione);
        Objects.requireNonNull(notificaSend);
        Objects.requireNonNull(navNotifica);
        Objects.requireNonNull(soggettiDebitori);
        if (soggettiDebitori.presente() && soggettiDebitori.valore() != null) {
            soggettiDebitori = ModificaCampo.imposta(List.copyOf(soggettiDebitori.valore()));
        }
    }
    public static AggiornamentoPosizioneDebitoria nessunaModifica() {
        return new AggiornamentoPosizioneDebitoria(ModificaCampo.invariato(), ModificaCampo.invariato(),
                ModificaCampo.invariato(), ModificaCampo.invariato(), ModificaCampo.invariato());
    }
}
