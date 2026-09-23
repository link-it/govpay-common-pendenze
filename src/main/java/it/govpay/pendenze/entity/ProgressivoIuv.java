package it.govpay.pendenze.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Contatore progressivo per la generazione di IUV/numero avviso, per chiave
 * {@code (protocollo, infoAssociata)}.
 *
 * <p><b>Non e' una tabella nuova</b>: mappa deliberatamente la stessa tabella fisica
 * {@code ID_MESSAGGIO_RELATIVO} gia' scritta in produzione dal generatore IUV legacy
 * ({@code org.openspcoop2.utils.id.serial.IDSerialGenerator}, invocato da
 * {@code IuvBD.getNextPrgIuv} con {@code protocollo="GovPay"} e
 * {@code infoAssociata=codDominio+iuvPrefix+"NUMERICO"}). Continuando a incrementare la
 * stessa riga, invece di copiarne il valore in una tabella nuova durante la migrazione, i
 * nuovi progressivi ripartono esattamente da dove erano arrivati i vecchi senza bisogno di
 * alcun passo di migrazione dedicato e senza la finestra di corsa fra la lettura di uno
 * snapshot e il cutover (vedi {@code proposta-modello-nativo-v3.md} per l'analisi completa
 * di questa scelta, incluso il punto aperto sugli altri eventuali utilizzatori della stessa
 * tabella a parita' di chiave).</p>
 *
 * <p>Non si riusa pero' la libreria openspcoop2 che la scrive oggi: quel meccanismo richiede
 * una connessione JDBC con autocommit attivo su cui gestisce da solo commit/rollback,
 * incompatibile con una transazione Spring/JPA. L'allocazione a blocchi su questa entita' e'
 * quindi reimplementata in idioma nativo JPA (lock pessimistico + transazione dedicata) in
 * {@code AllocatoreBloccoProgressivoIuv}.</p>
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
