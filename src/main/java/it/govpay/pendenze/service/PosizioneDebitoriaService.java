package it.govpay.pendenze.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.repository.OpzionePagamentoRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;

/**
 * Servizio applicativo sull'aggregato {@link PosizioneDebitoria}.
 *
 * <p><b>Un metodo per evento di dominio, non un "salva" generico</b> (continuita' con D3
 * del disegno precedente): non c'e' un {@code aggiorna(PosizioneDebitoria)} onnicomprensivo,
 * ogni operazione ha un nome e un contratto propri. Questa prima versione copre solo
 * creazione, lettura e la macchina a stati di {@link OpzionePagamento} — le operazioni di
 * aggiornamento sui campi della posizione/pendenza (PATCH, annullamento pendenza, ecc.)
 * sono previste come sviluppo successivo.</p>
 *
 * <p>Gli istanti passano sempre dal {@link Clock} della libreria (bean
 * {@code pendenzeClock}), mai da {@code OffsetDateTime.now()}: stesso principio del
 * disegno precedente (F1-2/D10), cosi' i test possono fissare il tempo e il fuso resta
 * quello configurato, non quello della JVM.</p>
 */
@Service
@Transactional
public class PosizioneDebitoriaService {

    private final PosizioneDebitoriaRepository posizioneDebitoriaRepository;
    private final OpzionePagamentoRepository opzionePagamentoRepository;
    private final Clock clock;

    public PosizioneDebitoriaService(PosizioneDebitoriaRepository posizioneDebitoriaRepository,
            OpzionePagamentoRepository opzionePagamentoRepository, Clock clock) {
        this.posizioneDebitoriaRepository = posizioneDebitoriaRepository;
        this.opzionePagamentoRepository = opzionePagamentoRepository;
        this.clock = clock;
    }

    /**
     * Crea una posizione debitoria con le sue opzioni di pagamento, pendenze e voci, gia'
     * collegate tramite {@code addXxx(...)} dal chiamante. Valorizza qui gli istanti di
     * creazione/aggiornamento su tutta la gerarchia (M10/D10: audit su ogni entita'
     * dell'aggregato, non solo sulla radice) e genera {@link OpzionePagamento#getIdOpzionePagamento()}
     * se assente.
     *
     * <p>Non genera IUV/numero avviso: restano a carico del chiamante in questa prima
     * versione (la generazione, tramite una SPI dedicata come nel disegno precedente, e'
     * sviluppo successivo) — {@link Pendenza#getIuv()}/{@link Pendenza#getNumeroAvviso()}
     * devono quindi essere gia' valorizzati.</p>
     *
     * @param posizione posizione da creare, con l'intero aggregato gia' collegato
     * @return la posizione persistita
     */
    public PosizioneDebitoria crea(PosizioneDebitoria posizione) {
        OffsetDateTime adesso = OffsetDateTime.now(clock);

        posizione.setDataCreazione(adesso);
        posizione.setDataUltimoAggiornamento(adesso);
        posizione.setDataUltimaModificaAca(adesso);

        for (OpzionePagamento opzione : posizione.getOpzioniPagamento()) {
            if (opzione.getIdOpzionePagamento() == null) {
                opzione.setIdOpzionePagamento(UUID.randomUUID());
            }
            if (opzione.getStato() == null) {
                opzione.setStato(StatoOpzionePagamento.DISPONIBILE);
            }
            opzione.setDataCreazione(adesso);
            opzione.setDataUltimoAggiornamento(adesso);

            for (Pendenza pendenza : opzione.getPendenze()) {
                pendenza.setDataCreazione(adesso);
                pendenza.setDataUltimoAggiornamento(adesso);
                pendenza.setDataUltimaModificaAca(adesso);
                if (pendenza.getDataCaricamento() == null) {
                    pendenza.setDataCaricamento(adesso.toLocalDate());
                }
            }
        }

        return posizioneDebitoriaRepository.save(posizione);
    }

    @Transactional(readOnly = true)
    public Optional<PosizioneDebitoria> trovaPerId(Long id) {
        return posizioneDebitoriaRepository.findById(id);
    }

