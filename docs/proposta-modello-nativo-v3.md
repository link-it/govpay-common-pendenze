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

> **Nota (2026-09-25/26), da leggere prima del resto**: le tabelle *nuove* di
> §§1-16 (`posizioni_debitorie`, `pendenze`, `voci_pendenza`, `ricevute`,
> `rendicontazioni`, `flussi_rendicontazione` come schema v3 separato) sono
> state **abbandonate** in favore del riuso diretto delle tabelle legacy
> (`documenti`/`versamenti`/`singoli_versamenti`/`rpt`/`pagamenti`/`fr`/
> `rendicontazioni`) — vedi §17 in poi. Le entità/decisioni di mapping restano
> valide dove non in contraddizione con §17+ (validazioni di dominio, criteri
> di ricerca, generazione IUV, `dettaglioContabile`), ma ogni riferimento a
> nomi di tabella in §§1-16 va inteso come lo schema abbandonato, non quello
> reale.

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
pendenza — confermato dal lead (2026-09-24): non è previsto che ammettano
più pendenze come `SOLUZIONE_UNICA`. `giorni` obbligatorio solo per
ENTRO/OLTRE.

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
   `PIANO_RATEALE`. `SOLUZIONE_UNICA_ENTRO`/`OLTRE` restano a 1 pendenza —
   **confermato dal lead (2026-09-24)**: non è previsto che ammettano più
   pendenze come `SOLUZIONE_UNICA`.

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

## 12. Due bug e una lacuna in `GeneratoreIuvStandard`/`CostruttoreIdentificativiPagamento`, trovati in revisione (2026-09-23/24)

Tutti scoperti dal lead con casi concreti riprodotti, verificati sul codice
legacy prima di essere corretti.

