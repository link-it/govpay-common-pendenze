package it.govpay.pendenze.entity;

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
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import it.govpay.pendenze.model.TipoSoggetto;

/**
 * Soggetto obbligato al pagamento di una {@link PosizioneDebitoria} ("debitore in
 * solido"), mappato sulla tabella nuova {@code soggetti_debitori} (decisione del lead,
 * 2026-09-25: TUTTI i debitori vivono qui, incluso il primo — il debitore appartiene
 * logicamente al documento, non al singolo versamento legacy).
 *
 * <p>{@link #ordine} e' la sola fonte di verita' su "chi e' il soggetto pagatore": il
 * primo (ordine 0), per convenzione dello YAML v3 (righe 1484-1494), essendo il Nodo dei
 * Pagamenti vincolato a un solo soggetto pagatore per avviso. <b>Non</b> viene sincronizzato
 * su {@code versamenti.debitore_*} (decisione del lead, 2026-09-26, dopo un tentativo
 * intermedio di sincronizzarlo davvero, poi scartato): questa lista resta modificabile dopo
 * la creazione (aggiornamento via PATCH, sviluppo successivo), e tenere allineate quelle
 * colonne a ogni modifica sarebbe complessita' pura — {@code soggetti_debitori} e' l'unica
 * fonte di verita' per v3, quelle colonne restano un placeholder per la sola compatibilita'
 * con la pipeline di pagamento legacy non ancora adattata a v3 — vedi Javadoc di
 * {@link Pendenza}.</p>
 */
@Entity
@Table(name = "soggetti_debitori", uniqueConstraints = @UniqueConstraint(
        name = "unique_soggetti_debitori_1", columnNames = {"id_documento", "ordine"}))
@SequenceGenerator(name = "seq_soggetti_debitori", sequenceName = "seq_soggetti_debitori", allocationSize = 1)
public class SoggettoDebitore {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_soggetti_debitori")
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_documento", nullable = false)
    private PosizioneDebitoria posizioneDebitoria;

    /** 0-based: 0 e' sempre il soggetto pagatore per convenzione. */
    @Column(name = "ordine", nullable = false)
    private int ordine;

    @Column(name = "tipo", nullable = false, length = 1)
    @Enumerated(EnumType.STRING)
    private TipoSoggetto tipo;

    /** Codice fiscale o partita IVA del soggetto — 35 per combaciare con {@code versamenti.debitore_identificativo}. */
    @Column(name = "identificativo", nullable = false, length = 35)
    private String identificativo;

    @Column(name = "anagrafica", length = 70)
    private String anagrafica;

    @Column(name = "indirizzo", length = 70)
    private String indirizzo;

    @Column(name = "civico", length = 16)
    private String civico;

    @Column(name = "cap", length = 16)
    private String cap;

    @Column(name = "localita", length = 35)
    private String localita;

    @Column(name = "provincia", length = 35)
    private String provincia;

    @Column(name = "nazione", length = 2)
    private String nazione;

    @Column(name = "email", length = 256)
    private String email;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PosizioneDebitoria getPosizioneDebitoria() {
        return posizioneDebitoria;
    }

    public void setPosizioneDebitoria(PosizioneDebitoria posizioneDebitoria) {
        this.posizioneDebitoria = posizioneDebitoria;
    }

    public int getOrdine() {
        return ordine;
    }

    public void setOrdine(int ordine) {
        this.ordine = ordine;
    }

    public TipoSoggetto getTipo() {
        return tipo;
    }

    public void setTipo(TipoSoggetto tipo) {
        this.tipo = tipo;
    }

    public String getIdentificativo() {
        return identificativo;
    }

    public void setIdentificativo(String identificativo) {
        this.identificativo = identificativo;
    }

    public String getAnagrafica() {
        return anagrafica;
    }

    public void setAnagrafica(String anagrafica) {
        this.anagrafica = anagrafica;
    }

    public String getIndirizzo() {
        return indirizzo;
    }

    public void setIndirizzo(String indirizzo) {
        this.indirizzo = indirizzo;
    }

    public String getCivico() {
        return civico;
    }

    public void setCivico(String civico) {
        this.civico = civico;
    }

    public String getCap() {
        return cap;
    }

    public void setCap(String cap) {
        this.cap = cap;
    }

    public String getLocalita() {
        return localita;
    }

    public void setLocalita(String localita) {
        this.localita = localita;
    }

    public String getProvincia() {
        return provincia;
    }

    public void setProvincia(String provincia) {
        this.provincia = provincia;
    }

    public String getNazione() {
        return nazione;
    }

    public void setNazione(String nazione) {
        this.nazione = nazione;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
