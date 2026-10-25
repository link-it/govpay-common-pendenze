package it.govpay.pendenze.repository;

import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;

/**
 * Coppia {@link Rpt}+{@link Pendenza} restituita dalla ricerca trasversale di
 * {@link RptRepository#cercaFiltrate}: {@code Rpt.idVersamento} e' una FK piatta, non una
 * relazione JPA (vedi Javadoc di classe di {@link Rpt}), quindi la pendenza non e'
 * raggiungibile navigando la RPT — la query la seleziona insieme, con un join ad-hoc, invece
 * di lasciare al chiamante una lettura per riga.
 *
 * <p>Record invece di una projection interface (come {@link RicevutaElenco}): qui servono le
 * entita' intere, non un sottoinsieme di colonne, e un costruttore JPQL
 * ({@code select new ...}) le restituisce gia' tipizzate, senza {@code Object[]} da
 * interpretare per posizione.</p>
 *
 * @param rpt      la richiesta di pagamento
 * @param pendenza la pendenza a cui appartiene ({@code pendenza.id = rpt.idVersamento})
 */
public record RptConPendenza(Rpt rpt, Pendenza pendenza) {
}