**1. I prefissi dinamici del vecchio GovPay non venivano risolti.** Il
prefisso configurato sul dominio veniva usato letteralmente, senza
sostituire i placeholder `%(a)`/`%(p)`/`%(t)`/`%(y)`/`%(Y)`. Verificato in
`CustomIuv.buildPrefix` (delimitatori `%(`/`)`, valori da
`PagamentoContext.getAllIuvProps`) e in `Iuv.generaIUV`, che risolve il
prefisso **prima** di chiamare `IuvBD.generaIuv` — quindi prima sia della
chiave del contatore sia della costruzione dello IUV — e valida
esplicitamente che il risultato sia numerico, con un errore chiaro invece di
un `NumberFormatException` grezzo. Riprodotto il caso `%(y)`: la nuova
generazione terminava con `NumberFormatException`. Corretto: nuova classe
`iuv/RisolutorePrefissoIuv` (porting senza la dipendenza da
`commons-text.StringSubstitutor`, non necessaria per un set fisso di
chiavi), che risolve `%(Y)`/`%(y)` (anno, dall'orologio della libreria) e
`%(a)` (`Applicazione.codApplicazioneIuv`, cercata per `idA2A` — confermato
dal lead: `idA2A` è esattamente `Applicazione.codApplicazione`)
prima di usare il prefisso sia per la chiave del contatore sia per lo IUV.
Un placeholder non risolvibile solleva un errore esplicito che lo nomina,
invece di restare letterale nel testo.

**2. Il controllo di lunghezza della reference usava sempre il limite di 15
caratteri.** `costruisciReference` rifiutava solo se `reference.length() >
15`, anche quando la reference deve essere di 13 (AuxDigit 0 e 3). Caso
riprodotto: AuxDigit 3, segregazione 12, prefisso `123456789012` (12 cifre),
progressivo 10 → NAV di 19 cifre invece di 18. Difetto ereditato dal vecchio
`IuvBD.generaIuv` (stesso controllo sempre `>15`), ma non riportato qui.
Corretto: il controllo usa ora `lunghezzaTotale` (il parametro già passato
alla chiamata, 13 o 15 secondo l'AuxDigit).

**3. `%(p)`/`%(t)` (codifica del tipo pendenza) inizialmente non
supportati.** Nel legacy rappresentano `TipoVersamento.codificaIuv`
(`PagamentoContext`: `%(p)` e `%(t)` sono alias storici dello stesso
valore). Non risolvibili da questa libreria da sola: `govpay-common` non
espone un'anagrafica tipo-versamento (M4). Prima soluzione (rifiuto
esplicito) lasciava gli enti che usano questi prefissi bloccati dopo il
passaggio alla v3 — segnalato esplicitamente dal lead come insufficiente.
Corretto estendendo la SPI: `GeneratoreIuv.genera` ha ora un quarto
parametro, `codificaIuvTipoPendenza`, sorgente un nuovo campo **transiente**
(mai persistito) `Pendenza.codificaIuvTipoPendenza` — il chiamante che
conosce quel valore (fuori da questa libreria, dove vive l'anagrafica
tipo-versamento) lo imposta sulla pendenza prima di `crea()`. Usando il
prefisso risolto per costruire sia la chiave del progressivo sia lo IUV
(già stabilito al punto 1), codifiche diverse sullo stesso dominio ottengono
progressivi indipendenti — preservando la stessa composizione della chiave
del legacy (`codDominio+prefix+tipo`).

9 nuovi test (65 totali): `CostruttoreIdentificativiPagamentoTest` (+1, la
riproduzione esatta del caso AuxDigit 3/prefisso 12 cifre/progressivo 10),
`GeneratoreIuvStandardTest` (+6: risoluzione `%(y)`/`%(a)`/`%(p)`, alias
`%(t)`, rifiuto esplicito senza valore fornito, progressivi indipendenti per
codifiche diverse), `RisolutorePrefissoIuvTest` (nuovo, 5 test puri).

**Confermato dal lead (24/09)**: `idA2A` ≡ `Applicazione.codApplicazione`
(non più un punto aperto).

## 13. Ricerca, criteri, paginazione (2026-09-24)

Nuovo package `criteri/`:

- `OffsetPageRequest implements Pageable` — paginazione a scorrimento
  libero (offset/limit, standard AGID RAC_REST_NAME_005), non a pagine
  allineate come `PageRequest` (che ammette solo `offset = pagina *
  dimensione`): il cliente può avanzare l'offset di un valore qualunque
  seguendo `prossimiRisultati`, non necessariamente un multiplo di `limit`.
- `CriteriOrdinamento` — analizza il parametro di query `sort`
  (`+campo,-campo2`) contro una mappa esplicita di campi ordinabili fornita
  dal chiamante (nome esterno → percorso JPA): nessun campo ordinabile per
  default, un nome non riconosciuto è un errore esplicito
  (`ValidazioneNonSuperataException`), non un ordinamento arbitrario su un
  percorso interno non previsto.

Verificati sui due endpoint di ricerca reali dello YAML v3 (non inventati):

- `GET /posizioni-debitorie/{idA2A}` — `idDebitore` è l'**unico** criterio di
  ricerca ammesso (query, obbligatorio), oltre a `idA2A` (path). Trova la
  posizione se l'identificativo corrisponde a **qualunque** soggetto in
  `soggettiDebitori`, non solo al primo (debitori in solido). Implementato:
  `PosizioneDebitoriaRepository.findDistinctByIdA2AAndSoggettiDebitori_Identificativo`,
  `PosizioneDebitoriaService.cercaPerDebitore`.
- `GET /pendenze/{idA2A}` — `numeroAvviso` è l'**unico** criterio,
  obbligatorio; `idDominio` è un filtro aggiuntivo opzionale, utilizzabile
  solo insieme a `numeroAvviso` (mai da solo, per esplicita indicazione
  dello YAML). Senza `idDominio` può restituire più risultati, perché lo
  stesso NAV può esistere legittimamente su domini diversi (M13).
  Implementato: due varianti in `PendenzaRepository` (con/senza filtro
  dominio), `PosizioneDebitoriaService.cercaPendenze`.

**Non implementato**: il parametro di query `fields` (proiezione parziale
dei campi in risposta) — è un problema di serializzazione JSON lato API,
non di accesso ai dati, fuori perimetro di questa libreria.

14 nuovi test (79 totali): `OffsetPageRequestTest` (5), `CriteriOrdinamentoTest`
(6), 3 di integrazione in `PosizioneDebitoriaServiceTest` contro il DB reale
(incluso uno che dimostra esplicitamente M13: stesso NAV su domini diversi,
trovato senza filtro, ristretto a uno con `idDominio`).

**Bug trovato dal lead e corretto (2026-09-24): `hasNext()` poteva risultare
`true` con i risultati già esauriti.** `Page`/`Pageable` di Spring Data sono
pensati per pagine allineate: `PageImpl.hasNext()` si basa su `getNumber()+1
< getTotalPages()`, e `OffsetPageRequest.getPageNumber()` può solo
approssimare (`offset / limit` troncato) un offset arbitrario non allineato.
Riprodotto: 3 risultati totali, offset 1, limit 2 → restituiti gli ultimi
due, ma `hasNext()` risultava `true` (avrebbe prodotto un `prossimiRisultati`
verso una pagina vuota). Non risolvibile facendo tornare corretto
`getPageNumber()` in generale: non esiste un numero di pagina che renda
`PageImpl.hasNext()` corretto per un offset qualunque. Corretto introducendo
`criteri/PaginaRisultati<T>` (record: `risultati`, `offset`, `limit`,
`numeroRisultatiTotali`), il cui `haAltriRisultati()` usa direttamente
`offset + risultati.size() < numeroRisultatiTotali` — corretto per qualunque
offset. `PosizioneDebitoriaService.cercaPerDebitore`/`cercaPendenze` ora
restituiscono `PaginaRisultati<T>`, non più `Page<T>`: gli unici dati letti
da `Page` sono `getContent()`/`getTotalElements()` (affidabili, vengono da
una query di conteggio separata), mai i suoi metodi derivati. 4 nuovi test
(96 totali): `PaginaRisultatiTest` (3, puri), 1 in `PosizioneDebitoriaServiceTest`
che riproduce esattamente lo scenario segnalato contro il DB reale.

## 14. `dettaglioContabile` (2026-09-24)

Riconciliazione contabile pagoPA (Dizionario dei metadata, issue #877 dello
YAML v3), rimandata esplicitamente al §5. Attaccata a `VocePendenza` di tipo
`RIFERIMENTO_ENTRATA`/`ENTRATA` (mai `BOLLO`, che si classifica solo tramite
`tassonomia`).

**Modello** (`model/DettaglioContabile`): sealed interface, 5 record — 4
scrivibili (`CorrispettivoDl118`, `IncassoTipico`, `Civilistico`,
`SpeseNotifica`) discriminati dal campo `tipo`, più `Sconosciuto`
(`UNKNOWN_ENTRIES`) di sola lettura, mai accettata in scrittura (fallback
per metadata di un intermediario/tecnologia terza).

**Persistenza** (`model/DettaglioContabileConverter`): `AttributeConverter`
JPA, JSON in colonna `voci_pendenza.dettaglio_contabile`
(`@JdbcTypeCode(SqlTypes.LONGVARCHAR)`, stesso principio di
`configurazione.valore` in govpay-common). A differenza del vecchio
`ProprietaPendenzaCodec` (dati storici, decodifica tollerante con log a WARN
su JSON malformato), qui un JSON illeggibile è un bug di questa libreria,
non un dato esterno sporco: nessuna tolleranza, fallisce esplicitamente.

**Bug Jackson 3 trovato e corretto durante l'implementazione**:
`ObjectMapper.writeValueAsString(Object)` su una `List<DettaglioContabile>`
perde il discriminatore polimorfico `tipo`. Causa: per l'erasure dei
generici, Jackson risolve il serializzatore di ogni elemento sulla sua
classe concreta (es. `Civilistico`), non sul tipo dichiarato della lista
(`DettaglioContabile`, dove vive `@JsonTypeInfo`) — verificato con un test
diagnostico standalone: funziona per un singolo valore dichiarato come
`DettaglioContabile`, sparisce per gli elementi di una lista. La lettura
falliva poi con "missing type id property". Corretto usando
`writerFor(JavaType)` esplicito in scrittura, simmetrico a
`readValue(dbData, JavaType)` già usato in lettura.

**Validazioni** (`ValidatorePosizioneDebitoria`): `dettaglioContabile`
vietato su voci `BOLLO`; `CORRISPETTIVO_DL118`/`CIVILISTICO` richiedono
esattamente uno tra i rispettivi campi alternativi (`capitolo`/
`accertamento`/`pianoFinanziario5Livello`; `conto`/`commessa`/
`nrDocumento`); `INCASSO_TIPICO` non ammette sia `emissioneFattura` sia
`nrDocumento` insieme; `UNKNOWN_ENTRIES` sempre rifiutato in scrittura;
`notificaSend` attivo con una voce che ha già `SPESE_NOTIFICA` rifiutato
(altrimenti le spese verrebbero applicate due volte — semantica esplicita
dello YAML v3). Non validati i vincoli di lunghezza dei singoli campi (già
espressi dallo schema JSON, non compito di questa libreria).

13 nuovi test (92 totali): `DettaglioContabileConverterTest` (4, incluso il
round trip di `Sconosciuto`), 9 nuovi in `ValidatorePosizioneDebitoriaTest`,
1 in `PosizioneDebitoriaMappingTest` (round trip attraverso il DB reale).

## 15. Utility IUV pure spostate in `govpay-common` (2026-09-24)

Proposta del lead: le funzioni senza dipendenza da DB/Spring, e funzionalmente
indipendenti dal modello di dominio delle pendenze (non solo prive di
persistenza), vanno in `govpay-common`, non duplicate/isolate qui — riusabili
da chiunque debba solo costruire/validare un formato IUV o sostituire
segnaposto in un testo, senza tirarsi dietro questa libreria.

**Verificato package per package** quali funzioni qualificano:

- **`CostruttoreIdentificativiPagamento`** (algoritmo aux digit/check digit
  mod-93/costruzione-decodifica IUV-NAV) → **spostato in
  `it.govpay.common.utils.IuvUtils`** (`genera`/`convertiDaNumeroAvviso`,
  nuovo record annidato `IuvUtils.IdentificativiPagamento`). Non è
  "processing generico basato su parametri": il "93", l'ordine di
  concatenazione e i vincoli di lunghezza per AuxDigit sono la formula
  pagoPA, non riusabili per altro — ma è comunque IUV, e `govpay-common` ne
  possiede già il concetto (`IuvUtils.isIuvInterno`/`isNumeric`): consolidare
  in un solo posto invece di spezzare la stessa competenza su due librerie.
  L'eccezione di formato non valido diventa `IllegalArgumentException`
  (prima `ValidazioneNonSuperataException`, specifica di questa libreria):
  `govpay-common` non ha un'eccezione di validazione propria, e
  `IllegalArgumentException` è idiomatica per una utility di basso livello.
- **`RisolutorePrefissoIuv`** → **spostato in
  `it.govpay.common.utils.RisolutoreSegnaposto`, anonimizzato**: il suo
  `risolvi(String, Map<String,String>)` è sostituzione di segnaposto
  `%(chiave)` del tutto generica (pattern e mappa sono entrambi parametri
  del chiamante), senza alcuna conoscenza di IUV — il nome "PrefissoIuv"
  descriveva solo chi la chiamava, non cosa faceva. Rinominata per non
  portare un nome fuorviante in una libreria dove non ha piu' alcun legame
  con IUV. Stessa eccezione (`IllegalArgumentException`) per un placeholder
  non risolvibile.

**Cosa resta in questa libreria** (richiede DB/Spring, non movibile):
`GeneratoreIuvStandard`, `GeneratoreProgressivoIuv`,
`AllocatoreBloccoProgressivoIuv`, `ProgressivoIuv`/`ProgressivoIuvId`/
`ProgressivoIuvRepository`. Anche la SPI `GeneratoreIuv` resta qui: lega
generazione a progressivo/DB e al parametro `codificaIuvTipoPendenza`, non è
un'utility pura.

`GeneratoreIuvStandard.genera`/`convertiDaNumeroAvviso` ora delegano a
`IuvUtils`, adattando il record di ritorno (`IuvUtils.IdentificativiPagamento`
→ `it.govpay.pendenze.spi.IdentificativiPagamento`, la SPI resta con il
proprio tipo). `risolviPrefix` usa `RisolutoreSegnaposto` al posto della
classe locale ora rimossa.

**Coordinamento fra i due repository**: cambiamento in `govpay-common`
(stessa versione `2.0.4-SNAPSHOT`, nessun bump necessario — è già uno
snapshot in evoluzione), installato in locale (`mvn install -DskipTests`)
per verificare l'integrazione prima che il lead lo pubblichi davvero. 27
nuovi test in `govpay-common` (`IuvUtilsTest` +12, `RisolutoreSegnapostoTest`
nuovo, 4 — totale libreria 619 test verdi). In `govpay-common-pendenze`:
rimossi `CostruttoreIdentificativiPagamento`/`RisolutorePrefissoIuv` e i
rispettivi test (17 test), 79 totali (da 96).

## 16. Ricevute e rendicontazioni (2026-09-24)

**Perimetro confermato prima di implementare**: la decisione A1 del disegno
abbandonato (`proposta-libreria-pendenze.md`) — "RPT e pagamenti non nel
dettaglio: li richiede il consumatore" — e la conferma diretta del lead sul
vecchio "dettaglio pendenza": caricava tutto (ricevute, rendicontazioni,
proprietà) insieme al resto, producendo circa 200 query per una singola
lettura. In v3 questo non deve ripetersi: `Ricevuta`/`Rendicontazione`/
`FlussoRendicontazione` restano **fuori dall'aggregato `PosizioneDebitoria`**
— `idPendenza` è una FK piatta (`Long`), non una relazione JPA; `Pendenza` non
ha una collezione `@OneToMany` verso queste entità. Sono risorse indipendenti,
interrogate solo su richiesta esplicita, tramite il nuovo
`RicevutaRendicontazioneService` (letture soltanto — nessuna scrittura: questa
libreria non acquisisce flussi pagoPA, lo farà un batch/consumer dedicato che
userà i repository direttamente, senza regole di validazione da rispettare,
il contenuto è prodotto integralmente da pagoPA).

**`FlussoRendicontazione` è un'entità separata**, non incorporata per riga in
`Rendicontazione` (a differenza di una prima ipotesi): un flusso raggruppa più
rendicontazioni, come nel legacy (`fr`/`rendicontazioni`, FK
`rendicontazioni.id_fr`); incorporarlo avrebbe duplicato inutilmente gli
stessi dati di testata. `Rendicontazione.flusso` è invece una vera relazione
JPA (`@ManyToOne`) — a differenza di `idPendenza` — perché
`Rendicontazione`/`FlussoRendicontazione` formano una loro piccola coppia di
aggregato, separata da `PosizioneDebitoria`.

**`Ricevuta.contenuto` conserva l'XML grezzo esattamente come nel legacy**,
non convertito in JSON. Prima ipotesi (poi corretta su indicazione esplicita
del lead): siccome lo YAML v3 espone `Ricevuta` in forma JSON, salvare già in
quella forma sembrava naturale. Sbagliato: l'istruzione "manteniamo il più
possibile i formati per semplificare la migrazione" riguardava lo *storage*,
non la forma esposta dall'API — con l'XML grezzo la migrazione dei dati
esistenti resta una copia diretta del blob (come `rpt.xml_rt BYTEA`), mentre
con JSON avrebbe richiesto scrivere e validare una vera conversione
XML→JSON in fase di migrazione. La conversione in JSON per l'endpoint di
lettura si sposta quindi a runtime, nel livello che espone
`GET .../ricevute`, fuori da questa libreria. Nessuna gerarchia di
entità/record tipizzati per le tre varianti (`ctRicevutaTelematica`/
`ctReceipt`/`ctReceiptV2`): questa libreria non valida il contenuto, lo
conserva e lo restituisce così com'è (a differenza di `DettaglioContabile`,
che ha vere regole di business da far rispettare).

