package it.govpay.pendenze.comando;

/** Assente lascia invariato il campo; presente con null ne richiede la rimozione. */
public record ModificaCampo<T>(boolean presente, T valore) {
    public ModificaCampo {
        if (!presente && valore != null) {
            throw new IllegalArgumentException("un campo assente non puo' avere un valore");
        }
    }
    public static <T> ModificaCampo<T> invariato() { return new ModificaCampo<>(false, null); }
    public static <T> ModificaCampo<T> imposta(T valore) { return new ModificaCampo<>(true, valore); }
}
