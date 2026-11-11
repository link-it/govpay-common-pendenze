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
| `pd_debitori` | Il debitore di una posizione: `id_documento`, `id_debitore`, `posizione` (ordine di presentazione), `notifica_send`, `notifica_send_payed`, `notifica_send_nav`, `data_rimozione`. Unique su `(id_documento, id_debitore)`. |
| `opzioni_pagamento` | La FK è `id_pd_debitore`, non più `id_documento`. |
| `documenti` | Perde `notifica_send` e `nav_notifica`, che passano a `pd_debitori`. |
| `versamenti` | Invariata. |

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

**Notifica SEND per debitore.** `notifica_send`, `notifica_send_payed` e `notifica_send_nav` stanno su `pd_debitori`
perché il NAV su cui si notifica è un NAV del debitore, non della posizione.

L'Ente guida le spese di notifica: `notifica_send` indica che SEND è previsto per il debitore e
`notifica_send_nav` il NAV da interrogare, sotto la sua responsabilità. SEND si interroga solo
quando il `numeroAvviso` della pendenza in pagamento coincide con `notifica_send_nav` del suo debitore,
e `notifica_send` è attivo: la pendenza con quel NAV porta le spese, le altre pendenze del debitore
no. `Pendenza.sendAbilitato`, campo della v2, non entra nella decisione. `notifica_send_payed` è lo
stato "spese già registrate sul debitore": `false` alla creazione, impostato dalla libreria, mai in
ingresso.

`registraEsitoPendenza`, quando la pendenza pagata ha `sendImportoTotale` maggiore di zero, marca il
debitore con `notifica_send_payed`; non modifica le altre pendenze. Un importo assente o pari a 0 non
marca il debitore.
`registraEsitoPendenzaSuOpzioneAnnullataDaCanaleConcorrente` non registra le spese, perché le altre
pendenze sono già annullate. Le due vie dell'importo (quota SEND e `SPESE_NOTIFICA` della voce)
non sono legate da alcun vincolo: non è verificato che siano incompatibili. Nessun controllo sul
`navNotifica`: la libreria non lo assegna né lo verifica.

**PATCH della posizione limitato a `descrizione` e `dataPubblicazione`.** `notificaSend`,
`navNotifica` e la sostituzione dell'elenco dei soggetti escono dall'aggiornamento della posizione:
le prime due sono ora per debitore, la terza non ha più un significato definito, perché togliere un
debitore comporterebbe decidere cosa accade alle sue opzioni e pendenze. La modifica e l'eventuale
rimozione di un `pd_debitori` sono una funzione dedicata, con regole proprie (per esempio rimozione
ammessa solo se nessuna sua opzione è stata pagata), da definire a parte.

**Aggiunta, modifica e rimozione di un debitore.** Sono operazioni dedicate sul singolo `pd_debitori`, indicato dal suo `identificativo`. L'aggiunta ha le stesse validazioni della creazione ed è rifiutata se la posizione ha già un'opzione `ATTIVATA`. La modifica riguarda anagrafica, `tipo`, `identificativo` (univoco tra i debitori attivi), `notificaSend` e `navNotifica`: `notifica_send_payed` non si indica mai in ingresso. La rimozione è **logica**: la colonna `pd_debitori.data_rimozione` viene valorizzata, le opzioni del debitore passano ad `ANNULLATA` e le sue pendenze ad `ANNULLATO`, e posizione e pendenze sono marcate per ACA. È ammessa solo se il debitore non è l'unico attivo e se nella posizione non c'è un'opzione `ATTIVATA` né una pendenza eseguita; una rimozione non è mai fisica, quindi `idPendenza` e `numeroAvviso` delle pendenze restano occupati. Un debitore rimosso è ignorato da `getPdDebitori()`, `getOpzioniPagamento()`, dal controllo di unicità dell'identificativo, dalla mutua esclusione, da `aggiungiOpzionePagamento` e dalle ricerche per debitore, e le pendenze restano leggibili per `idPendenza` con stato `ANNULLATO`; non compaiono invece nelle ricerche di pendenze e ricevute. Si può aggiungere un nuovo debitore con lo stesso identificativo di uno rimosso.

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
- la notifica SEND della v2 (`versamenti.send_abilitato`) diventa un dato del debitore: un debitore con
  almeno un versamento flaggato è migrato con `notifica_send = true` e `notifica_send_nav` uguale al
  `numero_avviso` della pendenza di `numero_rata` più basso (soluzione unica o piano rateale) oppure,
  per i gruppi con sole soglie (`ENTRO`/`OLTRE`/`RIDOTTO`/`SCONTATO`), del primo versamento flaggato.
  Un `numero_avviso` assente lascia `notifica_send_nav` nullo: il debitore è elencato dal report
  diagnostico finale e SEND non viene interrogato per lui. SEND esiste in v2 solo dalla 3.10.x;
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
- Ruolo di `notifica_send_payed` con la regola per NAV: la pendenza con il NAV di notifica si paga una
  sola volta, quindi il flag è quasi ridondante. Da chiarire con chi gestisce il modello il suo
  significato e se resta.
- `SPESE_NOTIFICA` e notifica SEND: non è verificato che siano incompatibili.
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