**Tre bug trovati dal lead in revisione e corretti**:

- **Le revisioni dello stesso flusso non erano conservabili.** Verificato in
  `Rendicontazioni.java:718` (business layer legacy): una nuova acquisizione
  con lo stesso `cod_dominio`+`cod_flusso` non sovrascrive la riga esistente,
  ne inserisce una nuova con `revisione` incrementata, marcando obsoleta
  quella precedente (`fr.obsoleto`/`fr.revisione`, vincoli `unique_fr_1`
  `(cod_dominio,cod_flusso,data_ora_flusso)` / `unique_fr_2`
  `(cod_dominio,cod_flusso,cod_psp,revisione)`). Il vincolo iniziale su
  `FlussoRendicontazione`, univoco per sola coppia dominio+identificativo,
  impediva sia di importare tutte le revisioni storiche sia di acquisirne una
  nuova senza sovrascrivere la testata precedente. Corretto aggiungendo
  `revisione`/`obsoleto` all'entità e sostituendo il vincolo con i due
  equivalenti legacy; il metodo di lookup diventa
  `findByIdDominioAndIdFlussoAndObsoletoFalse` (equivalente al legacy
  `FrBD.getFr(dominio, flusso)`, che filtra implicitamente `obsoleto=false`).
- **`Rendicontazione.getFlusso()` lanciava `LazyInitializationException` fuori
  transazione.** Riprodotto dal lead. `flusso` è `@ManyToOne(fetch = LAZY)`;
  senza un caricamento esplicito, leggerlo dopo il ritorno dal servizio
  fallisce. I test iniziali non lo intercettavano perché la transazione di
  test restava aperta. Corretto con `@EntityGraph(attributePaths = "flusso")`
  su `RendicontazioneRepository.findByIdPendenza` (fetch join nella stessa
  query, non un eager permanente sull'entità). Verificato con
  `Hibernate.isInitialized(...)`, non con la sola lettura del campo (che il
  bug avrebbe potuto nascondere di nuovo dentro la transazione di test).
- **L'elenco delle ricevute caricava anche il contenuto XML di ognuna.** Lo
  YAML v3 espone nell'elenco solo `iur`/`tipo`/`data`; il dettaglio si
  recupera separatamente per singola ricevuta. `RicevutaRepository`
  restituiva entità complete. Corretto con una proiezione dedicata
  (`RicevutaElenco`, interfaccia con i tre soli accessori) e una query
  esplicita (`findElencoByIdPendenza`) che seleziona solo quelle colonne,
  evitando di trasferire e allocare gli XML non richiesti.

8 nuovi test (87 totali, da 79): `RicevutaRendicontazioneMappingTest` (4, di
cui una — `piuRevisioniDelloStessoFlussoCoesistono` — riproduce lo scenario
delle revisioni multiple), `RicevutaRendicontazioneServiceTest` (4, di cui
una — `cercaRendicontazioniInizializzaIlFlusso` — verifica il fetch join con
`Hibernate.isInitialized`).

## 17. Riuso diretto delle tabelle legacy, non uno schema v3 separato (2026-09-25)

**Decisione che sostituisce §§1-16**: invece delle tabelle nuove `posizioni_debitorie`/
`pendenze`/`voci_pendenza` (con `opzioni_pagamento`/`ricevute`/`rendicontazioni`/
`flussi_rendicontazione` come già descritto), l'aggregato centrale riusa
column-per-column le tabelle legacy reali:

- **`PosizioneDebitoria` → `documenti`**: 7 colonne additive (`id_unita_operativa`,
  `notifica_send`, `nav_notifica`, `data_ultima_modifica_aca`,
  `data_ultima_comunicazione_aca`, `data_creazione`, `data_ultimo_aggiornamento`).
- **`Pendenza` → `versamenti`** (~50 colonne mappate su ~65 reali): 3 colonne
  additive (`id_opzione_pagamento`, `numero_rata`, `data_caricamento`).
- **`VocePendenza` → `singoli_versamenti`**: 5 colonne additive (`tipo_riferimento`,
  `cod_entrata`, `iban_accredito_v3`, `iban_appoggio_v3`, `tassonomia_v3`); riuso
  as-is di `tipo_bollo`/`hash_documento`/`provincia_residenza` e di `contabilita`
  (nuovo formato JSON, non sovrapposto al vecchio `Contabilita`/`QuotaContabilita`).
- **`OpzionePagamento`/`SoggettoDebitore`**: uniche tabelle davvero nuove
  (`opzioni_pagamento`/`soggetti_debitori`) — la macchina a stati delle opzioni non
  esiste in v2 in nessuna forma; i debitori in v2 vivono solo denormalizzati su
  `versamenti.debitore_*`, un solo soggetto per versamento.

**Motivazione**: minimizzare la differenza strutturale da v2 per ridurre al minimo
la migrazione dati e i problemi di retrocompatibilità — v2 e v3 scrivono le stesse
tabelle fisiche, un cliente è sempre sull'una o sull'altra API, mai su entrambe
contemporaneamente sullo stesso record. Principio M4 esteso: nessun vincolo FK
reale verso l'anagrafica esterna di govpay-common (`domini`/`applicazioni`/
`tipi_versamento`/`tipi_vers_domini`), anche dove il legacy reale ce l'ha — coerenza
con la scelta, già presa, di non avere relazioni JPA verso quelle tabelle.

