package it.govpay.pendenze.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Contatore progressivo per la generazione di IUV/numero avviso, per chiave
 * {@code (protocollo, infoAssociata)}.
 *
 * <p>Mappa la tabella fisica {@code ID_MESSAGGIO_RELATIVO}, gia' esistente e scritta in
 * produzione con {@code protocollo="GovPay"} — stessa riga, non una tabella nuova (vedi
 * {@code proposta-modello-nativo-v3.md} per l'analisi completa della scelta, incluso il punto
 * aperto sugli altri eventuali utilizzatori della stessa tabella a parita' di chiave).</p>
 *
 * <p>L'allocazione a blocchi su questa entita' e' in {@link
 * it.govpay.pendenze.iuv.AllocatoreBloccoProgressivoIuv} (lock pessimistico + transazione
 * dedicata).</p>
 */
@Entity
@Table(name = "id_messaggio_relativo")
public class ProgressivoIuv {

    @EmbeddedId
    private ProgressivoIuvId id;

    @Column(name = "counter", nullable = false)
    private Long counter;

    protected ProgressivoIuv() {
        // richiesto da JPA
    }

    public ProgressivoIuv(ProgressivoIuvId id, Long counter) {
        this.id = id;
        this.counter = counter;
    }

    public ProgressivoIuvId getId() {
        return id;
    }

    public Long getCounter() {
        return counter;
    }

    public void setCounter(Long counter) {
        this.counter = counter;
    }
}
