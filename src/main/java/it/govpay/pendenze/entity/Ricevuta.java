package it.govpay.pendenze.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import it.govpay.pendenze.model.TipoRicevuta;

/**
 * Ricevuta di pagamento (RT) pagoPA di una {@link Pendenza} (schema {@code Ricevuta} dello
 * YAML v3), mappata sulla tabella {@code ricevute}.
 *
 * <p><b>Contenuto conservato come blob XML opaco</b> ({@link #contenuto}), non come
 * gerarchia di entità/record tipizzati per le tre varianti ({@code ctRicevutaTelematica}/
 * {@code ctReceipt}/{@code ctReceiptV2}, decine di campi profondamente annidati): questa
 * libreria non ha alcuna regola di validazione sul contenuto della ricevuta (lo YAML espone
 * solo lettura, il contenuto è prodotto integralmente da pagoPA) — lo conserva e lo
 * restituisce così com'è, senza interpretarlo. Stesso formato del legacy, deliberatamente
 * (decisione del lead, 2026-09-24): la tabella {@code rpt} conserva l'RT originale in
 * {@code xml_rt BYTEA} (XML grezzo) più una manciata di colonne indicizzate estratte (qui:
 * {@link #iur}, {@link #tipo}, {@link #data}); {@link #contenuto} fa lo stesso, per rendere
 * banale la migrazione dei dati esistenti (copia diretta del blob, nessuna conversione
 * XML→JSON da scrivere/validare in fase di migrazione). La conversione in forma JSON per lo
 * schema {@code Ricevuta} dello YAML v3 avviene a runtime, nel livello che espone l'endpoint
 * {@code GET .../ricevute}, fuori da questa libreria.</p>
 *
 * <p><b>Fuori dall'aggregato {@link PosizioneDebitoria}</b> (decisione del lead, 2026-09-24):
 * {@link #idPendenza} è una FK piatta, non una relazione JPA — stesso principio di
 * {@link Rendicontazione}, per evitare di ripetere il problema del vecchio "dettaglio
 * pendenza" (centinaia di query per una singola lettura).</p>
 */
@Entity
@Table(name = "ricevute", uniqueConstraints = @UniqueConstraint(
        name = "unique_ricevute_1", columnNames = {"id_pendenza", "iur"}))
@SequenceGenerator(name = "seq_ricevute", sequenceName = "seq_ricevute", allocationSize = 1)
public class Ricevuta {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_ricevute")
    @Column(name = "id")
    private Long id;

    /** FK piatta verso {@link Pendenza}: vedi nota di classe sul perimetro dell'aggregato. */
    @Column(name = "id_pendenza", nullable = false)
    private Long idPendenza;

    @Column(name = "iur", nullable = false, length = 35)
    private String iur;

    @Column(name = "tipo", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private TipoRicevuta tipo;

    /** Data di acquisizione della ricevuta. */
    @Column(name = "data", nullable = false)
    private OffsetDateTime data;

    /**
     * Corpo completo della ricevuta, in XML grezzo esattamente come prodotto da pagoPA (stesso
     * formato del legacy) — vedi nota di classe.
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "contenuto", nullable = false)
    private String contenuto;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdPendenza() {
        return idPendenza;
    }

    public void setIdPendenza(Long idPendenza) {
        this.idPendenza = idPendenza;
    }

    public String getIur() {
        return iur;
    }

    public void setIur(String iur) {
        this.iur = iur;
    }

    public TipoRicevuta getTipo() {
        return tipo;
    }

    public void setTipo(TipoRicevuta tipo) {
        this.tipo = tipo;
    }

    public OffsetDateTime getData() {
        return data;
    }

    public void setData(OffsetDateTime data) {
        this.data = data;
    }

    public String getContenuto() {
        return contenuto;
    }

    public void setContenuto(String contenuto) {
        this.contenuto = contenuto;
    }
}
