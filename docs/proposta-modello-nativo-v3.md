# Proposta di disegno — modello nativo v3

Documento di proposta per il modello dati/entità JPA dell'evolutiva api-pendenze
v3, dopo l'abbandono del porting del modello legacy (issue #8, PR #9). Sostituisce
concettualmente `proposta-libreria-pendenze.md`/`f1-modello-e-entita.md` (che
restano come riferimento sul comportamento 3.10.x per la migrazione dati, non
come base di questo disegno).

- **Fonte v3**: `govpay-api-pendenze-v3.yaml` (schemi `NuovaPosizioneDebitoria`,
  `NuovaOpzionePagamento*`, `NuovaPendenza`, `NuovaVocePendenza*`, `Soggetto`,
  `PosizioneDebitoria`, `OpzionePagamento*`, `Pendenza*`, `VocePendenza*`)
- **Fonte principi di scrittura/lettura**: `riconciliazione-legacy-v3.md` §2
  (D10/D11/D3/A5/§4.2/§4.8/§7.4 del vecchio disegno, ancora validi)
- **Fonte ACA/GPD**: spec ufficiale pagoPA `pagopa/pagopa-api`,
  `openapi/gpd-4-aca.json` (verificata il 2026-09-22)

## 1. Struttura dell'aggregato

```
PosizioneDebitoria (root)
├── SoggettoDebitore[]     (ordinati, "in solido")
└── OpzionePagamento[]     (tipologia: PIANO_RATEALE | SOLUZIONE_UNICA |
    │                       SOLUZIONE_UNICA_ENTRO | SOLUZIONE_UNICA_OLTRE)
    │                       stato: DISPONIBILE → ATTIVATA | ANNULLATA
    └── Pendenza[]         (numeroRata, nav = numeroAvviso)
        └── VocePendenza[] (RiferimentoEntrata | Entrata | Bollo)
            └── DettaglioContabile[] (solo per Entrata/RiferimentoEntrata — non in questo primo disegno, vedi §6)
```

Un solo aggregato transazionale (coerente con D3/D9 del vecchio disegno):
caricare/modificare una `PosizioneDebitoria` tocca le sue opzioni, pendenze,
voci e soggetti in un'unica unità di lavoro.

## 2. Decisioni di mapping

| ID | Decisione |
|---|---|
| M1 | **Package/nomi nativi v3**, non il vocabolario legacy: `PosizioneDebitoria`, `OpzionePagamento`, `Pendenza`, `VocePendenza`, `SoggettoDebitore` — a differenza della vecchia A2 ("doppio vocabolario mantenuto"), qui non c'è un vocabolario legacy da conciliare: si parte nativi |
| M2 | **`OpzionePagamento` a tabella unica, non per-tipologia**: le 4 varianti differiscono solo per `tipologia` (enum), `giorni` (nullable, richiesto solo per ENTRO/OLTRE) e cardinalità di `pendenze` (validata in applicazione, non a DB). Una gerarchia JPA (`@Inheritance`) sarebbe sovradimensionata: colonna `tipologia` + `giorni` nullable bastano, validazione applicativa sulla cardinalità |
| M3 | **`VocePendenza` a tabella unica**, stesso ragionamento: le 3 varianti (`RiferimentoEntrata`/`Entrata`/`Bollo`) condividono `DatiComuniVocePendenza(Lettura)`, differiscono per un piccolo set di colonne mutuamente esclusive (`codEntrata` vs `ibanAccredito`/`ibanAppoggio`/`tassonomia` vs `tipoBollo`/`hashDocumento`/`provinciaResidenza`) — colonne nullable + discriminatore esplicito `tipoRiferimento`, non tre tabelle |
| M4 | **FK verso anagrafica come colonne `Long`, niente relazione JPA** (continuità con §4.8 del vecchio disegno): `idDominio`, `idUnitaOperativa`, `idTipoPendenza` risolti a `Long` al bordo dell'applicazione, mai `@ManyToOne` verso entità di `govpay-common`. **Diverso per le relazioni interne all'aggregato stesso** (`PosizioneDebitoria`→`SoggettoDebitore`/`OpzionePagamento`, `OpzionePagamento`→`Pendenza`, `Pendenza`→`VocePendenza`): qui la relazione JPA è appropriata, sono nella stessa persistence unit e nello stesso confine transazionale |
| M5 | **`SoggettoDebitore` è un'entità con identità propria**, non un `@Embeddable` nella lista: ha una PK e una FK esplicita `id_posizione_debitoria` |
| M6 | **Ordine di `soggettiDebitori` preservato** (colonna `ordine`, non `@OrderColumn` implicito): è la **sola** fonte di verità per "chi è il soggetto pagatore" — il primo per `ordine` (deciso dal lead, 2026-09-22, contro la mia proposta di uno snapshot esplicito, vedi nota sotto). Nessun campo aggiuntivo |

