# Opzioni di pagamento per debitore

Descrive il modello in cui ogni debitore di una posizione debitoria ha le proprie opzioni di
pagamento, quindi le proprie pendenze e i propri NAV, e le scelte che lo definiscono.

## 1. Problema

Nel modello precedente `opzioni_pagamento` era agganciata direttamente alla posizione debitoria
(`documenti`). `soggetti_debitori` era una lista di soggetti pari-grado senza alcun legame con le
opzioni: tutti condividevano lo stesso piano di pagamento e gli stessi NAV, e solo il soggetto di
`ordine=0` era trattato come pagatore verso RPT e comunicazione ACA, per convenzione applicativa.

Il requisito è un piano di pagamento indipendente per ogni debitore, con NAV propri. Lo schema
precedente ammetteva già più soggetti per posizione: cambia il legame tra debitori e opzioni, non
il numero di debitori ammessi.

## 2. Modello

```
documenti (posizione) 1 ── N pd_debitori N ── 1 debitori
                                  │
                                  1
                                  │
                                  N
                          opzioni_pagamento 1 ── N versamenti (pendenze)
```

| Tabella | Contenuto |
|---|---|
| `debitori` | Anagrafica: `tipo`, `identificativo`, `anagrafica`, indirizzo, `email`, `cellulare`. Nessun legame diretto con la posizione. |
| `pd_debitori` | Il debitore di una posizione: `id_documento`, `id_debitore`, `posizione` (ordine di presentazione), `data_rimozione`. Unique su `(id_documento, id_debitore)`. |
| `opzioni_pagamento` | La FK è `id_pd_debitore`, non più `id_documento`. |
| `documenti` | Perde `notifica_send` e `nav_notifica`: la notifica SEND è per opzione di pagamento e sta sulle pendenze. |
| `versamenti` | `send_abilitato` e `send_nav` (VARCHAR(18), nullable) portano la notifica SEND; `send_nav` è introdotta dalla 3.10.x. |

Le tabelle fisiche `documenti` e `versamenti` mantengono il nome. `soggetti_debitori` è stata
adattata e rinominata in `debitori`: stesse colonne di anagrafica, senza `id_documento`, `ordine` e
il vincolo `unique (id_documento, ordine)`, che passano a `pd_debitori`.

## 3. Scelte e motivazioni

**IUPD unico per l'intera posizione.** Nel contratto GPD v3 (`/debt-positions-service/v3`)
`PaymentPositionModelV3` non ha un debitore: il campo `debtor` (`DebtorModel`, obbligatorio) è su
ogni `PaymentOptionModelV3`. Un debitore diverso per opzione è quindi rappresentabile sotto lo
stesso IUPD, e la posizione resta l'unità identificata da `idA2A + idDominio + codDocumento`.

**Mutua esclusione a livello di intera posizione.** Quando un'opzione viene attivata, le altre
opzioni ancora DISPONIBILI della posizione diventano ANNULLATE, anche se appartengono ad altri
debitori. Lo scope non cambia rispetto al modello precedente; cambia il percorso per raggiungere
le opzioni, ora a due livelli (`pd_debitori` → `opzioni_pagamento`).
`PosizioneDebitoria.getOpzioniPagamento()` è una vista di sola lettura di tutte le opzioni di tutti
i debitori.

**`debitori` è un'anagrafica per occorrenza.** Nessun vincolo di unicità su `identificativo` e nessun
riuso della riga tra posizioni: ogni `pd_debitori` ha la propria riga `debitori`, mappata 1:1
(`@OneToOne` con cascade e orphan removal). Una deduplica richiederebbe di decidere lo scope
dell'unicità (per dominio, per applicazione) e cosa significhi modificare l'anagrafica di un
debitore condiviso da posizioni già comunicate; non serve al requisito.

**Notifica SEND per opzione di pagamento.** L'abilitazione di SEND e il NAV di notifica sono dati
dell'opzione di pagamento nel contratto, ma sul database stanno sulla singola pendenza
(`versamenti.send_abilitato`, `versamenti.send_nav`): la 3.10.x non ha le opzioni e l'Ente può avere
più NAV con importi distinti nella stessa posizione. Con soluzione unica e piano rateale alternativi,
un solo NAV per debitore non coprirebbe l'opzione scelta.

- *Scrittura.* Alla creazione o alla modifica dell'opzione i due valori vanno sulla sola prima pendenza
  dell'opzione (`numero_rata` minore), in ogni tipologia; le altre pendenze non la portano. Una
  pendenza aggiunta all'opzione non la porta e non è possibile abilitarla su una pendenza che non
  sia la prima.
- *Lettura.* Sull'opzione non esiste alcuna colonna: abilitazione e NAV si ricostruiscono dalla prima
  pendenza dell'opzione.
- *Interrogazione.* SEND si interroga per ogni pendenza con `send_abilitato` vero, con `send_nav` se
  valorizzato, altrimenti con il `numeroAvviso` della pendenza. L'Ente è responsabile della scelta dei
  NAV e di non duplicare le spese.
