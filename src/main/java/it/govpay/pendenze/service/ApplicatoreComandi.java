package it.govpay.pendenze.service;

import java.util.ArrayList;
import it.govpay.pendenze.comando.DatiOpzionePagamento;
import it.govpay.pendenze.comando.DatiPendenza;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.comando.DatiSoggettoDebitore;
import it.govpay.pendenze.comando.DatiVocePendenza;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;

/** Materializza nuove entita dai comandi. Mai esposto ai consumatori del servizio. */
final class ApplicatoreComandi {
    private ApplicatoreComandi() { }
    static SoggettoDebitore soggettoDebitore(DatiSoggettoDebitore dati) {
        SoggettoDebitore e = new SoggettoDebitore();
        e.setTipo(dati.tipo());
        e.setIdentificativo(dati.identificativo());
        e.setAnagrafica(dati.anagrafica());
        e.setIndirizzo(dati.indirizzo());
        e.setCivico(dati.civico());
        e.setCap(dati.cap());
        e.setLocalita(dati.localita());
        e.setProvincia(dati.provincia());
        e.setNazione(dati.nazione());
        e.setEmail(dati.email());
        e.setCellulare(dati.cellulare());
        return e;
    }

    static VocePendenza vocePendenza(DatiVocePendenza dati) {
        VocePendenza e = new VocePendenza();
        e.setStato(it.govpay.pendenze.model.StatoVocePendenza.NON_ESEGUITO);
        e.setIdVocePendenza(dati.idVocePendenza());
        e.setImporto(dati.importo());
        e.setDescrizione(dati.descrizione());
        e.setIdDominio(dati.idDominio());
        e.setIdTributo(dati.idTributo());
        e.setIdIbanAccredito(dati.idIbanAccredito());
        e.setIdIbanAppoggio(dati.idIbanAppoggio());
        e.setTassonomia(dati.tassonomia());
        e.setTipoBollo(dati.tipoBollo());
        e.setHashDocumento(dati.hashDocumento());
        e.setProvinciaResidenza(dati.provinciaResidenza());
        e.setDettaglioContabile(new ArrayList<>(dati.dettaglioContabile()));
        return e;
    }

    static Pendenza pendenza(DatiPendenza dati) {
        Pendenza e = new Pendenza();
        e.setIdPendenza(dati.idPendenza());
        e.setIdTipoPendenza(dati.idTipoPendenza());
        e.setIdTipoVersamento(dati.idTipoVersamento());
        e.setCodificaIuvTipoPendenza(dati.codificaIuvTipoPendenza());
        e.setImporto(dati.importo());
        e.setNumeroAvviso(dati.numeroAvviso());
        e.setIuv(dati.iuv());
        e.setDataValidita(dati.dataValidita());
        e.setDataScadenzaAvviso(dati.dataScadenzaAvviso());
        dati.voci().forEach(v -> e.addVocePendenza(vocePendenza(v)));
        e.setStato(StatoPendenza.NON_ESEGUITO);
        return e;
    }

    static OpzionePagamento opzionePagamento(DatiOpzionePagamento dati) {
        OpzionePagamento e = new OpzionePagamento();
        e.setTipologia(dati.tipologia());
        e.setGiorni(dati.giorni());
        e.setDataInizioValidita(dati.dataInizioValidita());
        e.setDataScadenza(dati.dataScadenza());
        dati.pendenze().forEach(v -> e.addPendenza(pendenza(v)));
        e.setStato(StatoOpzionePagamento.DISPONIBILE);
        return e;
    }

    static PosizioneDebitoria posizioneDebitoria(DatiPosizioneDebitoria dati) {
        PosizioneDebitoria e = new PosizioneDebitoria();
        e.setCodDocumento(dati.codDocumento());
        e.setIdApplicazione(dati.idApplicazione());
        e.setIdDominio(dati.idDominio());
        e.setIdUnitaOperativa(dati.idUnitaOperativa());
        e.setDescrizione(dati.descrizione());
        e.setDataPubblicazione(dati.dataPubblicazione());
        e.setNotificaSend(dati.notificaSend());
        e.setNavNotifica(dati.navNotifica());
        dati.soggettiDebitori().forEach(v -> e.addSoggettoDebitore(soggettoDebitore(v)));
        dati.opzioniPagamento().forEach(v -> e.addOpzionePagamento(opzionePagamento(v)));
        return e;
    }

}
