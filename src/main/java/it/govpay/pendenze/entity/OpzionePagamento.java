package it.govpay.pendenze.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;

/**
 * Opzione di pagamento: modalita' alternativa con cui puo' essere estinta una
 * {@link PosizioneDebitoria}, mappata sulla tabella nuova {@code opzioni_pagamento}
 * (decisione del lead, 2026-09-25: la mutua esclusione tra opzioni alternative non esiste
 * in v2 in nessuna forma — nemmeno manuale: l'unico annullamento legacy e' un'operazione
 * esplicita per singolo versamento, senza cascata sui versamenti "fratelli" — quindi va
 * tracciata da una tabella vera, non da una colonna sparsa su {@code versamenti}).
 *
 * <p><b>Tabella unica per le 4 tipologie (M2 di {@code proposta-modello-nativo-v3.md}).</b>
 * {@code PIANO_RATEALE}/{@code SOLUZIONE_UNICA}/{@code SOLUZIONE_UNICA_ENTRO}/
 * {@code SOLUZIONE_UNICA_OLTRE} condividono la stessa struttura: differiscono solo per
 * {@link #giorni} (nullable, richiesto solo dalle due tipologie con termine, vedi
 * {@link TipologiaOpzionePagamento#richiedeGiorni()}) e per la cardinalita' ammessa di
 * {@link #pendenze} (validata in applicazione tramite
 * {@link TipologiaOpzionePagamento#cardinalitaPendenzeMinima()}/
 * {@code cardinalitaPendenzeMassima()}, non a livello di schema). Una gerarchia JPA
 * sarebbe sovradimensionata per questa differenza.</p>
 *
 * <p>L'assegnazione di {@link Pendenza#getNumeroRata()} in base alla posizione nella
 * lista {@link #pendenze} resta a carico del servizio di caricamento (fase successiva),
 * non di questa entita' — coerente con come il vecchio disegno teneva la
 * riassegnazione degli indici fuori dall'entita'.</p>
 */
@Entity
@Table(name = "opzioni_pagamento")
@SequenceGenerator(name = "seq_opzioni_pagamento", sequenceName = "seq_opzioni_pagamento", allocationSize = 1)
public class OpzionePagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_opzioni_pagamento")
    @Column(name = "id")
    private Long id;

    /**
     * Lock ottimistico: senza questo controllo un {@code annulla} basato su una lettura
     * antecedente a un {@code attiva} concorrente (o viceversa) sovrascriverebbe in
     * silenzio la transizione appena registrata — un pagamento gia' eseguito potrebbe
     * risultare annullato. Hibernate incrementa questa colonna a ogni update e rifiuta
     * (con {@code OptimisticLockException}) uno scritto basato su una versione superata:
     * il chiamante deve rileggere e ridecidere, non perdere l'aggiornamento in silenzio.
     */
    @Version
    @Column(name = "versione", nullable = false)
    private long versione;

    /** Esposto in API, generato da GovPay alla creazione, stabile per tutta la vita della posizione. */
    @Column(name = "id_opzione_pagamento", nullable = false, unique = true)
    private UUID idOpzionePagamento;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_documento", nullable = false)
    private PosizioneDebitoria posizioneDebitoria;

    @Column(name = "tipologia", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private TipologiaOpzionePagamento tipologia;

    /** Nullable: richiesto solo per {@code SOLUZIONE_UNICA_ENTRO}/{@code SOLUZIONE_UNICA_OLTRE}. */
    @Column(name = "giorni")
    private Integer giorni;

    @Column(name = "stato", nullable = false, length = 35)
    @Enumerated(EnumType.STRING)
    private StatoOpzionePagamento stato = StatoOpzionePagamento.DISPONIBILE;

    @Column(name = "data_inizio_validita")
    private LocalDate dataInizioValidita;

    @Column(name = "data_scadenza")
    private LocalDate dataScadenza;

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

    @Column(name = "data_ultimo_aggiornamento", nullable = false)
    private OffsetDateTime dataUltimoAggiornamento;

    /** Le rate/pendenze che compongono questa opzione, nell'ordine in cui vanno pagate. */
    @OneToMany(mappedBy = "opzionePagamento", cascade = CascadeType.ALL)
    @OrderBy("numeroRata ASC")
    private List<Pendenza> pendenze = new ArrayList<>();

    /**
     * Aggiunge una pendenza mantenendo coerente il lato inverso della relazione.
     *
     * @param pendenza pendenza da aggiungere, non nulla
     */
    public void addPendenza(Pendenza pendenza) {
        Objects.requireNonNull(pendenza, "la pendenza da aggiungere non puo' essere nulla");
        pendenze.add(pendenza);
        pendenza.setOpzionePagamento(this);
    }

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    /** Nessun setter: la versione e' gestita da Hibernate, non va scritta dal chiamante. */
    public long getVersione() {
        return versione;
    }

    public UUID getIdOpzionePagamento() {
        return idOpzionePagamento;
    }

    public void setIdOpzionePagamento(UUID idOpzionePagamento) {
        this.idOpzionePagamento = idOpzionePagamento;
    }

    public PosizioneDebitoria getPosizioneDebitoria() {
        return posizioneDebitoria;
    }

    public void setPosizioneDebitoria(PosizioneDebitoria posizioneDebitoria) {
        this.posizioneDebitoria = posizioneDebitoria;
    }

    public TipologiaOpzionePagamento getTipologia() {
        return tipologia;
    }

    public void setTipologia(TipologiaOpzionePagamento tipologia) {
        this.tipologia = tipologia;
    }

    public Integer getGiorni() {
        return giorni;
    }

    public void setGiorni(Integer giorni) {
        this.giorni = giorni;
    }

    public StatoOpzionePagamento getStato() {
        return stato;
    }

    public void setStato(StatoOpzionePagamento stato) {
        this.stato = stato;
    }

    public LocalDate getDataInizioValidita() {
        return dataInizioValidita;
    }

    public void setDataInizioValidita(LocalDate dataInizioValidita) {
        this.dataInizioValidita = dataInizioValidita;
    }

    public LocalDate getDataScadenza() {
        return dataScadenza;
    }

    public void setDataScadenza(LocalDate dataScadenza) {
        this.dataScadenza = dataScadenza;
    }

    public OffsetDateTime getDataCreazione() {
        return dataCreazione;
    }

    public void setDataCreazione(OffsetDateTime dataCreazione) {
        this.dataCreazione = dataCreazione;
    }

    public OffsetDateTime getDataUltimoAggiornamento() {
        return dataUltimoAggiornamento;
    }

    public void setDataUltimoAggiornamento(OffsetDateTime dataUltimoAggiornamento) {
        this.dataUltimoAggiornamento = dataUltimoAggiornamento;
    }

    public List<Pendenza> getPendenze() {
        return pendenze;
    }
}
