package it.govpay.pendenze.comando;

import it.govpay.pendenze.entity.Debitore;
import it.govpay.pendenze.model.TipoSoggetto;

/** Dati di ingresso a valore: nessuno stato, identita JPA o riferimento all'aggregato gestito. */
public record DatiDebitore(
        TipoSoggetto tipo,
        String identificativo,
        String anagrafica,
        String indirizzo,
        String civico,
        String cap,
        String localita,
        String provincia,
        String nazione,
        String email,
        String cellulare) {
    /** Copia i soli campi ammessi; successive modifiche all'entita non modificano il comando. */
    public static DatiDebitore da(Debitore e) {
        return new DatiDebitore(e.getTipo(),
                e.getIdentificativo(),
                e.getAnagrafica(),
                e.getIndirizzo(),
                e.getCivico(),
                e.getCap(),
                e.getLocalita(),
                e.getProvincia(),
                e.getNazione(),
                e.getEmail(),
                e.getCellulare());
    }
}