- *Validazione.* `send_nav` è ammesso solo con la notifica abilitata, è di 18 cifre e non coincide con
  il `numeroAvviso` della pendenza. In aggiornamento, se non è indicato, resta quello salvato.
- *Modifica.* `aggiornaNotificaSend` agisce su un'opzione le cui pendenze sono tutte `NON_ESEGUITO` o
  `ANNULLATO`. Le pendenze il cui stato SEND cambia, se `NON_ESEGUITO` o `ANNULLATO` e senza RPT, perdono
  `sendImportoTotale` e `sendDataAggiornamento`: la verifica successiva interroga di nuovo SEND. Le
  pendenze con una RPT (pagamento in corso) conservano il loro valore.

`registraEsitoPendenza` non registra nulla a proposito di SEND. Le due vie dell'importo (quota SEND e
`SPESE_NOTIFICA` della voce) non sono legate da alcun vincolo: non è verificato che siano
incompatibili.

**Data di pubblicazione.** `documenti.data_pubblicazione` (DATE) è la data da cui la posizione è
pagabile: un giorno, valido dalle 00:00. Senza data, o a data raggiunta, la posizione è pubblicata
(`isPubblicata`). La data serve a lasciare il tempo di notificare a SEND i dati dei pagamenti prima che
l'avviso sia pagabile.

- *Controlli.* La data deve essere antecedente alla scadenza di ogni pendenza della posizione (quella
  dell'avviso o, se assente, quella dell'opzione; le pendenze senza scadenza non vincolano), non può
  superare di oltre `govpay.pendenze.giorni-massimi-data-pubblicazione` giorni la data corrente
  (default 7, da tenere entro la finestra con cui il batch ACA prende in carico le posizioni) e, in
  aggiornamento, non può essere futura su una posizione già pubblicata, perché l'avviso non si ritira da
  pagoPA e dall'ACA. Se l'aggiornamento non la indica, resta quella salvata.
- *ACA.* `dataUltimaModificaAca` di posizione e pendenze è il massimo tra l'istante della modifica e
  l'inizio del giorno di pubblicazione, così il batch non prende in carico la posizione prima. Lo
  stesso vale per tutte le operazioni che marcano per ACA.
- *Ricerca.* Il filtro `nonPubblicata` seleziona le pendenze di una posizione con data futura
  (`true`) o tutte le altre (`false`); lo stato `NON_PUBBLICATA` è calcolato dal chiamante come
  `NON_ESEGUITO` con data futura.
- *Pagamento.* La libreria non blocca il pagamento: verifica e attivazione dell'avviso prima della data
  sono a carico del servizio di pagamento, che si basa su `isPubblicata`.

**PATCH della posizione limitato a `descrizione` e `dataPubblicazione`.** La sostituzione dell'elenco
dei soggetti esce dall'aggiornamento della posizione: non ha più un significato definito, perché togliere un
debitore comporterebbe decidere cosa accade alle sue opzioni e pendenze. La modifica e l'eventuale
rimozione di un `pd_debitori` sono una funzione dedicata, con regole proprie (per esempio rimozione
ammessa solo se nessuna sua opzione è stata pagata), da definire a parte.

**Aggiunta, modifica e rimozione di un debitore.** Sono operazioni dedicate sul singolo `pd_debitori`, indicato dal suo `identificativo`. L'aggiunta ha le stesse validazioni della creazione ed è rifiutata se la posizione ha già un'opzione `ATTIVATA`. La modifica riguarda anagrafica, `tipo`, `identificativo` (univoco tra i debitori attivi). La rimozione è **logica**: la colonna `pd_debitori.data_rimozione` viene valorizzata, le opzioni del debitore passano ad `ANNULLATA` e le sue pendenze ad `ANNULLATO`, e posizione e pendenze sono marcate per ACA. È ammessa solo se il debitore non è l'unico attivo e se nella posizione non c'è un'opzione `ATTIVATA` né una pendenza eseguita; una rimozione non è mai fisica, quindi `idPendenza` e `numeroAvviso` delle pendenze restano occupati. Un debitore rimosso è ignorato da `getPdDebitori()`, `getOpzioniPagamento()`, dal controllo di unicità dell'identificativo, dalla mutua esclusione, da `aggiungiOpzionePagamento` e dalle ricerche per debitore, e le pendenze restano leggibili per `idPendenza` con stato `ANNULLATO`; non compaiono invece nelle ricerche di pendenze e ricevute. Si può aggiungere un nuovo debitore con lo stesso identificativo di uno rimosso.

**Nuova opzione su posizione esistente.** L'alternativa si aggiunge a ogni debitore della
posizione: il comando porta un'opzione per debitore, abbinata tramite l'identificativo fiscale, e
deve coprirli tutti, altrimenti la posizione resterebbe con un'alternativa disponibile solo per
alcuni. Le opzioni non possono essere copie identiche, perché ogni debitore ha NAV e pendenze
proprie e `idPendenza` è univoco per applicazione. Per rendere l'abbinamento non ambiguo la
validazione rifiuta due debitori con lo stesso identificativo nella stessa posizione.