> **Nota su M6 (non più M7)**: avevo proposto uno snapshot esplicito (`soggetto_pagatore_id` su `OpzionePagamento`) per isolare il modello dati dal rischio noto (`updatePosizioneDebitoria` non impedisce di modificare `soggettiDebitori` dopo che un'opzione è ATTIVATA, quindi rileggere "live" l'ordine potrebbe cambiare retroattivamente chi risulta pagatore di una pendenza già chiusa). **Decisione del lead**: niente snapshot per ora — ci si attiene all'indicazione dello YAML (ordine + primo della lista), in attesa che pagoPA stessa evolva l'interfaccia includendo esplicitamente l'identità del soggetto pagatore nelle operazioni (RPT, ecc.). Il rischio resta noto e tracciato (punto 1 della riconciliazione), ma non viene ora assorbito dallo schema.
| M7 | **`iupd` non è una colonna**: verificato lo spec `gpd-4-aca.json`, è definito solo `type: string`, nessun formato imposto — "è responsabilità dell'EC garantirne l'unicità" (suggerito lì: `<codice fiscale ente + UUID>`, ma non vincolante). Dato che `idA2A`+`idPosizioneDebitoria` sono già garantiti univoci insieme (vincolo esplicito dello YAML v3), `iupd` si **deriva al volo** da questi (es. codice fiscale del dominio + `idA2A` + `idPosizioneDebitoria`) quando serve dialogare con ACA/GPD, senza persistere nulla di nuovo (correzione rispetto alla prima stesura di questo documento, su rilievo del lead) |
| M8 | **`nav` non è una colonna a sé**: coincide con `Pendenza.numeroAvviso`, già previsto dallo YAML. Nessun campo duplicato |
| M9 | **`StatoPendenza.SCADUTA` derivato, non persistito** (continuità con la vecchia A6): calcolato da `stato=NON_ESEGUITA` + `dataScadenza` (dell'opzione o della pendenza) nel passato, mai scritto su colonna. Le altre 6 costanti (`ESEGUITA`, `NON_ESEGUITA`, `ESEGUITA_PARZIALE`, `ANNULLATA`, `ANOMALA`, `INCASSATA`) sono lo stato persistito reale |
| M10 | **Audit e timestamp su ogni scrittura, senza eccezioni** (continuità con D10): `dataCreazione`/`dataUltimoAggiornamento` su tutte le entità dell'aggregato, non solo sulla radice |
| M11 | **Tabelle nuove**, non riuso di `versamenti`/`documenti`/`singoli_versamenti`: `posizioni_debitorie`, `soggetti_debitori`, `opzioni_pagamento`, `pendenze`, `voci_pendenza` |
| M12 | **Lock ottimistico (`@Version`) su `OpzionePagamento`** (rilievo del lead, 2026-09-22): senza un controllo di concorrenza, `attiva`/`annulla` chiamati concorrentemente sulla stessa opzione potrebbero sovrascriversi in silenzio — un annullamento basato su una lettura antecedente potrebbe vincere su un'attivazione appena registrata (o viceversa), corrompendo la macchina a stati. Con `@Version` chi scrive per secondo su una versione superata riceve un'eccezione invece di un aggiornamento perso |
| M13 | **`idDominio` denormalizzato su `Pendenza`** (rilievo del lead, 2026-09-22): IUV e NAV sono univoci **per dominio**, non globalmente — coerente con `idx_vrs_iuv_dominio` del legacy, già composto `(iuv_versamento, id_dominio)`. Un vincolo di unicità globale (come nella prima stesura di questo documento) impedirebbe a due enti creditori diversi di generare legittimamente lo stesso IUV/numero avviso, rompendo il multi-ente e la migrazione dei dati storici |

## 3. Entità proposte

### 3.1 `PosizioneDebitoria`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `idA2A` | `String` | identificativo del gestionale, con `idPosizioneDebitoria` forma la chiave logica univoca |
| `idPosizioneDebitoria` | `String` | business key, univoca per `idA2A` (409 se duplicata) — **da qui si deriva `iupd` per ACA/GPD, nessuna colonna dedicata (M7)** |
| `idDominio` | `Long` | FK, colonna (M4) |
| `idUnitaOperativa` | `Long` | FK, colonna, nullable |
| `descrizione` | `String` | max 140 |
| `dataPubblicazione` | `LocalDate` | nullable = pubblicata subito |
| `notificaSend` | `boolean` | default `false` |
| `navNotifica` | `String` | nullable, deve corrispondere al `numeroAvviso` di una pendenza figlia (vincolo applicativo, non FK — il riferimento è a un valore, non a una riga) |
| `dataUltimaModificaAca` / `dataUltimaComunicazioneAca` | `OffsetDateTime` | **nuovo, da §5** — marcatura ACA a livello posizione |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `soggettiDebitori` | `List<SoggettoDebitore>` | ordinata per `ordine` |
| `opzioniPagamento` | `List<OpzionePagamento>` | |

### 3.2 `SoggettoDebitore`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `posizioneDebitoria` | FK (`@ManyToOne`) | |
| `ordine` | `int` | posizione nell'array originale (M6) |
| `tipo` | enum `F`/`G` | |
| `identificativo` | `String` | CF/PIVA, 2-16 char |
| `anagrafica` | `String` | nullable |
| `indirizzo`, `civico`, `cap`, `localita`, `provincia`, `nazione`, `email` | `String` | tutti nullable |

### 3.3 `OpzionePagamento`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `versione` | `long` | **nuovo, M12** — lock ottimistico (`@Version`), protegge le transizioni di stato dalla concorrenza |
| `idOpzionePagamento` | `UUID` | esposto in API, generato da GovPay, stabile |
| `posizioneDebitoria` | FK | |
| `tipologia` | enum `PIANO_RATEALE`/`SOLUZIONE_UNICA`/`SOLUZIONE_UNICA_ENTRO`/`SOLUZIONE_UNICA_OLTRE` | M2 |
| `giorni` | `Integer` | nullable, richiesto solo per ENTRO/OLTRE (validazione applicativa) |
| `stato` | enum `DISPONIBILE`/`ATTIVATA`/`ANNULLATA` | macchina a stati |
| `dataInizioValidita` / `dataScadenza` | `LocalDate` | nullable |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `pendenze` | `List<Pendenza>` | ordinata (determina `numeroRata`) |

Vincoli applicativi (non a DB, coerenti con D3 "un metodo per evento di
dominio"): `PIANO_RATEALE` e `SOLUZIONE_UNICA` richiedono almeno 1 pendenza,
senza limite massimo (per `SOLUZIONE_UNICA` da quando un dovuto con più di 5
voci richiede più di un avviso, quindi più di una pendenza — vedi
`riconciliazione-legacy-v3.md` §4 punto 6); `PIANO_RATEALE` in pratica ne
richiede almeno 2, altrimenti si userebbe `SOLUZIONE_UNICA`.
`SOLUZIONE_UNICA_ENTRO`/`SOLUZIONE_UNICA_OLTRE` restano a esattamente 1
pendenza — **da confermare se il requisito ">5 voci" valga anche per queste
due**, non ancora deciso. `giorni` obbligatorio solo per ENTRO/OLTRE.

### 3.4 `Pendenza`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `opzionePagamento` | FK | |
| `idDominio` | `Long` | **nuovo (M13)** — denormalizzato dalla posizione: IUV/NAV sono univoci per dominio, non globalmente (rilievo del lead, 2026-09-22) |
| `idPendenza` | `String` | business key |
| `idTipoPendenza` | `Long` | FK, colonna (M4) |
| `numeroRata` | `int` | derivato dalla posizione nell'array (assegnato da GovPay) |
| `importo` | `BigDecimal` | continuità §4.2 del vecchio disegno (conversione dedicata, mai `new BigDecimal(double)`) |
| `numeroAvviso` | `String` | = NAV (M8), generato se assente; univoco insieme a `idDominio` (M13), non da solo |
| `iuv` | `String` | generato; univoco insieme a `idDominio` (M13), non da solo |
| `stato` | enum (6 valori persistiti, M9) | |
| `dataPagamento` | `LocalDate` | nullable |
| `dataCaricamento` | `LocalDate` | = data creazione, business-facing |
| `dataValidita` | `LocalDate` | nullable |
| `dataScadenzaAvviso` | `LocalDate` | nullable, override di stampa |
| `dataUltimaModificaAca` / `dataUltimaComunicazioneAca` | `OffsetDateTime` | **nuovo, da §5** — marcatura ACA a livello pendenza/avviso |
| `dataCreazione` / `dataUltimoAggiornamento` | `OffsetDateTime` | M10 |
| `voci` | `List<VocePendenza>` | |

### 3.5 `VocePendenza`

| Campo | Tipo | Note |
|---|---|---|
| `id` | `Long` (PK) | tecnico |
| `pendenza` | FK | |
| `idVocePendenza` | `String` | business key |
| `importo` | `BigDecimal` | |
| `descrizione` | `String` | max 140 |
| `indice` | `int` | 1-5, ordine nella pendenza |
| `stato` | enum `Eseguito`/`Non eseguito`/`Anomalo` | valori così come nello YAML (maiuscole non uniformi, da preservare) |
| `idDominio` | `Long` | FK, nullable — override multi-beneficiario |
| `tipoRiferimento` | enum `RIFERIMENTO_ENTRATA`/`ENTRATA`/`BOLLO` | discriminatore (M3) |
| `codEntrata` | `String` | nullable, solo `RIFERIMENTO_ENTRATA` |
| `ibanAccredito`, `ibanAppoggio`, `tassonomia` | `String` | nullable, solo `ENTRATA` (e `tassonomia` anche per `BOLLO`) |
| `tipoBollo`, `hashDocumento`, `provinciaResidenza` | `String` | nullable, solo `BOLLO` |

`dettaglioContabile` (array, solo per `ENTRATA`/`RIFERIMENTO_ENTRATA`) **non è
in questo primo disegno** — vedi §6.

## 4. Come il modello accomoda i punti ancora aperti (senza deciderli)

- **Punto 1 (soggetto per RPT/stampa avviso)**: **non assorbito dallo schema,
  per decisione esplicita del lead** — niente snapshot dedicato (vedi nota su
  M6): si usa `soggettiDebitori` ordinato per `ordine`, primo elemento per
  convenzione, esattamente come indica lo YAML. Il rischio noto
  (`updatePosizioneDebitoria` non impedisce modifiche a `soggettiDebitori` dopo
  che un'opzione è ATTIVATA) resta quindi presente e non mitigato a livello di
  schema, in attesa che pagoPA evolva l'interfaccia includendo esplicitamente
  l'identità del soggetto pagatore nelle operazioni.
- **Punto 2 (avvisatura granulare)**: **non modellato in questo disegno** —
  nessun campo per giorni di preavviso mail/AppIO su `PosizioneDebitoria`/
  `Pendenza`. Se la decisione futura sarà di reintrodurla, servirà una
  migrazione di schema successiva.
- **Punto 4 (debitori incoerenti in migrazione)**: non è un problema di
  schema — è la migrazione a doverlo gestire (vedi `riconciliazione-legacy-v3.md`
  §4 punto 4).
- **ACA (chiarito, §5 di questo documento)**: `iupd` derivato da `idA2A`+
  `idPosizioneDebitoria` (M7, nessuna colonna), `nav` = `numeroAvviso` (M8) — le
  coppie `dataUltimaModificaAca`/`dataUltimaComunicazioneAca` restano su
  **entrambi** i livelli (posizione e pendenza), coerenti con
  `PaymentPositionModel`(`iupd`)/`PaymentOptionModel`(`nav`) dello spec pagoPA
  `gpd-4-aca.json`.

## 5. Cosa NON è in questo primo disegno (rimandato)

- `dettaglioContabile` completo (4 varianti: CorrispettivoDL118/IncassoTipico/
  Civilistico/ImportoNotifica) — solo abbozzato come collezione futura su
  `VocePendenza`
- Ricevute (`Ricevuta`, 3 formati XML), Rendicontazioni — fuori perimetro
  dell'aggregato pendenza in senso stretto, come nel vecchio disegno (A1)
- Ricerca, criteri, paginazione (sarà una fase a sé, come F2 nel vecchio
  disegno) — i 3 repository Spring Data esistenti coprono solo i lookup per
  chiave (identificativo, UUID, IUV/NAV)
- Aggiornamenti oltre `attiva`/`annulla` (PATCH, annullamento pendenza, ecc.)
  — le validazioni di dominio sono invece fatte, vedi §8
- Migrazione dati vera e propria (script), oggetto di un documento a parte

**Aggiornamento (2026-09-22): implementato da quando questo elenco è stato
scritto** — `repository/` (3 interfacce), `service/PosizioneDebitoriaService`
(`crea`/`trovaPerId`/`trovaPerIdentificativo`/`attiva`/`annulla`, quest'ultime
due la macchina a stati vera e propria con lock ottimistico M12), `exception/`
(2 eccezioni), e `spi/GeneratoreIuv` (generazione IUV/numero avviso quando il
chiamante non li fornisce — l'algoritmo reale resta al consumatore, questa
libreria non ha accesso all'anagrafica del dominio, M4). 15 test, tutti verdi
(build reale, non solo compilazione).

## 6. Punti da validare prima di procedere all'implementazione

1. Conferma della struttura a tabella unica per `OpzionePagamento` (M2) e
   `VocePendenza` (M3) invece di gerarchie JPA per tipologia.
2. Conferma che le relazioni interne all'aggregato (M4, seconda parte) possano
   essere `@ManyToOne`/`@OneToMany` reali, a differenza delle FK verso
   l'anagrafica esterna.
3. Conferma di M8 (nav = numeroAvviso, senza colonna dedicata).
4. ~~`cardinalitaPendenzeMassima()` di `SOLUZIONE_UNICA` (M2)~~ — **risolto
   (2026-09-22)**: confermato dal lead che lo YAML va aggiornato (oggi impone
   ancora `maxItems: 1`) per consentire più pendenze con `SOLUZIONE_UNICA`
   (dovuto con >5 voci → >1 avviso, vedi `riconciliazione-legacy-v3.md` §4
   punto 6). Implementato: `SOLUZIONE_UNICA` senza limite massimo, come
   `PIANO_RATEALE`. **Nuovo punto da confermare**: `SOLUZIONE_UNICA_ENTRO`/
   `OLTRE` restano a 1 pendenza — la richiesta discussa riguardava solo
   `SOLUZIONE_UNICA`, non le due varianti con termine.

## 7. Dipendenza da `govpay-common` (aggiunta il 2026-09-23)

**M4 era stato applicato in modo più restrittivo di quanto scritto**: vieta solo
le relazioni JPA (`@ManyToOne`) verso le entità di `govpay-common`, non l'uso
della libreria in generale. Il vecchio disegno (`proposta-libreria-pendenze.md`
§3.1, decisione B2) prevedeva esplicitamente questa dipendenza, per riusare le
utility condivise. Corretto: aggiunta la dipendenza (stesso pattern di
`govpay-console-api`: `${govpay-common.version}`, "not in BOM").

**Revisione di cosa avremmo potuto riusare e non abbiamo riusato** (richiesta
del lead): trovata **una sola sovrapposizione reale**, minore —
`PendenzeProperties.FUSO_ORARIO_DEFAULT` duplicava come letterale
`DateTimePatterns.DEFAULT_TIME_ZONE` (stessa stringa `"Europe/Rome"`);
corretto per riferirsi a quella costante. Nessun'altra sovrapposizione trovata:
gli enum di dominio (`TipoSoggetto`, stati, tipologie) non hanno equivalente in
`govpay-common`; non esiste un converter `BigDecimal`/importi equivalente al
nostro; il bean `Clock` e il customizer del fuso orario Hibernate di
`PendenzeAutoConfiguration` sono originali, `govpay-common` non espone nulla
del genere.

**Per `GeneratoreIuv`**: `govpay-common.utils.IuvUtils` ha oggi solo funzioni di
**verifica** (`isIuvInterno`, `isNumeric` — per capire se uno IUV già esistente
è interno), non di generazione. Verificato anche `jars/core/.../business/Iuv.java`
nel monorepo: la generazione reale richiede oggetti anagrafica completi
(`Applicazione`, `Dominio` con aux digit/stazione/prefisso custom) e un
contatore persistito per l'unicità — non e' una funzione pura delegabile a
un'utility statica. La SPI resta quindi necessaria per intero: nulla da
riusare da `govpay-common` oggi. Se in futuro quella libreria guadagnasse
funzioni di conversione/generazione (come il vecchio disegno auspicava), va
rivista.

**Decisi** (non più da validare):
- **M6** — niente snapshot del soggetto pagatore, si usa `soggettiDebitori`
  ordinato + primo elemento, come da YAML (vedi nota su M6 in §2).
- **M7** — niente colonna `iupd`, derivato al volo da `idA2A`+
  `idPosizioneDebitoria` (verificato sullo spec `gpd-4-aca.json`: nessun
  vincolo di formato, l'unicità è responsabilità dell'EC).

## 8. Validazioni di dominio (aggiunto il 2026-09-23)

Nuovo package `validazione/`, classe `ValidatorePosizioneDebitoria` — senza
stato ne' dipendenze da Spring/JPA (stesso principio di `model/`), invocata da
`PosizioneDebitoriaService.crea` prima di valorizzare audit/IUV. Copre i
vincoli semantici che lo schema JSON non può esprimere da solo:

- almeno un `SoggettoDebitore` e almeno una `OpzionePagamento` sulla posizione
- cardinalità delle pendenze per tipologia, usando
  `TipologiaOpzionePagamento.cardinalitaPendenzeMinima/Massima` (scritti in
  precedenza ma non ancora cablati in nessuna validazione)
- `giorni` obbligatorio e positivo per `SOLUZIONE_UNICA_ENTRO`/`OLTRE`, non
  ammesso per le altre due tipologie
- ogni pendenza ha da 1 a 5 voci
- l'importo di ogni pendenza coincide con la somma delle sue voci
  (confronto con `compareTo`, non `equals`, per non essere sensibile alla
  scala del `BigDecimal`)

Nuova eccezione `ValidazioneNonSuperataException`. 9 nuovi test (24 totali),
tutti JUnit puro senza `@DataJpaTest` — il validatore non ha bisogno di un
database per essere testato.

## 9. Quattro bug in `crea()` trovati in revisione e corretti (2026-09-23)

Tutti scoperti dal lead con casi concreti, non dalla suite di test esistente —
`crea()` compilava e i 24 test di prima passavano comunque, perché nessuno
copriva questi scenari.

**1. Un numero avviso fornito poteva essere sostituito.** La condizione era
`if (iuv == null || numeroAvviso == null)`: se il chiamante forniva solo uno
dei due, `crea()` generava una **coppia nuova**, scartando in silenzio quello
fornito. Corretto: genera solo se **entrambi** sono assenti; se ne manca solo
uno, `ValidazioneNonSuperataException` esplicita — completare la coppia
preservando l'identificativo fornito richiederebbe l'algoritmo di conversione
IUV↔NAV (dipendente dalla configurazione del dominio, M4), che
{@link GeneratoreIuv} non espone (genera sempre coppie nuove). Non risolvibile
senza estendere la SPI: rifiutato esplicitamente, come suggerito.

**2. Gli indici delle collezioni non venivano assegnati.** `numeroRata` (su
`Pendenza`) e `ordine` (su `SoggettoDebitore`) restavano al valore di default
(`0`): più pendenze finivano tutte con `numeroRata=0`, più soggetti senza
`ordine` esplicito violavano il vincolo di unicità `(id_posizione_debitoria,
ordine)`. La Javadoc diceva "assegnato dal servizio di caricamento" ma
`crea()` non lo faceva davvero. Corretto: nuovo metodo privato
`assegnaIndici`, chiamato per primo in `crea()`, assegna `ordine` (0-based) e
`numeroRata` (1-based) dalla posizione nelle liste — **sempre**, sovrascrivendo
anche un valore che il chiamante avesse impostato, perché nessuno dei due è
scrivibile in API (per `numeroRata` lo YAML lo dice esplicitamente) e l'unica
fonte di verità è l'ordine delle liste stesse.

**3. La somma degli importi poteva diventare incoerente dopo il salvataggio.**
Due voci da `0.005` e una pendenza da `0.01` superano il confronto in memoria
(`compareTo` non distingue la scala), ma la colonna `NUMERIC(19,2)` arrotonda
**implicitamente** ciascuna voce a `0.01` in scrittura: alla rilettura la
somma è `0.02`, non più coerente. Corretto: `ValidatorePosizioneDebitoria`
rifiuta ora qualunque importo con più di 2 decimali significativi
(`importo.setScale(2, RoundingMode.UNNECESSARY)`, che solleva
`ArithmeticException` se ci sono cifre significative oltre la seconda) —
stesso principio del vecchio `ImportoConverter` (che avevo scartato insieme al
resto del porting legacy, senza notare che il problema della precisione era
indipendente dal problema `DOUBLE PRECISION` multi-DB che l'aveva originato).

**4. Mancavano controllo e assegnazione di `navNotifica`.** `crea()` non
verificava che `navNotifica` (se fornito) corrispondesse al `numeroAvviso` di
una pendenza della posizione (previsto esplicitamente dallo YAML v3), né lo
assegnava automaticamente quando `notificaSend` è attivo e `navNotifica` è
assente — nemmeno nel caso più semplice (una sola pendenza). Corretto: nuovo
metodo privato `assegnaOValidaNavNotifica`, chiamato per ultimo in `crea()`
(dopo che `numeroRata` e `numeroAvviso` sono stati assegnati, da cui dipende):
valida la corrispondenza se `navNotifica` è fornito; altrimenti, se
`notificaSend` è attivo, lo assegna alla pendenza dell'opzione
`SOLUZIONE_UNICA` se presente, altrimenti alla rata 1 dell'unico
`PIANO_RATEALE` (semantica esatta dello YAML). Se nessuno dei due casi si
applica (es. solo opzioni `ENTRO`/`OLTRE`), resta `null` — non è un caso
coperto dalla regola nota, non si tenta un'assegnazione arbitraria.

7 nuovi test di regressione (31 totali): due per la coppia IUV/NAV parziale
(solo numeroAvviso, solo iuv) più uno che conferma che una coppia fornita per
intero non viene mai toccata; uno per l'assegnazione di indici; uno per il
rifiuto di importi con troppi decimali; due per `navNotifica` (rifiuto se non
corrisponde, assegnazione automatica).

## 10. Altri due bug trovati in revisione dello stesso giro (2026-09-23)

**5. `VocePendenza.indice` dimenticato in `assegnaIndici`.** Il fix del punto 9.2
copriva `ordine` (soggetti) e `numeroRata` (pendenze) ma non l'indice delle
voci: due voci aggiunte con `addVocePendenza()` restavano entrambe a `0`,
violando `unique_voci_pendenza_1 (id_pendenza, indice)` al salvataggio.
Corretto: `assegnaIndici` ora itera anche le voci di ogni pendenza,
assegnando `indice` 1-based dalla posizione nella lista.

**6. `notificaSend` poteva essere persistito con `navNotifica` nullo.**
`assegnaOValidaNavNotifica` (punto 4 del §9) copriva solo due casi
(`SOLUZIONE_UNICA` presente, o `PIANO_RATEALE` presente) per l'assegnazione
automatica; se la posizione aveva **solo** opzioni `SOLUZIONE_UNICA_ENTRO`/
`OLTRE`, nessuno dei due si applicava e il metodo lasciava silenziosamente
`navNotifica` a `null`, permettendo di salvare `notificaSend=true` con
`navNotifica` mancante — una configurazione incompleta accettata senza
errore. Corretto: se non si trova un candidato, `crea()` rifiuta
esplicitamente con `ValidazioneNonSuperataException`, richiedendo un
`navNotifica` indicato dal chiamante. La scelta automatica per il caso
solo-`ENTRO`/`OLTRE` resta un punto da chiarire (quale pendenza scegliere,
se ce ne può essere più di una anche qui) — per ora si preferisce rifiutare
piuttosto che indovinare.

2 nuovi test di regressione (33 totali): uno riproduce esattamente lo scenario
segnalato (due voci via `addVocePendenza`, verificato `indice` 1 e 2); uno
verifica il rifiuto con sole opzioni `ENTRO`.

## 11. Generazione IUV: SPI standard, progressivi, conversione da NAV (2026-09-23)

Il lead ha messo in discussione l'architettura di `GeneratoreIuv` confrontandola
punto per punto con il generatore legacy (`IuvBD`/`VersamentoUtils`), con 4
osservazioni verificate sul codice del monorepo prima di essere accolte:

1. **NAV fornito dal chiamante → si ricava l'IUV, non si genera una coppia.**
   Confermato in `VersamentoUtils` (righe ~798-810): se `numeroAvviso` è
   presente, l'IUV si estrae per formato (`getIuvFromNumeroAvviso`) e si valida
   (`checkIUV`), senza toccare alcun progressivo. È un percorso distinto dalla
   generazione, non la stessa operazione con un ramo diverso.
2. **Algoritmo di generazione**: aux digit 0/1/2/3, check digit mod-93,
   prefisso dominio — confermato leggendo `IuvBD.generaIuv` per intero.
3. **Progressivo a blocchi**: `IuvBD.getNextPrgIuv` usa
   `org.openspcoop2.utils.id.serial.IDSerialGenerator` con `sizeBuffer(100)` e
   chiave `codDominio+iuvPrefix+tipoIUV.toString()` — block reservation +
   buffer in memoria per JVM, non un semplice contatore per chiamata.
4. **Transazione separata**: quel generatore richiede una connessione JDBC con
   autocommit attivo su cui gestisce da solo commit/rollback — incompatibile
   con una transazione JPA aperta, da cui il commento legacy "l'utility di
   generazione non supporta le transazioni" e l'apertura di una connessione
   dedicata quando il chiamante è già in una transazione.

**Decisione: non riusare la libreria openspcoop2, riusare le garanzie e — dove
possibile — la tabella fisica.** Verificato che `ID_MESSAGGIO_RELATIVO`
(`PROTOCOLLO='GovPay'`, `INFO_ASSOCIATA=codDominio+iuvPrefix+tipoIUV`) è già
parte dello schema proprio di GovPay (`gov_pay.sql`, non solo ereditata da
openspcoop2/govway), ed è condivisa con un secondo consumatore reale
(`TracciatiNotificaPagamentiBD.getNextPrgTracciato`, stesso `PROTOCOLLO`,
chiave `codDominio+tipoTracciato`) — disambiguazione solo per convenzione sui
valori delle stringhe, nessun vincolo strutturale. Confermato anche che non ci
sarà mai coesistenza temporale vecchio/nuovo sistema (cutover netto, coerente
con la scelta già presa di non gestire la retrocompatibilità), quindi il
rischio di due strategie di lock diverse sulla stessa riga non si pone.

Continuare a scrivere sulla **stessa riga** (stessa chiave, `PROTOCOLLO`,
`INFO_ASSOCIATA`) invece di copiarne il valore in una tabella nuova elimina
anche la finestra di corsa del cutover (uno snapshot letto e poi copiato può
essere già superato se il vecchio sistema emette un altro IUV nel frattempo;
continuare sulla riga esistente non ha questo problema perché non c'è
snapshot da tenere allineato) — **nessun passo di migrazione dedicato ai
progressivi IUV è quindi necessario**, a differenza di quanto ipotizzato in
un primo momento.

**Implementato**, nuovo package `iuv/`:

- `entity/ProgressivoIuv` + `entity/ProgressivoIuvId` — mappano
  `id_messaggio_relativo` così com'è (stessa tabella, stesse colonne
  `counter`/`protocollo`/`info_associata`), non una tabella nuova.
- `repository/ProgressivoIuvRepository.findByIdForUpdate` — lettura con
  `@Lock(PESSIMISTIC_WRITE)`, equivalente nativo JPA del `SELECT ... FOR
  UPDATE` legacy.
- `iuv/AllocatoreBloccoProgressivoIuv.allocaBlocco` —
  `@Transactional(REQUIRES_NEW)`: transazione dedicata, equivalente nativo
  JPA della connessione separata legacy (un rollback della
  `PosizioneDebitoria` non fa tornare indietro un progressivo già
  consegnato). Classe separata da `GeneratoreProgressivoIuv` apposta:
  `@Transactional` su self-invocation non passerebbe dal proxy Spring.
- `iuv/GeneratoreProgressivoIuv.prossimoValore` — buffer in memoria per
  chiave (`Map<String, Deque<Long>>`), stesso principio del buffer statico
  per JVM legacy; ritenta una volta sola se la prima allocazione per una
  chiave nuova va in conflitto (due chiamate concorrenti che tentano
  entrambe l'INSERT iniziale).
- `iuv/CostruttoreIdentificativiPagamento` — porting fedele
  dell'algoritmo (aux digit, check digit mod-93, formato NAV), verificato
  con valori attesi calcolati indipendentemente (non solo coerenza interna
  fra generazione e conversione). Include anche `convertiDaNumeroAvviso`,
  che ricava e valida (check digit ricalcolato) l'IUV da un NAV fornito,
  senza consumare progressivi.
- `iuv/GeneratoreIuvStandard implements GeneratoreIuv` — legge
  `DominioEntity`/`StazioneEntity` da `govpay-common` (dipendenza di
  libreria, non relazione JPA — M4 vieta solo la seconda), implementazione
  condivisa invece di lasciare l'intero algoritmo a ogni consumatore.
  Registrata da `PendenzeAutoConfiguration` come bean
  `@ConditionalOnMissingBean(GeneratoreIuv.class)` +
  `@ConditionalOnBean(DominioRepository.class)`: attiva solo se il
  consumatore ha già l'anagrafica di govpay-common nel contesto e non ha
  fornito una propria implementazione.

**SPI estesa**: `GeneratoreIuv.convertiDaNumeroAvviso(idDominio,
numeroAvviso)`, `default` (implementazione di base: `UnsupportedOperationException`)
così l'interfaccia resta lambda-compatibile per chi vuole implementare solo
`genera`. `PosizioneDebitoriaService.assegnaIdentificativiPagamento`
distingue ora tre casi: entrambi assenti → genera; solo `numeroAvviso` →
converte; solo `iuv` → rifiuta (nessun percorso legacy verificato per
ricostruire il NAV dal solo IUV, non se ne inventa uno).

**Bug trovato durante l'implementazione (non di logica, di configurazione
di test) e corretto**: lo schema di test H2 (`schema-pendenze-test.sql`) non
era idempotente (`CREATE TABLE`/`CREATE SEQUENCE` senza `IF NOT EXISTS`).
Innocuo finché ogni test apriva una sola connessione; `AllocatoreBloccoProgressivoIuv`
ne apre una seconda (`REQUIRES_NEW`), e H2 riesegue lo script `INIT` su
**ogni** nuova connessione alla stessa URL, non solo alla prima — la seconda
falliva su "already exists" e HikariCP passava 30s a ritentare la creazione
della connessione prima di arrendersi. Corretto rendendo idempotenti tutte le
istruzioni dello schema.

20 nuovi test (53 totali): `CostruttoreIdentificativiPagamentoTest` (11, puro
algoritmo, nessuna dipendenza Spring), `GeneratoreProgressivoIuvTest` (4,
inclusa la prova che la libreria continua da un valore già presente nella
riga — il punto centrale della scelta di riuso della tabella),
`GeneratoreIuvStandardTest` (4, contro anagrafica reale di govpay-common),
`PosizioneDebitoriaServiceConGeneratoreIuvTest` (1 nuovo: conversione da
NAV fornito).

**Punto aperto tracciato, non bloccante**: verificare con chi gestisce la
versione precedente se esistono altri utilizzatori di `ID_MESSAGGIO_RELATIVO`
con `PROTOCOLLO='GovPay'` oltre a `IuvBD`/`TracciatiNotificaPagamentiBD`, per
escludere collisioni di chiave non ancora note.
