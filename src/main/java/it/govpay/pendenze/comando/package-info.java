/**
 * Comandi a valore per le scritture di
 * {@link it.govpay.pendenze.service.PosizioneDebitoriaService}.
 *
 * <p>I comandi di creazione non contengono stati, chiavi JPA, versioni o riferimenti
 * alle entita proprietarie. Il servizio inizializza gli stati e assegna i collegamenti.
 * Le factory {@code da(entity)} copiano esclusivamente i dati ammessi, senza mantenere
 * riferimenti mutabili alla sorgente. Le liste vengono copiate dai costruttori.</p>
 *
 * <p>{@link it.govpay.pendenze.comando.AggiornamentoPosizioneDebitoria} distingue
 * i campi invariati da quelli impostati, anche a null (rimozione dei soli campi
 * facoltativi). {@link it.govpay.pendenze.comando.AggiornamentoPendenza} rappresenta
 * invece la sostituzione dei dati modificabili della pendenza, incluse le voci.
 * Numero avviso omesso conserva quello esistente. Le transizioni di stato passano
 * esclusivamente dai metodi dedicati del servizio.</p>
 */
package it.govpay.pendenze.comando;