**Cinque micro-decisioni di mapping**, prese una alla volta:

1. **Due FK per il tipo pendenza** (`idTipoPendenza`/`idTipoVersamento`): il legacy
   ha due livelli (`tipi_versamento` catalogo astratto, `tipi_vers_domini`
   istanza/override per dominio) — `versamenti.id_tipo_versamento` è una
   denormalizzazione di comodo. Il chiamante fornisce entrambi gli ID (M4 puro,
   nessuna query di questa libreria verso l'anagrafica esterna).
2. **`numero_rata` (nuova colonna) invece di `cod_rata`** (deprecata per v3, resta
   `NULL` sulle righe v3): `cod_rata` mischiava tipologia e posizione nello stesso
   formato, ambiguo per un lettore legacy.
3. **`data_caricamento` (nuova colonna)**: "data di emissione della pendenza" dello
   YAML v3, concetto distinto da `data_creazione` (timestamp tecnico di scrittura),
   senza equivalente nel legacy.
4. **`src_iuv`/`src_debitore_identificativo`**: denormalizzazioni `UPPERCASE` per
   la ricerca case-insensitive (verificato in `proposta-libreria-pendenze.md` §4.5),
   non copie di audit. Il legacy ha un bug su un solo percorso di scrittura, non
   replicato.
5. **`StatoPendenza` allineato agli 8 valori di `StatoVersamento`** (non i 6 dello
   YAML v3 attuale — da correggere nello YAML): `Pendenza.stato` mappa direttamente
   `versamenti.stato_versamento`, un valore diverso da quelli legacy farebbe fallire
   `valueOf(...)` su qualunque riga letta da codice legacy.

## 18. Unicità IUV/NAV per dominio e bug della cascade mancante (2026-09-25)

**IUV/NAV non hanno un vincolo DB**: verificato che `versamenti` ha solo un indice
non univoco su `(iuv_versamento, id_dominio)`, mai `UNIQUE` — il legacy la
garantisce solo a livello applicativo (`Versamento.java:184-189`, `VER_025`:
"due pendenze non possono avere lo stesso numero avviso"). Replicato in
`PosizioneDebitoriaService.verificaNumeroAvvisoNonDuplicato`: interroga il DB solo
quando il chiamante fornisce `numeroAvviso` esplicitamente (IUV/NAV generati da
`GeneratoreIuv` sono collision-free per costruzione, nessun controllo necessario).

**Bug della cascade mancante**: `Pendenza.voci` (`@OneToMany(mappedBy = "pendenza")`)
era priva di `cascade = CascadeType.ALL` — sintomo fuorviante: dopo `em.clear()` +
`em.find()`, collezioni `@OneToMany` *diverse e non correlate*
(`PosizioneDebitoria.soggettiDebitori`) risultavano vuote alla rilettura, non
`Pendenza.voci` stessa. Diverse ipotesi sbagliate esplorate (fetch eager,
`@OrderBy`, timing delle transazioni) prima di trovare la causa reale. Verificato
che tutte e 4 le `@OneToMany` della libreria abbiano `cascade = CascadeType.ALL`.

## 19. Fase 2: riuso di `rpt`/`pagamenti`/`fr`/`rendicontazioni` (2026-09-25/26)

Stesso principio di §17 esteso a `Ricevuta`/`Rendicontazione`/`FlussoRendicontazione`:

- **`Rpt` → `rpt`**: nessuna colonna aggiunta. **`iur` è fisicamente la colonna
  `ccp`** — "codice contesto pagamento", nomenclatura SANP storica dello stesso
  identificativo che le specifiche più recenti del Nodo chiamano `receiptId`,
  verificato nel business layer legacy (`RicevuteConverter.setIdRicevuta(rpt.getCcp())`,
  `QuietanzaPagamento.setCcp(pagamento.getIur())`, `CtReceiptUtils.setCcp(receiptId)`;
  il bean v2 `Ricevuta.idRicevuta` è documentato come "Corrisponde al `receiptId`
  oppure al `ccp`"). Quindi `(iuv, ccp, cod_dominio)` — il vincolo naturale reale —
  è esattamente `(iuv, iur, dominio)`: `Rpt` basta da sola per
  `GET .../ricevute/{iur}`, contraddicendo un'ipotesi precedente (poi abbandonata)
  che passava da `Pagamento.iur` pensando che `rpt` non avesse un IUR.
- **`Pagamento` → `pagamenti`**: mappata ma non agganciata alla feature ricevute
  (introdotta solo per l'ipotesi poi abbandonata) — resta disponibile per un uso
  futuro (es. una risorsa "riscossioni").
- **`FlussoRendicontazione` → `fr`**: porta sia `idDominio` (Long, M4) sia
  `codDominio` (String) — verificato che tutta la ricerca applicativa legacy su
  questo gruppo di tabelle (`FrBD`/`RptBD`/`PagamentiBD`) avviene sempre per
  *codice* del dominio, mai per id numerico (colonna presente sui bean ma mai usata
  come parametro di ricerca). `rpt`/`pagamenti` non hanno nemmeno una colonna
  `id_dominio`: `codDominio` era già l'unica opzione lì.
- **`Rendicontazione` → `rendicontazioni`**: **non ha `id_pendenza`** (ipotesi
  iniziale sbagliata) — il legacy correla solo per `iuv` (stringa). La ricerca
  ({@code cercaRendicontazioni}) filtra per `iuv` **e per `flusso.codDominio`**
  (bug trovato in revisione indipendente, §20): lo IUV è univoco solo per dominio,
  non globalmente.

`TipoRicevuta` (model) rimosso: `Rpt.versione` conserva il valore legacy grezzo di
`VersioneRPT`, la mappatura sui valori dello YAML v3 è responsabilità del livello
API, non più un campo tipizzato di questa libreria.

## 20. Cinque bug trovati in revisione indipendente (2026-09-26)

1. **Rendicontazioni cercate senza filtro dominio** — vedi §19, corretto con
   `RendicontazioneRepository.findByIuvAndFlusso_CodDominio`, risolvendo
   `codDominio` dall'`idDominio` della pendenza tramite `DominioRepository` di
   govpay-common.
2. **`srcDebitoreIdentificativo` mai inizializzato**: `NOT NULL` ma nessun codice lo
   valorizzava — `crea()` falliva con `DataIntegrityViolationException`. Corretto
   con lo stesso placeholder fisso di `debitoreIdentificativo` (§21).
3. **NAV duplicato accettato nello stesso aggregato in ingresso**: il controllo di
   `verificaNumeroAvvisoNonDuplicato` (§18) confronta solo contro il DB — due
   pendenze duplicate nella stessa richiesta lo superano entrambe (nessuna delle
   due è ancora persistita quando vengono verificate). Corretto con un controllo
   puramente in memoria in `ValidatorePosizioneDebitoria`, prima di qualunque
   accesso al DB.
4. **Elenco/dettaglio ricevute includevano RPT senza ricevuta acquisita**:
   `RptRepository` selezionava tutte le righe del versamento, incluse quelle con
   `xml_rt`/`data_msg_ricevuta` ancora `NULL` (richiesta di pagamento inviata, RT
   non ancora arrivata). Corretto filtrando `dataMsgRicevuta is not null`.
5. **`idPosizioneDebitoria` non univoco rispetto alla ricerca pubblica** — vedi §22.

## 21. Debitore denormalizzato su `versamenti`: placeholder fissi, non sincronizzati (2026-09-26)

**Decisione finale, dopo un'inversione**: `debitoreTipo`/`debitoreIdentificativo`/
`debitoreAnagrafica`/`srcDebitoreIdentificativo` (quest'ultimo `NOT NULL`,
`UPPERCASE(debitoreIdentificativo)`) sono valorizzati con **placeholder fissi ed
espliciti** (`"VEDERE_SOGGETTI_DEBITORI"`/`"Vedere tabella soggetti_debitori"`),
non sincronizzati col soggetto di ordine 0 di `soggettiDebitori`. `debitoreTipo`
(nullable) resta indefinito.

Percorso della decisione: inizialmente si era detto di sincronizzarli dal soggetto
di ordine 0 a ogni scrittura (fonte di verità v3 = `soggetti_debitori`). Poi si è
notato che `soggettiDebitori` resta modificabile dopo la creazione (PATCH, §5/
sviluppo successivo) e tenerli allineati nel tempo sarebbe complessità pura — da
qui i placeholder. Verificando poi se fosse sicuro farlo, è emerso che il motore
di pagamento **legacy** (attivazione RPT verso il Nodo,
`CtPaymentPABuilder.buildSoggettoPagatore`; stampa dell'avviso PDF,
`AvvisoPagamentoUtils.impostaAnagraficaDebitore`) legge queste colonne
direttamente, per qualunque pendenza indipendentemente da chi l'ha creata — non
un'opzione applicativa v2 come `id_documento` (§17, mai popolato: letto solo da
una funzionalità v2 opzionale che un cliente su v3 non esegue più). Un placeholder
lì produrrebbe un pagamento con il debitore sbagliato.

**Decisione finale**, comunque per i placeholder: quella pipeline legacy è essa
stessa parte di ciò che verrà sostituito/adattato a fine transizione v3 (quando
leggerà `soggetti_debitori` direttamente). Fino ad allora resta un gap noto e
accettato per l'attivazione di un pagamento reale su una pendenza v3 attraverso
l'endpoint non ancora adattato — non qualcosa che questa libreria deve compensare
fingendo un dato che potrebbe comunque disallinearsi.

## 22. Unicità pubblica di `idPosizioneDebitoria` (2026-09-26)

Il vincolo legacy reale su `documenti` è `(cod_documento, id_applicazione,
id_dominio)`, ma l'identità pubblica dello YAML v3 (`GET`/`POST`
`/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}`, 409 "Esiste già...") è
chiavata solo su `idA2A`+`idPosizioneDebitoria`, senza dominio. Due posizioni con
lo stesso identificativo su domini diversi passavano entrambe la creazione, ma la
lettura per identificativo (`Optional`) falliva con
`IncorrectResultSizeDataAccessException`.

Corretto su due livelli:

- **Vincolo DB nuovo**, in migrazione: `unique_documenti_applicazione
  (cod_documento, id_applicazione)`, senza `id_dominio` — il vincolo storico
  `unique_documenti_1` resta, ridondante ma innocuo.
- **`crea()`**: il controllo applicativo (`existsByIdApplicazioneAndIdPosizioneDebitoria`)
  resta per il fallimento rapido nel caso comune (non atomico, check-then-act); la
  garanzia reale contro le creazioni concorrenti è il catch della violazione del
  vincolo DB (`DataIntegrityViolationException` → `RisorsaGiaEsistenteException`,
  nuova eccezione).

Nessuna difesa aggiuntiva necessaria in lettura: eventuali duplicati legacy
preesistenti farebbero fallire l'`ALTER TABLE ADD CONSTRAINT` in fase di
migrazione stessa (script diagnostico incluso come commento), prima che qualunque
codice v3 possa girare su quel DB — e senza la migrazione applicata,
`ddl-auto=validate` fallirebbe comunque all'avvio per le colonne mancanti.

## 23. Script di migrazione SQL per un DB v2 esistente (2026-09-26)

`sql/migrazione/` nel repository (non parte della catena di patch versionate del
core GovPay, `src/main/resources/db/sql/<dialetto>/patch` — script a sé stanti,
solo PostgreSQL, da eseguire una sola volta in ordine di numerazione):

- `01_documenti.sql`, `04_versamenti.sql`, `05_singoli_versamenti.sql`: colonne
  additive di §17, con **sentinelle esplicite** per le righe v2 esistenti su
  colonne `NOT NULL` nuove (`1970-01-01` per le date, `-1` per `numero_rata`) —
  un valore ovviamente non plausibile come dato reale, mai un valore verosimile
  come `CURRENT_TIMESTAMP` che potrebbe passare per dato genuino. `01_documenti.sql`
  include anche il vincolo di unicità di §22.
- `02_opzioni_pagamento.sql`, `03_soggetti_debitori.sql`: le due tabelle nuove,
  nessuna riga esistente da migrare.
- `rpt`/`pagamenti`/`fr`/`rendicontazioni` non compaiono: nessuna modifica
  necessaria, tutto quello che serve a v3 esiste già in produzione (§19).

## 24. `PosizioneDebitoria.dataPubblicazione` ripristinato (2026-09-26)

Presente fin dal primissimo disegno (§3.1: `LocalDate`, nullable = "pubblicata
subito"), perso silenziosamente durante il pivot al riuso delle tabelle legacy
(§17) — nel ricostruire l'elenco colonne di `documenti` per quel pivot non è
stato confrontato sistematicamente con la vecchia tabella §3.1, e questo campo
(senza un aggancio ovvio a una colonna legacy) è rimasto fuori senza che ce ne
accorgessimo. Individuato lavorando su `govpay-pendenze-api`, quando
`NuovaPosizioneDebitoria` dello YAML si è rivelata averlo mentre l'entity no.

Colonna aggiunta (nona, non ottava — vedi §17): `documenti.data_pubblicazione`
(`DATE`, nullable, nessuna sentinella di migrazione necessaria: `NULL`
significa "pubblicata subito", esattamente il significato corretto anche per
le righe v2 esistenti).

**Logica applicativa implementata** ("la posizione si comporta come se non
esistesse per qualsiasi ricerca/pagamento" prima di quella data, semantica
dello YAML v3): `PosizioneDebitoriaRepository.findByIdApplicazioneAndIdPosizioneDebitoria`/
`findDistinctByIdApplicazioneAndSoggettiDebitori_Identificativo` e
`PendenzaRepository.findByIdApplicazioneAndNumeroAvviso`/
`findByIdApplicazioneAndNumeroAvvisoAndIdDominio` filtrano tutti su
`dataPubblicazione is null or dataPubblicazione <= :oggi` (`oggi` sempre dal
`Clock` della libreria, mai da una funzione DB). Per `Pendenza` il filtro passa
per un `left join` opzionale fino a `PosizioneDebitoria` (via
`opzionePagamento`, esso stesso nullable): una pendenza priva di
`opzionePagamento` — creata da v2, o derivante da migrazione, che non hanno mai
avuto il concetto di posizione/pubblicazione — resta sempre visibile,
indipendentemente da qualunque `dataPubblicazione`. Deliberatamente **non**
filtrati: `existsByIdApplicazioneAndIdPosizioneDebitoria` (il controllo duplicati
in scrittura deve valere comunque, non ha senso lasciar creare una seconda
posizione "perché la prima non è ancora pubblicata") e `trovaPerId` (lookup
tecnico interno, non una ricerca pubblica). La futura verifica pagamento resta
fuori da questo giro.

## Anagrafica `UnitaOperativa` (2026-09-26)

Emersa lavorando su `govpay-pendenze-api`: risolvere `idUnitaOperativa`
(codice, non id numerico — stessa convenzione di `idA2A`/`idDominio`,
verificata nel legacy: `PutUnitaOperativaDTO.idUo`) richiede un'anagrafica che
non esiste affatto in `govpay-common` (a differenza di `Applicazione`/
`Dominio`, già presenti). `UnitaOperativa` è comunque un concetto legacy reale
(tabella `uo`, non una tabella nuova — riuso diretto, stesso principio di
§17), solo mai portato su `govpay-common`.

Decisione del lead: nasce in `govpay-common-pendenze` (non in `govpay-common`,
condivisa con la 3.10.x, la cui strategia di release per l'aggiunta di novità
è da chiarire) — se `govpay-console-api` passerà alla 3.11.x dipendendo da
questa libreria (come previsto), questa resta la sua sede naturale, non un
ripiego da migrare poi. Mappata per intero (tutte le colonne reali di `uo`,
nessuna omissione), anche se per ora serve solo a risolvere l'FK in
scrittura — l'anagrafica in lettura (`GET /domini/{idDominio}/unita-operative/{idUnitaOperativa}`)
resta lavoro separato.

## Anagrafica `TipoVersamento`/`TipoVersamentoDominio` (2026-09-26)

Stesso gap di `UnitaOperativa`, emerso per la stessa ragione: `idTipoPendenza`
dello YAML v3 (`NuovaPendenza.idTipoPendenza`, es. `IMU`) è un codice
testuale — verificato nel legacy, `Versamento.SingoloVersamento.codDominio` è
lo stesso pattern per l'analogo campo dominio — che va risolto negli ID
numerici `Pendenza.idTipoVersamento` (catalogo astratto) e
`Pendenza.idTipoPendenza` (= `id_tipo_versamento_dominio`, l'istanza/override
per dominio). Nessuna anagrafica JPA esisteva per questo, né in
`govpay-common` né qui.

Risoluzione legacy verificata: `AnagraficaManager.getTipoVersamentoDominio(configWrapper,
idDominio, codTipoVersamento)` — `codTipoVersamento` univoco *globalmente* nel
catalogo (`unique_tipi_versamento_1`), l'override è univoco per
`(idDominio, idTipoVersamento)`; se non trovato, il percorso REST "custom"
(il più vicino concettualmente a questa v3) rifiuta esplicitamente, **nessun
fallback automatico** su un dominio di default (l'auto-censimento esiste solo
nel percorso v2 "flessibile", non riprodotto qui).

**Proiezione minimale, non piena fedeltà** (decisione del lead, 2026-09-26,
dopo aver verificato la DDL reale — la scelta iniziale, per analogia con
`UnitaOperativa`, era piena fedeltà, poi corretta): `tipi_versamento` ha ~55
colonne, quasi tutte configurazione di stampa/notifica del BackOffice legacy
(form BO/PagOffice, template email/AppIO di promemoria, tracciati CSV) che la
v3 non usa mai (ha il proprio `notificaSend`/`dataPubblicazione`). Mappate
solo `codTipoVersamento`/`descrizione`/`abilitato` su `TipoVersamento`, e la
sola relazione verso di esso (più `idDominio`, FK piatta M4) su
`TipoVersamentoDominio` — sufficienti a risolvere il codice negli ID
richiesti, senza portarsi dietro superficie JPA per campi che questo
microservizio non userà mai. `TipoVersamentoDominioRepository.findByCodTipoVersamentoAndIdDominio`
risolve entrambi gli ID in un'unica interrogazione.

## `VocePendenza.idDominio` ripristinato (2026-09-26)

Stessa causa di omissione di `PosizioneDebitoria.dataPubblicazione` (§24):
`singoli_versamenti.id_dominio` è una colonna legacy **reale** (FK verso
`domini`, nullable) — concetto pagoPA di multi-beneficiario, un avviso con
voci destinate a enti creditori diversi — mai mappata su `VocePendenza`.
Individuata lavorando sul converter di `govpay-pendenze-api`, quando le 3
varianti di `NuovaVocePendenza` dello YAML si sono rivelate avere tutte un
`idDominio` opzionale (stesso pattern/convenzione di `idDominio` di
posizione: codice testuale, non id numerico — confermato nel legacy,
`PendenzeConverter.java`: `sv.setCodDominio(vocePendenza.getIdDominio())`)
mentre l'entity non aveva alcuna colonna corrispondente.

A differenza di `dataPubblicazione`, qui `NULL` non basta a significare
"eredita dal padre": `PosizioneDebitoriaService.crea()` materializza sempre
esplicitamente `VocePendenza.idDominio` al valore della posizione se il
chiamante non indica un override — stesso principio già in uso per
`Pendenza.idDominio` (mai lasciato implicito), confermato anche dal
comportamento del legacy (`VersamentoUtils.toSingoloVersamentoModel`: se
`codDominio` è assente sulla voce, usa comunque quello del versamento, non lo
lascia indefinito).

## Stato dei test

105 test totali, tutti verdi (`mvn clean test`).
