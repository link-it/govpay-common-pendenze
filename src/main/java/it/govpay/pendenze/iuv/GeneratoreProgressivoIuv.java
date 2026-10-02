package it.govpay.pendenze.iuv;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Distribuisce progressivi per chiave, riservando un blocco alla volta da
 * {@link AllocatoreBloccoProgressivoIuv} e tenendone il resto in un buffer in memoria — stesso
 * principio del buffer statico per JVM del generatore legacy
 * ({@code org.openspcoop2.utils.id.serial.IDSerialGeneratorBuffer}): un riavvio del processo
 * perde il buffer residuo (buchi accettabili), non tocca il DB a ogni singolo IUV.
 *
 * <p>Due chiamate concorrenti che trovano entrambe il buffer vuoto per la stessa chiave
 * possono allocare due blocchi distinti invece di uno: nessuna duplicazione (i blocchi non si
 * sovrappongono mai), solo un margine di spreco nel caso raro — stesso comportamento del
 * meccanismo legacy, che non serializza la decisione "buffer vuoto, vado sul DB" fra thread.</p>
 */
@Service
public class GeneratoreProgressivoIuv {

    static final int AMPIEZZA_BLOCCO = 100;

    private final AllocatoreBloccoProgressivoIuv allocatore;
    private final Map<String, Deque<Long>> buffer = new ConcurrentHashMap<>();

    public GeneratoreProgressivoIuv(AllocatoreBloccoProgressivoIuv allocatore) {
        this.allocatore = allocatore;
    }

    public long prossimoValore(String chiave) {
        Deque<Long> valoriRiservati = buffer.computeIfAbsent(chiave, k -> new ConcurrentLinkedDeque<>());
        Long valore = valoriRiservati.pollFirst();
        if (valore != null) {
            return valore;
        }

        List<Long> nuovoBlocco = riservaBloccoConRetry(chiave);
        valoriRiservati.addAll(nuovoBlocco.subList(1, nuovoBlocco.size()));
        return nuovoBlocco.get(0);
    }

    /**
     * Riprova una volta sola: se la riga per {@code chiave} non esisteva ancora, due
     * allocazioni concorrenti possono entrambe tentare di crearla — la seconda fallisce sul
     * vincolo di unicita' e deve rileggerla (a quel punto esiste gia', quindi la seconda
     * chiamata segue il ramo di incremento, non di inserimento).
     */
    private List<Long> riservaBloccoConRetry(String chiave) {
        try {
            return allocatore.allocaBlocco(chiave, AMPIEZZA_BLOCCO);
        } catch (DataIntegrityViolationException primaRigaCreataDaAltraChiamataConcorrente) {
            return allocatore.allocaBlocco(chiave, AMPIEZZA_BLOCCO);
        }
    }
}