**Almeno un'opzione per debitore.** Un debitore senza opzioni non produce alcun avviso: la
validazione di creazione lo rifiuta.

**Ricerca per debitore.** La ricerca di posizioni trova la posizione se *qualunque* suo debitore
corrisponde. La ricerca di pendenze e di ricevute filtra invece sul debitore dell'opzione della
pendenza: ogni debitore ha le proprie pendenze, quelle degli altri debitori della stessa posizione
non sono sue. Le pendenze v2 senza opzione di pagamento non corrispondono a nessun filtro per
debitore, come prima.

**Schema modificato in place.** La v3 non è mai stata rilasciata e non esistono righe con la
struttura precedente: gli script di migrazione sono riscritti senza passi di conversione. `02` crea
`debitori` e `pd_debitori`, `03` crea `opzioni_pagamento`, che dipende da `pd_debitori`.

## 4. Migrazione v2 → v3

In v2 il debitore è denormalizzato sui versamenti (`debitore_*`). La migrazione raggruppa i
versamenti di un documento per debitore distinto: ogni gruppo produce una riga `debitori` e un
`pd_debitori` (ordine di prima comparsa), e la classificazione di `cod_rata` (soluzione unica,
piano rateale, soglie ENTRO/OLTRE/RIDOTTO/SCONTATO) si applica dentro il gruppo, con l'opzione
agganciata a `id_pd_debitore`. I documenti con debitori diversi tra i versamenti non sono più
scartati come `DEBITORI_INCOERENTI`: diventano posizioni con più debitori.

Dettagli di `06_opzioni_pagamento_documenti_esistenti.sql`:

- il raggruppamento usa `debitore_identificativo` senza distinguere maiuscole e minuscole; l'anagrafica
  del debitore è copiata dal primo versamento del gruppo, e un `debitore_tipo` assente (nullable in v2,
  non in `debitori`) diventa `F`;
- un gruppo con `cod_rata` anomalo (misto, duplicato, corrotto) non viene migrato: resta senza
  `pd_debitori` e senza opzioni, con i versamenti a `id_opzione_pagamento` nullo, ed elencato dal report
  diagnostico finale per coppia documento-debitore. Gli altri debitori dello stesso documento vengono
  migrati;
- la cascata finale applica la mutua esclusione a tutta la posizione: un'opzione `DISPONIBILE` di
  qualunque debitore diventa `ANNULLATA` se un'altra opzione della posizione è `ATTIVATA`;
- la notifica SEND della v2 (`versamenti.send_abilitato`, `versamenti.send_nav`) non richiede alcuna
  mappatura: resta sui versamenti, e l'opzione la legge dalla prima pendenza;
- `documenti.data_pubblicazione` è la data più tarda tra quelle di `versamenti.data_pubblicazione`
  del documento, troncata al giorno; una data già presente sul documento non viene toccata;
- la `06` gira in un'unica transazione (annullata per intero in caso di errore, quindi non esistono
  gruppi migrati a metà) ed è idempotente: un gruppo già presente come `pd_debitori` dello stesso
  documento non si duplica. Un gruppo scartato e corretto a mano è migrato da una riesecuzione con
  `posizione` successiva ai debitori già migrati, quindi l'ordine resta non ambiguo ma non coincide più
  con la prima comparsa assoluta;
- `05_documenti_orfani.sql` non cambia: ogni versamento orfano diventa un documento con un solo debitore;
- `06` è stato eseguito su PostgreSQL con scenari multi-debitore (incluso il rerun). Le versioni MySQL,
  Oracle e SQL Server non sono state eseguite contro un'istanza reale; per HSQLDB non esiste.

## 5. Questioni aperte

- Eliminazione fisica dei debitori rimossi e rilascio di `idPendenza`/`numeroAvviso`: da decidere con `govpay-aca-batch`, che deve aver comunicato l'annullamento.
- `SPESE_NOTIFICA` e notifica SEND: non è verificato che siano incompatibili.
- Voce a importo 0 per le spese di notifica: come si rappresenta nelle voci della posizione.
- Se `versamenti.data_pubblicazione` va ancora scritta per le pendenze v3.
- Migrazione non completa: manca la `06` per HSQLDB; le versioni MySQL, Oracle e SQL Server non sono
  eseguite contro un'istanza reale; il routing ACA v1/v3 sui dati già comunicati (vedi sotto) è
  rimandato.

- Routing tra il percorso ACA/GPD v1 e v3 per i dati già comunicati prima della migrazione
  (precedentemente risolto con un flag persistito, da rivalutare nel nuovo modello): si riprende
  quando `govpay-aca-batch` viene allineato alla nuova struttura, dove il debitore va letto da
  `pd_debitori.debitore` per ciascuna opzione.
- Lunghezze e pattern dei campi del debitore (per esempio `identificativo` oggi `VARCHAR(35)`):
  da verificare contro i vincoli di validazione pagoPA.
- Lo script SQL unico del repository centrale (`gov_pay.sql`, `patch/3.11.0.sql`,
  `riallineamento-v3.sql`) va riallineato da chi lo gestisce.
