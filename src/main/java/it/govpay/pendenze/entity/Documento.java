package it.govpay.pendenze.entity;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Documento: raggruppamento di pendenze, mappato sulla tabella {@code documenti}.
 *
 * <p>Appartiene all'aggregato pendenza — piu' pendenze rateizzate condividono lo stesso
 * documento — quindi e' l'unica entita' esterna a {@code versamenti} verso cui esiste una
 * relazione JPA.</p>
 */
@Entity
@Table(name = "documenti", uniqueConstraints = @UniqueConstraint(
        name = "unique_documenti_1",
        columnNames = {"cod_documento", "id_applicazione", "id_dominio"}))
@SequenceGenerator(name = "seq_documenti", sequenceName = "seq_documenti", allocationSize = 1)
public class Documento {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_documenti")
    @Column(name = "id")
    private Long id;

    @Column(name = "cod_documento", nullable = false, length = 35)
    private String codDocumento;

    @Column(name = "descrizione", nullable = false, length = 255)
    private String descrizione;

    @Column(name = "id_dominio", nullable = false)
    private Long idDominio;

    @Column(name = "id_applicazione", nullable = false)
    private Long idApplicazione;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCodDocumento() {
        return codDocumento;
    }

    public void setCodDocumento(String codDocumento) {
        this.codDocumento = codDocumento;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public Long getIdDominio() {
        return idDominio;
    }

    public void setIdDominio(Long idDominio) {
        this.idDominio = idDominio;
    }

    public Long getIdApplicazione() {
        return idApplicazione;
    }

    public void setIdApplicazione(Long idApplicazione) {
        this.idApplicazione = idApplicazione;
    }

    @Override
    public boolean equals(Object altro) {
        if (this == altro) {
            return true;
        }
        if (!(altro instanceof Documento that) || id == null || that.id == null) {
            return false;
        }
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Documento[id=" + id + ", codDocumento=" + codDocumento + "]";
    }
}
