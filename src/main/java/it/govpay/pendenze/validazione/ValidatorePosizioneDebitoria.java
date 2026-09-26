package it.govpay.pendenze.validazione;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
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
        validaSpeseNotificaEnotificaSend(posizione);
        validaNumeroAvvisoNonDuplicatoNellAggregato(posizione);
    }

    /**
     * Rifiuta due pendenze dello stesso {@code numeroAvviso} gia' all'interno dello stesso
     * aggregato in ingresso (bug del lead, 2026-09-26): il controllo del servizio contro il
     * DB ({@code PosizioneDebitoriaService#verificaNumeroAvvisoNonDuplicato}) confronta ogni
     * pendenza contro le righe gia' persistite, non contro le altre pendenze della stessa
     * richiesta ancora in memoria — due pendenze duplicate nella stessa richiesta superano
     * entrambe quel controllo (nessuna delle due e' ancora su DB quando vengono verificate) e
     * vengono salvate entrambe. Riproducibile senza concorrenza, va risolto qui: un controllo
     * puramente strutturale sull'aggregato, non serve alcun accesso al DB.
     */
    private static void validaNumeroAvvisoNonDuplicatoNellAggregato(PosizioneDebitoria posizione) {
        Set<String> numeriAvviso = new HashSet<>();
        for (OpzionePagamento opzione : posizione.getOpzioniPagamento()) {
            for (Pendenza pendenza : opzione.getPendenze()) {
                String numeroAvviso = pendenza.getNumeroAvviso();
                if (numeroAvviso != null && !numeriAvviso.add(numeroAvviso)) {
                    throw new ValidazioneNonSuperataException(
                            "piu' pendenze della stessa richiesta hanno lo stesso numeroAvviso ["
                                    + numeroAvviso + "]");
                }
            }
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

        validaScalaImporto(BigDecimal.valueOf(pendenza.getImporto()), "la pendenza [" + pendenza.getIdPendenza() + "]");

        BigDecimal sommaVoci = BigDecimal.ZERO;
        for (VocePendenza voce : pendenza.getVoci()) {
            validaScalaImporto(BigDecimal.valueOf(voce.getImporto()), "la voce [" + voce.getIdVocePendenza() + "]");
            sommaVoci = sommaVoci.add(BigDecimal.valueOf(voce.getImporto()));
            validaDettaglioContabile(voce);
        }
        if (sommaVoci.compareTo(BigDecimal.valueOf(pendenza.getImporto())) != 0) {
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

    /**
     * {@code dettaglioContabile} e' ammesso solo per {@code RIFERIMENTO_ENTRATA}/
     * {@code ENTRATA} (mai {@code BOLLO}, che si classifica solo tramite {@code tassonomia}
     * — vedi lo YAML v3). Ogni voce dell'elenco e' poi validata per tipologia: vincoli di
     * "esattamente uno tra N campi"/"alternativi" che un record da solo non può esprimere,
     * e il rifiuto di {@code UNKNOWN_ENTRIES} (sola lettura, mai da GovPay in scrittura).
     */
    private static void validaDettaglioContabile(VocePendenza voce) {
        if (voce.getDettaglioContabile().isEmpty()) {
            return;
        }
        if (voce.getTipoRiferimento() == TipoRiferimentoVocePendenza.BOLLO) {
            throw new ValidazioneNonSuperataException(
                    "la voce [" + voce.getIdVocePendenza() + "] e' di tipo BOLLO: non ammette dettaglioContabile"
                            + " (la classificazione avviene solo tramite tassonomia)");
        }
        for (DettaglioContabile dettaglio : voce.getDettaglioContabile()) {
            validaVoceDettaglioContabile(voce, dettaglio);
        }
    }

    private static void validaVoceDettaglioContabile(VocePendenza voce, DettaglioContabile dettaglio) {
        if (dettaglio instanceof DettaglioContabile.Sconosciuto) {
            throw new ValidazioneNonSuperataException(
                    "la voce [" + voce.getIdVocePendenza() + "] contiene un dettaglioContabile di tipo"
                            + " UNKNOWN_ENTRIES: e' generato solo in lettura, non ammesso in scrittura");
        } else if (dettaglio instanceof DettaglioContabile.CorrispettivoDl118 d) {
            richiedeEsattamenteUno(voce, "CORRISPETTIVO_DL118",
                    "capitolo", d.capitolo(), "accertamento", d.accertamento(),
                    "pianoFinanziario5Livello", d.pianoFinanziario5Livello());
        } else if (dettaglio instanceof DettaglioContabile.Civilistico d) {
            richiedeEsattamenteUno(voce, "CIVILISTICO",
                    "conto", d.conto(), "commessa", d.commessa(), "nrDocumento", d.nrDocumento());
        } else if (dettaglio instanceof DettaglioContabile.IncassoTipico d
                && d.emissioneFattura() != null && d.nrDocumento() != null) {
            throw new ValidazioneNonSuperataException(
                    "la voce [" + voce.getIdVocePendenza() + "] ha un dettaglioContabile INCASSO_TIPICO con sia"
                            + " emissioneFattura sia nrDocumento: sono alternativi, non ammessi insieme");
        }
        // SpeseNotifica: nessun vincolo di campo qui, solo l'incrocio con notificaSend
        // (validaSpeseNotificaEnotificaSend, sull'intera posizione).
    }

    private static void richiedeEsattamenteUno(VocePendenza voce, String tipo, String nome1, Object valore1,
            String nome2, Object valore2, String nome3, Object valore3) {
        int presenti = (valore1 != null ? 1 : 0) + (valore2 != null ? 1 : 0) + (valore3 != null ? 1 : 0);
        if (presenti != 1) {
            throw new ValidazioneNonSuperataException(
                    "la voce [" + voce.getIdVocePendenza() + "] ha un dettaglioContabile " + tipo + " con "
                            + presenti + " tra " + nome1 + "/" + nome2 + "/" + nome3
                            + " valorizzati: deve essere presente esattamente uno");
        }
    }

    /**
     * {@code SPESE_NOTIFICA} va indicato solo se l'importo della voce include già le spese
     * di notifica SEND calcolate autonomamente dall'applicativo — in quel caso
     * {@code notificaSend} non deve essere attivo sulla posizione, altrimenti le spese
     * verrebbero applicate due volte (semantica esplicita dello YAML v3).
     */
    private static void validaSpeseNotificaEnotificaSend(PosizioneDebitoria posizione) {
        if (!posizione.isNotificaSend()) {
            return;
        }
        boolean presenteSpeseNotifica = posizione.getOpzioniPagamento().stream()
                .flatMap(o -> o.getPendenze().stream())
                .flatMap(p -> p.getVoci().stream())
                .flatMap(v -> v.getDettaglioContabile().stream())
                .anyMatch(DettaglioContabile.SpeseNotifica.class::isInstance);
        if (presenteSpeseNotifica) {
            throw new ValidazioneNonSuperataException(
                    "notificaSend e' attivo ma una voce ha gia' un dettaglioContabile SPESE_NOTIFICA: le spese"
                            + " verrebbero applicate due volte");
        }
    }
}
