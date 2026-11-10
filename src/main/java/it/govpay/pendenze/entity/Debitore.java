package it.govpay.pendenze.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import it.govpay.pendenze.model.TipoSoggetto;

/**
 * Anagrafica di un debitore, mappata sulla tabella nuova {@code debitori}. E' un'anagrafica
 * per occorrenza: ogni {@link PdDebitore} ha la propria riga, nessun vincolo di unicita' su
 * {@link #identificativo} e nessun riuso tra posizioni.
 *
 * <p>Non viene sincronizzata su {@code versamenti.debitore_*}: quelle colonne restano un
 * placeholder per la sola compatibilita' con la pipeline di pagamento legacy non ancora
 * adattata a v3 — vedi Javadoc di {@link Pendenza}.</p>
 */
@Entity
@Table(name = "debitori")
public class Debitore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * {@code @JdbcTypeCode(SqlTypes.VARCHAR)}: senza, Hibernate per un enum-stringa di
     * lunghezza 1 si aspetta {@code CHAR(1)} in validazione schema — la colonna reale
     * (schema del core, script di migrazione della libreria) e' {@code VARCHAR(1)}, come
     * {@code versamenti.debitore_tipo}. H2 (test) non distingue i due tipi, quindi il gap
     * non emergeva dai test della libreria.
     */
    @Column(name = "tipo", nullable = false, length = 1)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
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

    @Column(name = "cellulare", length = 35)
    private String cellulare;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getCellulare() {
        return cellulare;
    }

    public void setCellulare(String cellulare) {
        this.cellulare = cellulare;
    }
}
