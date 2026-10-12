package it.govpay.pendenze.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Projection "slim" di {@link Rpt} sulla sola colonna {@code xml_rpt} (BLOB della richiesta di
 * pagamento originale). Stesso principio di {@code DominioLogoEntity} in {@code govpay-common}:
 * una entity separata, mappata sulla stessa tabella {@code rpt}, per non caricare questo BLOB
 * nelle letture di {@link Rpt} che non lo richiedono (l'elenco e il dettaglio ricevuta in JSON
 * non lo usano mai) — interrogata solo dal mapper della stampa PDF, e solo per il formato
 * SANP_230, l'unico per cui serve leggere la RPT originale invece della RT.
 */
@Entity
@Table(name = "rpt")
public class RptXmlRptEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "xml_rpt")
    private byte[] xmlRpt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public byte[] getXmlRpt() {
        return xmlRpt;
    }

    public void setXmlRpt(byte[] xmlRpt) {
        this.xmlRpt = xmlRpt;
    }
}