    /**
     * @param idA2A                identificativo del gestionale responsabile
     * @param idPosizioneDebitoria identificativo della posizione nel gestionale
     * @return la posizione, se esiste
     */
    @Transactional(readOnly = true)
    public Optional<PosizioneDebitoria> trovaPerIdentificativo(String idA2A, String idPosizioneDebitoria) {
        return posizioneDebitoriaRepository.findByIdA2AAndIdPosizioneDebitoria(idA2A, idPosizioneDebitoria);
    }

    /**
     * Attiva un'opzione di pagamento a seguito di un pagamento su una delle sue pendenze:
     * la porta ad {@link StatoOpzionePagamento#ATTIVATA} e annulla automaticamente tutte
     * le altre opzioni ancora {@link StatoOpzionePagamento#DISPONIBILE} della stessa
     * posizione debitoria, essendo alternative non piu' applicabili (semantica dello YAML
     * v3, schema {@code StatoOpzionePagamento}).
     *
     * @param idOpzionePagamento identificativo dell'opzione che risulta pagata
     * @return l'opzione appena attivata
     * @throws RisorsaNonTrovataException        se l'opzione non esiste
     * @throws TransizioneStatoNonAmmessaException se l'opzione non e' {@code DISPONIBILE}
     */
    public OpzionePagamento attiva(UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);
        if (opzione.getStato() != StatoOpzionePagamento.DISPONIBILE) {
            throw new TransizioneStatoNonAmmessaException(
                    "l'opzione di pagamento [" + idOpzionePagamento + "] non e' DISPONIBILE (stato attuale: "
                            + opzione.getStato() + "): non puo' essere attivata");
        }

        OffsetDateTime adesso = OffsetDateTime.now(clock);
        opzione.setStato(StatoOpzionePagamento.ATTIVATA);
        opzione.setDataUltimoAggiornamento(adesso);

        for (OpzionePagamento altra : opzione.getPosizioneDebitoria().getOpzioniPagamento()) {
            if (!altra.getId().equals(opzione.getId()) && altra.getStato() == StatoOpzionePagamento.DISPONIBILE) {
                altra.setStato(StatoOpzionePagamento.ANNULLATA);
                altra.setDataUltimoAggiornamento(adesso);
            }
        }

        return opzione;
    }

    /**
     * Annulla manualmente un'opzione di pagamento ancora {@code DISPONIBILE}. Idempotente
     * se gia' {@code ANNULLATA} (stesso comportamento di
     * {@code business.Versamento.annullaVersamento} nel legacy); rifiuta l'annullamento
     * di un'opzione gia' {@code ATTIVATA}, perche' corrisponde a un pagamento gia'
     * eseguito (semantica dello YAML v3).
     *
     * @param idOpzionePagamento identificativo dell'opzione da annullare
     * @return l'opzione annullata
     * @throws RisorsaNonTrovataException        se l'opzione non esiste
     * @throws TransizioneStatoNonAmmessaException se l'opzione e' {@code ATTIVATA}
     */
    public OpzionePagamento annulla(UUID idOpzionePagamento) {
        OpzionePagamento opzione = trovaOpzionePagamento(idOpzionePagamento);

        if (opzione.getStato() == StatoOpzionePagamento.ANNULLATA) {
            return opzione;
        }
        if (opzione.getStato() == StatoOpzionePagamento.ATTIVATA) {
            throw new TransizioneStatoNonAmmessaException(
                    "l'opzione di pagamento [" + idOpzionePagamento
                            + "] e' ATTIVATA: corrisponde a un pagamento gia' eseguito, non puo' essere annullata");
        }

        opzione.setStato(StatoOpzionePagamento.ANNULLATA);
        opzione.setDataUltimoAggiornamento(OffsetDateTime.now(clock));
        return opzione;
    }

    private OpzionePagamento trovaOpzionePagamento(UUID idOpzionePagamento) {
        return opzionePagamentoRepository.findByIdOpzionePagamento(idOpzionePagamento)
                .orElseThrow(() -> new RisorsaNonTrovataException(
                        "nessuna opzione di pagamento con identificativo [" + idOpzionePagamento + "]"));
    }
}
