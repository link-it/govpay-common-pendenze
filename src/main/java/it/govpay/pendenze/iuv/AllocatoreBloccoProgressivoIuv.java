package it.govpay.pendenze.iuv;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.pendenze.entity.ProgressivoIuv;
import it.govpay.pendenze.entity.ProgressivoIuvId;
import it.govpay.pendenze.repository.ProgressivoIuvRepository;

/**
 * Alloca un blocco di valori progressivi in una transazione dedicata, indipendente da quella
 * del chiamante (equivalente nativo JPA della connessione separata che il generatore IUV
 * legacy apre quando e' gia' in una transazione — vedi {@code ProgressivoIuv}): se la
 * {@link it.govpay.pendenze.entity.PosizioneDebitoria} in creazione va in rollback, il
 * blocco gia' consegnato resta consumato, non torna disponibile (buchi accettabili,
 * duplicati no — stessa garanzia del meccanismo legacy).
 *
 * <p>Classe separata da {@link GeneratoreProgressivoIuv} apposta: {@code @Transactional} su
 * un metodo richiamato dalla stessa istanza (self-invocation) non passerebbe dal proxy di
 * Spring e {@code REQUIRES_NEW} non avrebbe alcun effetto.</p>
 */
@Service
public class AllocatoreBloccoProgressivoIuv {

    static final String PROTOCOLLO = "GovPay";

    private final ProgressivoIuvRepository repository;

    public AllocatoreBloccoProgressivoIuv(ProgressivoIuvRepository repository) {
        this.repository = repository;
    }

    /**
     * @param chiave           {@code codDominio+iuvPrefix+tipo}, stessa composizione della
     *                         {@code informazioneAssociataAlProgressivo} legacy
     * @param ampiezzaBlocco   quanti valori riservare in questa chiamata
     * @return i valori riservati, in ordine crescente
     * @throws DataIntegrityViolationException se due chiamate concorrenti tentano di creare la
     *         riga per la stessa chiave per la prima volta contemporaneamente: il chiamante
     *         deve riprovare, la seconda volta trovera' la riga gia' creata dall'altra
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> allocaBlocco(String chiave, int ampiezzaBlocco) {
        ProgressivoIuvId id = new ProgressivoIuvId(PROTOCOLLO, chiave);
        Optional<ProgressivoIuv> esistente = repository.findByIdForUpdate(id);

        long base;
        if (esistente.isPresent()) {
            ProgressivoIuv progressivo = esistente.get();
            base = progressivo.getCounter();
            progressivo.setCounter(base + ampiezzaBlocco);
            repository.save(progressivo);
        } else {
            base = 0;
            repository.saveAndFlush(new ProgressivoIuv(id, (long) ampiezzaBlocco));
        }

        return LongStream.rangeClosed(base + 1, base + ampiezzaBlocco).boxed().toList();
    }
}
