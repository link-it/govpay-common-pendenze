package it.govpay.pendenze.validazione;

import java.math.BigDecimal;
import java.math.RoundingMode;

import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Valida i vincoli semantici di un aggregato {@link PosizioneDebitoria} prima della
 * creazione — quelli che lo schema JSON non può esprimere da solo (cardinalità delle
 * pendenze per tipologia, coerenza fra l'importo di una pendenza e la somma delle sue
 * voci). Le validazioni puramente strutturali (campi obbligatori, lunghezze, pattern)
 * restano a carico del livello che espone i bean API, non di questa libreria.
 *
 * <p>Classe senza stato e senza dipendenze da Spring/JPA/persistenza (stesso principio
 * del package {@code model}): può essere invocata e testata senza un contesto Spring o
 * un database.</p>
 */
public final class ValidatorePosizioneDebitoria {

    private ValidatorePosizioneDebitoria() {
        // utility class
    }

    /**
     * @param posizione posizione da validare, con l'aggregato già collegato
     * @throws ValidazioneNonSuperataException se un vincolo semantico non è rispettato
     */
    public static void valida(PosizioneDebitoria posizione) {
        if (posizione.getSoggettiDebitori().isEmpty()) {
            throw new ValidazioneNonSuperataException(
                    "la posizione debitoria deve avere almeno un soggetto debitore");
        }
        if (posizione.getOpzioniPagamento().isEmpty()) {
            throw new ValidazioneNonSuperataException(
                    "la posizione debitoria deve avere almeno un'opzione di pagamento");
        }
        for (OpzionePagamento opzione : posizione.getOpzioniPagamento()) {
            validaOpzione(opzione);
        }
    }

    private static void validaOpzione(OpzionePagamento opzione) {
        TipologiaOpzionePagamento tipologia = opzione.getTipologia();
        int numeroPendenze = opzione.getPendenze().size();

        if (numeroPendenze < tipologia.cardinalitaPendenzeMinima()) {
            throw new ValidazioneNonSuperataException(
                    "l'opzione di tipo " + tipologia + " richiede almeno "
                            + tipologia.cardinalitaPendenzeMinima() + " pendenza/e, trovate " + numeroPendenze);
        }
        Integer massimo = tipologia.cardinalitaPendenzeMassima();
        if (massimo != null && numeroPendenze > massimo) {
            throw new ValidazioneNonSuperataException(
                    "l'opzione di tipo " + tipologia + " ammette al massimo " + massimo
                            + " pendenza/e, trovate " + numeroPendenze);
        }

        if (tipologia.richiedeGiorni() && (opzione.getGiorni() == null || opzione.getGiorni() <= 0)) {
            throw new ValidazioneNonSuperataException(
                    "l'opzione di tipo " + tipologia + " richiede 'giorni' positivo");
        }
        if (!tipologia.richiedeGiorni() && opzione.getGiorni() != null) {
            throw new ValidazioneNonSuperataException(
                    "l'opzione di tipo " + tipologia + " non ammette 'giorni'");
        }

        for (Pendenza pendenza : opzione.getPendenze()) {
            validaPendenza(pendenza);
        }
    }

    private static void validaPendenza(Pendenza pendenza) {
        int numeroVoci = pendenza.getVoci().size();
        if (numeroVoci < 1 || numeroVoci > 5) {
            throw new ValidazioneNonSuperataException(
                    "la pendenza [" + pendenza.getIdPendenza() + "] deve avere da 1 a 5 voci, trovate "
                            + numeroVoci);
        }

        validaScalaImporto(pendenza.getImporto(), "la pendenza [" + pendenza.getIdPendenza() + "]");

        BigDecimal sommaVoci = BigDecimal.ZERO;
        for (VocePendenza voce : pendenza.getVoci()) {
            validaScalaImporto(voce.getImporto(), "la voce [" + voce.getIdVocePendenza() + "]");
            sommaVoci = sommaVoci.add(voce.getImporto());
        }
        if (sommaVoci.compareTo(pendenza.getImporto()) != 0) {
            throw new ValidazioneNonSuperataException(
                    "la pendenza [" + pendenza.getIdPendenza() + "] ha importo " + pendenza.getImporto()
                            + " ma la somma delle voci e' " + sommaVoci);
        }
    }

    /**
     * Rifiuta un importo con più di 2 decimali significativi, invece di lasciare che sia
     * il database ad arrotondarlo in modo implicito alla colonna {@code NUMERIC(19,2)}.
     * Senza questo controllo, due voci come {@code 0.005} superano la validazione
     * ({@code compareTo} in memoria non distingue {@code 0.005+0.005} da {@code 0.01}),
     * ma dopo il giro in banca dati ciascuna diventa {@code 0.01}: la somma riletta
     * ({@code 0.02}) non coincide più con l'importo della pendenza. Rifiutare qui, prima
     * di persistere, evita che l'incoerenza si manifesti solo alla rilettura.
     *
     * @param importo  valore da controllare
     * @param contesto descrizione dell'entità a cui appartiene, per il messaggio d'errore
     */
    private static void validaScalaImporto(BigDecimal importo, String contesto) {
        try {
            importo.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ValidazioneNonSuperataException(
                    contesto + " ha un importo con più di 2 decimali significativi: " + importo.toPlainString());
        }
    }
}
