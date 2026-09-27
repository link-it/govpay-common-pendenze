package it.govpay.pendenze.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Override per dominio di un {@link TipoVersamento} (anagrafica legacy reale, tabella
 * {@code tipi_vers_domini}) — vedi Javadoc di {@link TipoVersamento} per il contesto
 * generale (proiezione minimale, non piena fedeltà) e la motivazione di questa scelta.
 *
 * <p>{@link #getId()} e' il valore da usare per {@link Pendenza#getIdTipoPendenza()}
 * ({@code id_tipo_versamento_dominio} nel legacy, l'istanza/override per dominio — vedi
 * Javadoc di classe di {@link Pendenza}); {@link #getTipoVersamento()}{@code .getId()} e'
 * il valore per {@link Pendenza#getIdTipoVersamento()} (il catalogo astratto).</p>
 *
 * <p>{@link #tipoVersamento} e' una relazione JPA vera (non una FK piatta M4): a differenza
 * di {@code idDominio} qui sotto, che punta all'anagrafica esterna di {@code govpay-common},
 * {@link TipoVersamento} e' un'anagrafica interna a questa stessa libreria — stesso
 * principio gia' usato per le relazioni tra {@link PosizioneDebitoria}/{@link OpzionePagamento}/
 * {@link Pendenza}.</p>
 */
@Entity
@Table(name = "tipi_vers_domini", uniqueConstraints = @UniqueConstraint(
        name = "unique_tipi_vers_domini_1", columnNames = {"id_dominio", "id_tipo_versamento"}))
@SequenceGenerator(name = "seq_tipi_vers_domini", sequenceName = "seq_tipi_vers_domini", allocationSize = 1)
public class TipoVersamentoDominio {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_tipi_vers_domini")
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_tipo_versamento", nullable = false)
    private TipoVersamento tipoVersamento;

    /** FK piatta verso l'anagrafica esterna di govpay-common (M4). */
    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    /**
     * Override per questo dominio di {@link TipoVersamento#getCodificaIuv()} — colonna
     * legacy reale, stesso nome, presente anche su {@code tipi_vers_domini} (override) come
     * su {@code tipi_versamento} (default). {@code NULL} qui significa "nessun override",
     * non "nessuna codifica": usare {@link #getCodificaIuvEffettiva()}, mai questo campo
     * direttamente, per risolvere il placeholder {@code %(p)}/{@code %(t)} del prefisso IUV.
     */
    @Column(name = "codifica_iuv", length = 4)
    private String codificaIuv;

    // ── Accessori ────────────────────────────────────────────────────────────

    /**
     * @return {@link #codificaIuv} se presente (override per questo dominio), altrimenti
     *         {@link TipoVersamento#getCodificaIuv()} (default del catalogo) — stessa
     *         semantica del legacy (bug del lead, 2026-09-27: la prima versione di questa
     *         entita' non mappava affatto la codifica IUV, facendo fallire la generazione
     *         per i domini con un prefisso {@code %(p)}/{@code %(t)}).
     */
    public String getCodificaIuvEffettiva() {
        return codificaIuv != null ? codificaIuv : tipoVersamento.getCodificaIuv();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TipoVersamento getTipoVersamento() {
        return tipoVersamento;
    }

    public void setTipoVersamento(TipoVersamento tipoVersamento) {
        this.tipoVersamento = tipoVersamento;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public String getCodificaIuv() {
        return codificaIuv;
    }

    public void setCodificaIuv(String codificaIuv) {
        this.codificaIuv = codificaIuv;
    }
}
