package it.govpay.pendenze.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Unità operativa di un dominio (anagrafica legacy reale, tabella {@code uo}), non
 * ancora portata su {@code govpay-common} insieme a {@code Applicazione}/{@code Dominio}
 * (decisione del lead, 2026-09-26): nasce qui perché serve subito a risolvere
 * {@code idUnitaOperativa} (codice, non id numerico — stessa convenzione di {@code idA2A}/
 * {@code idDominio}, verificata nel legacy: {@code PutUnitaOperativaDTO.idUo}) in scrittura
 * su {@link PosizioneDebitoria}. Se in futuro {@code govpay-console-api} (passata alla
 * 3.11.x) dipenderà da questa libreria, questa resta la sua sede naturale, non un ripiego
 * da migrare — se invece un domani dovesse servire indipendentemente da questa libreria,
 * andrà valutata la promozione a {@code govpay-common}.
 *
 * <p>Colonne reali riusate 1:1 da {@code uo}: nessuna colonna aggiunta. {@link #idDominio}
 * è una FK piatta (M4, nessuna relazione JPA verso l'anagrafica esterna), coerente con
 * come {@link Pendenza}/{@link PosizioneDebitoria} trattano già {@code id_dominio} — anche
 * se in produzione {@code uo.id_dominio} ha un vincolo FK reale verso {@code domini(id)}.</p>
 */
@Entity
@Table(name = "uo", uniqueConstraints = @UniqueConstraint(
        name = "unique_uo_1", columnNames = {"cod_uo", "id_dominio"}))
@SequenceGenerator(name = "seq_uo", sequenceName = "seq_uo", allocationSize = 1)
public class UnitaOperativa {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_uo")
    @Column(name = "id")
    private Long id;

    /** FK piatta verso l'anagrafica esterna di govpay-common (M4). */
    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    @Column(name = "cod_uo", nullable = false, length = 35)
    private String codUo;

    @Column(name = "abilitato", nullable = false)
    private boolean abilitato;

    @Column(name = "uo_codice_identificativo", length = 35)
    private String codiceIdentificativo;

    @Column(name = "uo_denominazione", length = 70)
    private String denominazione;

    @Column(name = "uo_indirizzo", length = 70)
    private String indirizzo;

    @Column(name = "uo_civico", length = 16)
    private String civico;

    @Column(name = "uo_cap", length = 16)
    private String cap;

    @Column(name = "uo_localita", length = 35)
    private String localita;

    @Column(name = "uo_provincia", length = 35)
    private String provincia;

    @Column(name = "uo_nazione", length = 2)
    private String nazione;

    @Column(name = "uo_area", length = 255)
    private String area;

    @Column(name = "uo_url_sito_web", length = 255)
    private String urlSitoWeb;

    @Column(name = "uo_email", length = 255)
    private String email;

    @Column(name = "uo_pec", length = 255)
    private String pec;

    @Column(name = "uo_tel", length = 255)
    private String tel;

    @Column(name = "uo_fax", length = 255)
    private String fax;

    // ── Accessori ────────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public String getCodUo() {
        return codUo;
    }

    public void setCodUo(String codUo) {
        this.codUo = codUo;
    }

    public boolean isAbilitato() {
        return abilitato;
    }

    public void setAbilitato(boolean abilitato) {
        this.abilitato = abilitato;
    }

    public String getCodiceIdentificativo() {
        return codiceIdentificativo;
    }

    public void setCodiceIdentificativo(String codiceIdentificativo) {
        this.codiceIdentificativo = codiceIdentificativo;
    }

    public String getDenominazione() {
        return denominazione;
    }

    public void setDenominazione(String denominazione) {
        this.denominazione = denominazione;
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

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getUrlSitoWeb() {
        return urlSitoWeb;
    }

    public void setUrlSitoWeb(String urlSitoWeb) {
        this.urlSitoWeb = urlSitoWeb;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPec() {
        return pec;
    }

    public void setPec(String pec) {
        this.pec = pec;
    }

    public String getTel() {
        return tel;
    }

    public void setTel(String tel) {
        this.tel = tel;
    }

    public String getFax() {
        return fax;
    }

    public void setFax(String fax) {
        this.fax = fax;
    }
}
