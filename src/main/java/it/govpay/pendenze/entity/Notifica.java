package it.govpay.pendenze.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import it.govpay.pendenze.model.StatoSpedizioneNotifica;
import it.govpay.pendenze.model.TipoNotifica;

/**
 * Notifica in coda verso il connettore di integrazione di un'applicazione (es. attivazione
 * di una RPT), mappata sulla tabella legacy {@code notifiche}: riuso diretto, stesso
 * principio di {@link Rpt}/{@link Pagamento}.
 *
 * <p>{@link #idRpt}/{@link #idApplicazione} sono FK piatte (non relazioni JPA), stesso
 * principio di {@link Rpt#getIdVersamento()}: {@link #idApplicazione} referenzia
 * {@code ApplicazioneEntity} di govpay-common, fuori da questa libreria.</p>
 *
 * <p>{@link #tipo} e' fisicamente la colonna {@code tipo_esito} (nome legacy).</p>
 */
@Entity
@Table(name = "notifiche")
@SequenceGenerator(name = "seq_notifiche", sequenceName = "seq_notifiche", allocationSize = 1)
public class Notifica {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_notifiche")
    @Column(name = "id")
    private Long id;

    @Column(name = "id_applicazione", nullable = false)
    private Long idApplicazione;

    @Column(name = "id_rpt")
    private Long idRpt;

    @Column(name = "tipo_esito", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private TipoNotifica tipo;

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    @Column(name = "stato", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private StatoSpedizioneNotifica stato;

    @Column(name = "descrizione_stato", length = 255)
    private String descrizioneStato;

    @Column(name = "data_aggiornamento_stato", nullable = false)
    private OffsetDateTime dataAggiornamentoStato;

    @Column(name = "data_prossima_spedizione", nullable = false)
    private OffsetDateTime dataProssimaSpedizione;

    @Column(name = "tentativi_spedizione")
    private Long tentativiSpedizione;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdApplicazione() {
        return idApplicazione;
    }

    public void setIdApplicazione(Long idApplicazione) {
        this.idApplicazione = idApplicazione;
    }

    public Long getIdRpt() {
        return idRpt;
    }

    public void setIdRpt(Long idRpt) {
        this.idRpt = idRpt;
    }

    public TipoNotifica getTipo() {
        return tipo;
    }

    public void setTipo(TipoNotifica tipo) {
        this.tipo = tipo;
    }

    public OffsetDateTime getDataCreazione() {
        return dataCreazione;
    }

    public void setDataCreazione(OffsetDateTime dataCreazione) {
        this.dataCreazione = dataCreazione;
    }

    public StatoSpedizioneNotifica getStato() {
        return stato;
    }

    public void setStato(StatoSpedizioneNotifica stato) {
        this.stato = stato;
    }

    public String getDescrizioneStato() {
        return descrizioneStato;
    }

    public void setDescrizioneStato(String descrizioneStato) {
        this.descrizioneStato = descrizioneStato;
    }

    public OffsetDateTime getDataAggiornamentoStato() {
        return dataAggiornamentoStato;
    }

    public void setDataAggiornamentoStato(OffsetDateTime dataAggiornamentoStato) {
        this.dataAggiornamentoStato = dataAggiornamentoStato;
    }

    public OffsetDateTime getDataProssimaSpedizione() {
        return dataProssimaSpedizione;
    }

    public void setDataProssimaSpedizione(OffsetDateTime dataProssimaSpedizione) {
        this.dataProssimaSpedizione = dataProssimaSpedizione;
    }

    public Long getTentativiSpedizione() {
        return tentativiSpedizione;
    }

    public void setTentativiSpedizione(Long tentativiSpedizione) {
        this.tentativiSpedizione = tentativiSpedizione;
    }
}
