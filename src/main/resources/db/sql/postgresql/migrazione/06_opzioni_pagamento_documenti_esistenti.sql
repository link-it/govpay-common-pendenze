-- ---------------------------------------------------------------------------
-- Sintesi dei debitori (debitori + pd_debitori) e delle OpzionePagamento per i
-- documenti esistenti, per i tre pattern classificati in
-- docs/proposta-modello-nativo-v3.md §28 (leggere quella sezione per il
-- ragionamento completo).
--
-- In v2 il debitore e' denormalizzato sul versamento (debitore_*): i versamenti
-- di un documento si raggruppano per debitore distinto (debitore_identificativo,
-- senza distinzione tra maiuscole e minuscole), e ogni gruppo e' classificato
-- e migrato per conto suo. Ogni gruppo migrato produce una riga debitori
-- (anagrafica copiata dal primo versamento del gruppo), un pd_debitori (ordine
-- = prima comparsa nel documento) e le sue opzioni di pagamento. Un documento
-- con debitori diversi tra i versamenti non e' piu' scartato: diventa una
-- posizione con piu' debitori.
--
--  A) tutti i versamenti del gruppo con cod_rata NULL -> una
--     OpzionePagamento SOLUZIONE_UNICA con tutte le pendenze dentro
--     (l'assunzione che siano componenti dello stesso dovuto, non
--     alternative, non e' dimostrata dal solo NULL: resta un'ipotesi di
--     migrazione, non un fatto verificato nei dati).
--  B) tutti i versamenti del gruppo con cod_rata numerico valido (intero
--     positivo), SENZA duplicati tra loro -> una OpzionePagamento PIANO_RATEALE.
--     numero_rata = valore dichiarato, preservato (1,3,5 resta 1,3,5, non
--     diventa 1,2,3): la regola "numeroRata sempre dalla posizione nelle
--     liste" (assegnaIndici) vale solo per le scritture dal vivo del
--     wrapper via crea()/aggiungiPendenza(), non per questo script che
--     scrive direttamente sulle tabelle.
--  C) versamenti con cod_rata riconosciuto come soglia (`ENTRO<gg>`,
--     `OLTRE<gg>`, `RIDOTTO`, `SCONTATO` — decodifica verificata su
--     VersamentoConverter.java:143-157), SENZA duplicati di valore nello
--     stesso gruppo -> una OpzionePagamento SOLUZIONE_UNICA_ENTRO/OLTRE/
--     RIDOTTO/SCONTATO PER VERSAMENTO (cardinalita' massima 1 pendenza per
--     queste tipologie, M2/TipologiaOpzionePagamento), quindi piu' opzioni
--     sotto lo stesso debitore quando coesistono piu' soglie (es. la coppia
--     ENTRO/OLTRE, o RIDOTTO+SCONTATO). RIDOTTO/SCONTATO restano distinti da
--     ENTRO/OLTRE: nessuna colonna porta il numero di giorni per questi due
--     codici (vietato in input, PendenzaVerificataValidator.java:182) perche'
--     e' un valore fisso per legge — usato qui come costante `giorni = 5`.
--     Lo stato di ciascuna opzione deriva dal suo
--     unico versamento (NON_ESEGUITO->DISPONIBILE, ANNULLATO->ANNULLATA,
--     altro->ATTIVATA): a differenza dei casi A/B non c'e' aggregazione su
--     piu' righe, quindi nessuno dei due documenti puo' incorrere nel gap
--     "mix NON_ESEGUITO/ANNULLATO" del caso successivo. Nessun controllo
--     incrociato tra le opzioni sorelle dello stesso debitore: e'
--     intenzionale che una coppia possa risultare entrambe ATTIVATA (decisione
--     del lead, 2026-10-22 — non dovrebbe succedere nella pratica, il DB non
--     ha un vincolo che lo impedisca, e il servizio v3 (PosizioneDebitoriaService
--     .attiva) evita nuove violazioni dal momento della migrazione in poi, anche
--     se non puo' correggere quelle gia' scritte qui).
--
-- QUALUNQUE altro pattern NON viene toccato da questo script (cod_rata
-- anomali/duplicati/misti tra i tre pattern sopra): quei gruppi (documento +
-- debitore) restano senza debitore e senza opzione, e i loro versamenti con
-- id_opzione_pagamento NULL. La query diagnostica in fondo a questo file li elenca per
-- un report separato, da rivedere caso per caso prima di decidere una
-- correzione dati o un'estensione del modello v3.
--
-- Atomico: lo script gira in un'unica transazione (BEGIN ... COMMIT prima del
-- report): se un passo fallisce, nulla di quanto fatto resta scritto, e non
-- possono esistere gruppi migrati a meta'. Eseguirlo con ON_ERROR_STOP
-- (psql -v ON_ERROR_STOP=1 -f ...) per fermarsi al primo errore; il rollback
-- lascia comunque il database com'era. Il costo e' un lock sulle righe di
-- versamenti toccate per tutta la durata dello script.
--
-- Idempotente: ogni passo considera solo versamenti
-- con id_opzione_pagamento ancora NULL — sicuro rieseguire l'intero file
-- dopo aver risolto manualmente altri documenti (es. dopo aver corretto un
-- dato anomalo segnalato dal report), ed e' indifferente l'ordine A/B
-- rispetto a C (nessuno dei due pattern puo' matchare i versamenti dell'altro).
--
-- Dialetto: PostgreSQL soltanto. A differenza di 05_documenti_orfani.sql,
-- qui non e' stata ancora scritta/verificata una traduzione per gli altri 4
-- dialetti: la classificazione usa espressioni regolari e funzioni finestra
-- la cui sintassi varia sensibilmente (es. FILTER non esiste su MySQL/
-- SQL Server, servirebbe riscriverla con CASE WHEN dentro COUNT) — da
-- valutare se e quando servira' davvero su quei motori.
-- ---------------------------------------------------------------------------

-- Gruppi (documento, debitore) da migrare, con la loro classificazione. Tabella
-- temporanea: serve a piu' passi, e porta gli id di debitori/pd_debitori/opzioni
-- preassegnati dalle sequenze, cosi' ogni passo li riusa senza ricostruire il
-- legame. Sparisce da sola a fine sessione; il DROP finale la toglie comunque.
BEGIN;

DROP TABLE IF EXISTS tmp_gruppi_debitore;
CREATE TEMPORARY TABLE tmp_gruppi_debitore AS
WITH versamenti_candidati AS (
    SELECT v.id, v.id_documento, upper(v.debitore_identificativo) AS chiave,
           v.cod_rata, v.stato_versamento
    FROM versamenti v
    WHERE v.id_documento IS NOT NULL
      AND v.id_opzione_pagamento IS NULL
),
classificazione AS (
    SELECT
        id_documento,
        chiave,
        MIN(id) AS primo_versamento,
        COUNT(*) AS totale,
        COUNT(*) FILTER (WHERE cod_rata IS NULL) AS n_null,
        COUNT(*) FILTER (WHERE cod_rata ~ '^[1-9][0-9]*$') AS n_numerico,
        COUNT(DISTINCT CASE WHEN cod_rata ~ '^[1-9][0-9]*$' THEN cod_rata::int END) AS n_numerico_distinti,
        COUNT(*) FILTER (WHERE cod_rata ~ '^(ENTRO[0-9]+|OLTRE[0-9]+|RIDOTTO|SCONTATO)$') AS n_soglia,
        COUNT(DISTINCT CASE WHEN cod_rata ~ '^(ENTRO[0-9]+|OLTRE[0-9]+|RIDOTTO|SCONTATO)$' THEN cod_rata END) AS n_soglia_distinti,
        COUNT(*) FILTER (WHERE stato_versamento = 'ANNULLATO') AS n_annullato,
        COUNT(*) FILTER (WHERE stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO')) AS n_altro
    FROM versamenti_candidati
    GROUP BY id_documento, chiave
)
SELECT
    c.*,
    CASE
        WHEN n_null = totale THEN 'SOLUZIONE_UNICA'
        WHEN n_numerico = totale AND n_numerico_distinti = totale THEN 'PIANO_RATEALE'
        ELSE NULL
    END AS tipologia,
    (n_soglia = totale AND n_soglia_distinti = totale) AS e_soglia,
    -- DISPONIBILE e' il caso di default (ValidatorePosizioneDebitoria.validaStati,
    -- corretto 2026-10-22): copre sia "tutte NON_ESEGUITO" sia il mix
    -- NON_ESEGUITO/ANNULLATO senza alcuna pendenza eseguita (annullamento
    -- indipendente della singola pendenza, possibile solo in v2 — l'opzione non e'
    -- mai stata "esercitata" in nessuno dei due casi, nessun pagamento registrato).
    CASE
        WHEN n_annullato = totale THEN 'ANNULLATA'
        WHEN n_altro > 0 THEN 'ATTIVATA'
        ELSE 'DISPONIBILE'
    END AS stato,
    -- Ordine dei debitori nel documento: prima comparsa tra i gruppi migrati ora,
    -- dopo quelli gia' migrati (un gruppo corretto a mano e migrato con una
    -- riesecuzione non riparte da 0 e non duplica l'ordine di un altro debitore).
    COALESCE((SELECT MAX(pd.posizione) + 1 FROM pd_debitori pd WHERE pd.id_documento = c.id_documento), 0)
      + DENSE_RANK() OVER (PARTITION BY c.id_documento ORDER BY c.primo_versamento) - 1 AS posizione,
    nextval('seq_debitori') AS id_debitore,
    nextval('seq_pd_debitori') AS id_pd_debitore,
    nextval('seq_opzioni_pagamento') AS id_opzione
FROM classificazione c
WHERE (n_null = totale)
   OR (n_numerico = totale AND n_numerico_distinti = totale)
   OR (n_soglia = totale AND n_soglia_distinti = totale);

-- Un gruppo gia' presente come pd_debitori dello stesso documento (stesso
-- identificativo) non si duplica: protegge da una riesecuzione dopo una
-- correzione manuale parziale.
DELETE FROM tmp_gruppi_debitore g
WHERE EXISTS (
    SELECT 1 FROM pd_debitori pd JOIN debitori d ON d.id = pd.id_debitore
    WHERE pd.id_documento = g.id_documento AND upper(d.identificativo) = g.chiave);

-- Anagrafica del debitore: copiata dal primo versamento del gruppo.
INSERT INTO debitori
    (id, tipo, identificativo, anagrafica, indirizzo, civico, cap, localita,
     provincia, nazione, email, cellulare)
SELECT
    -- debitore_tipo e' nullable in v2, tipo no: i dati senza tipo diventano 'F'.
    g.id_debitore, COALESCE(v.debitore_tipo, 'F'), v.debitore_identificativo, v.debitore_anagrafica,
    v.debitore_indirizzo, v.debitore_civico, v.debitore_cap, v.debitore_localita,
    v.debitore_provincia, v.debitore_nazione, v.debitore_email, v.debitore_cellulare
FROM tmp_gruppi_debitore g
JOIN versamenti v ON v.id = g.primo_versamento;

INSERT INTO pd_debitori (id, id_documento, id_debitore, posizione, notifica_send_payed)
SELECT g.id_pd_debitore, g.id_documento, g.id_debitore, g.posizione, false
FROM tmp_gruppi_debitore g;

-- Pattern A/B: una opzione per gruppo, con tutti i suoi versamenti dentro.
INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_pd_debitore, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT g.id_opzione, gen_random_uuid(), g.id_pd_debitore, g.tipologia, g.stato, 0, now(), now()
FROM tmp_gruppi_debitore g
WHERE g.tipologia IS NOT NULL;

UPDATE versamenti v
SET id_opzione_pagamento = g.id_opzione,
    numero_rata = CASE
        WHEN v.cod_rata ~ '^[1-9][0-9]*$' THEN v.cod_rata::int
        -- Caso A (SOLUZIONE_UNICA, cod_rata NULL): nessun valore dichiarato da
        -- preservare, numerate per id (ordine di inserimento) solo per garantire
        -- indici 1..N distinti dentro l'opzione (vincolo di unicita' su numero_rata).
        ELSE n.progressivo
    END
FROM tmp_gruppi_debitore g,
     (SELECT c.id, ROW_NUMBER() OVER (PARTITION BY c.id_documento, upper(c.debitore_identificativo)
                                      ORDER BY c.id) AS progressivo
      FROM versamenti c
      WHERE c.id_documento IS NOT NULL AND c.id_opzione_pagamento IS NULL) n
WHERE g.tipologia IS NOT NULL
  AND v.id_documento = g.id_documento
  AND upper(v.debitore_identificativo) = g.chiave
  AND v.id_opzione_pagamento IS NULL
  AND n.id = v.id;

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO
-- (vedi commento in testa al file). Una OpzionePagamento per versamento,
-- mai un'aggregazione: cardinalita' massima 1 per queste due tipologie.
-- Le opzioni del gruppo condividono il suo pd_debitori.
-- ---------------------------------------------------------------------------

WITH versamenti_soglia AS (
    SELECT
        vc.id,
        g.id_pd_debitore,
        CASE
            WHEN vc.cod_rata ~ '^ENTRO[0-9]+$'  THEN 'SOLUZIONE_UNICA_ENTRO'
            WHEN vc.cod_rata ~ '^OLTRE[0-9]+$'  THEN 'SOLUZIONE_UNICA_OLTRE'
            WHEN vc.cod_rata = 'SCONTATO'       THEN 'SOLUZIONE_UNICA_SCONTATO'
            WHEN vc.cod_rata = 'RIDOTTO'        THEN 'SOLUZIONE_UNICA_RIDOTTO'
        END AS tipologia,
        CASE
            WHEN vc.cod_rata ~ '^(ENTRO|OLTRE)[0-9]+$' THEN substring(vc.cod_rata FROM '[0-9]+$')::int
            ELSE NULL -- RIDOTTO/SCONTATO: 'giorni' non e' un valore ammesso per queste tipologie
        END AS giorni,
        CASE
            WHEN vc.stato_versamento = 'NON_ESEGUITO' THEN 'DISPONIBILE'
            WHEN vc.stato_versamento = 'ANNULLATO' THEN 'ANNULLATA'
            ELSE 'ATTIVATA'
        END AS stato,
        nextval('seq_opzioni_pagamento') AS nuovo_id,
        gen_random_uuid() AS nuovo_id_opzione_pagamento
    FROM versamenti vc
    JOIN tmp_gruppi_debitore g
      ON g.id_documento = vc.id_documento AND g.chiave = upper(vc.debitore_identificativo)
    WHERE g.tipologia IS NULL AND g.e_soglia
      AND vc.id_opzione_pagamento IS NULL
),
nuove_opzioni_soglia AS (
    INSERT INTO opzioni_pagamento
        (id, id_opzione_pagamento, id_pd_debitore, tipologia, giorni, stato, versione,
         data_creazione, data_ultimo_aggiornamento)
    SELECT nuovo_id, nuovo_id_opzione_pagamento, id_pd_debitore, tipologia, giorni, stato, 0, now(), now()
    FROM versamenti_soglia
    RETURNING id
)
-- Il join con nuove_opzioni_soglia non e' solo logico: un CTE data-modifying
-- in PostgreSQL esegue solo se e' effettivamente referenziato dalla query
-- finale — senza questo join l'INSERT sopra non verrebbe mai eseguito.
UPDATE versamenti v
SET id_opzione_pagamento = vs.nuovo_id,
    numero_rata = 1
FROM versamenti_soglia vs
JOIN nuove_opzioni_soglia nos ON nos.id = vs.nuovo_id
WHERE v.id = vs.id;

-- ---------------------------------------------------------------------------
-- Notifica SEND per debitore: un debitore con almeno un versamento v2 con
-- send_abilitato porta notifica_send e il NAV di notifica. Il NAV e' quello della
-- pendenza di numero_rata piu' basso (soluzione unica, rata del piano rateale) e,
-- per i gruppi con sole soglie, del primo versamento con send_abilitato. Un NAV
-- assente resta NULL ed e' elencato dal report diagnostico finale.
-- ---------------------------------------------------------------------------

UPDATE pd_debitori p SET notifica_send = true
WHERE p.id IN (SELECT g.id_pd_debitore FROM tmp_gruppi_debitore g)
  AND EXISTS (SELECT 1 FROM opzioni_pagamento o JOIN versamenti v ON v.id_opzione_pagamento = o.id
              WHERE o.id_pd_debitore = p.id AND v.send_abilitato);

UPDATE pd_debitori p SET notifica_send_nav = (SELECT v.numero_avviso
    FROM opzioni_pagamento o JOIN versamenti v ON v.id_opzione_pagamento = o.id
    WHERE o.id_pd_debitore = p.id
      AND v.numero_rata = (SELECT MIN(v2.numero_rata)
                           FROM opzioni_pagamento o2 JOIN versamenti v2 ON v2.id_opzione_pagamento = o2.id
                           WHERE o2.id_pd_debitore = p.id))
WHERE p.notifica_send = true
  AND p.id IN (SELECT g.id_pd_debitore FROM tmp_gruppi_debitore g WHERE g.tipologia IS NOT NULL);

UPDATE pd_debitori p SET notifica_send_nav = (SELECT v.numero_avviso
    FROM opzioni_pagamento o JOIN versamenti v ON v.id_opzione_pagamento = o.id
    WHERE o.id_pd_debitore = p.id
      AND v.id = (SELECT MIN(v2.id)
                  FROM opzioni_pagamento o2 JOIN versamenti v2 ON v2.id_opzione_pagamento = o2.id
                  WHERE o2.id_pd_debitore = p.id AND v2.send_abilitato))
WHERE p.notifica_send = true
  AND p.id IN (SELECT g.id_pd_debitore FROM tmp_gruppi_debitore g WHERE g.tipologia IS NULL);

DROP TABLE tmp_gruppi_debitore;

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione (di qualunque suo debitore) di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
-- stessa semantica di PosizioneDebitoriaService.attiva() (righe 1025-1036:
-- quando un'opzione si attiva, annulla automaticamente le DISPONIBILI della
-- stessa posizione e, tramite cascataStatoPendenze, le pendenze sottostanti),
-- applicata qui a ritroso sui dati storici (decisione del lead, 2026-10-22).
-- Diverso dal punto precedente (piu' opzioni ATTIVATA sullo stesso documento,
-- tollerato e non corretto): qui il caso e' l'opposto e univoco — un'opzione
-- ATTIVATA accanto a una o piu' DISPONIBILI ha un esito chiaro, nessuna
-- scelta arbitraria da fare.
--
-- Cascata anche sulle pendenze sottostanti (DISPONIBILE implica che nessuna
-- e' mai stata eseguita, anche se una puo' essere ANNULLATO per annullamento
-- indipendente v2 — vedi il caso del mix NON_ESEGUITO/ANNULLATO sopra): senza
-- questa cascata, le pendenze ancora NON_ESEGUITO di un'opzione cosi'
-- annullata resterebbero incongruenti con l'invariante "ANNULLATA -> tutte
-- le pendenze ANNULLATO" dello stesso modello (vedi §28).
--
-- Scoped su TUTTA la tabella opzioni_pagamento (non solo le righe appena
-- inserite sopra): e' una regola di consistenza generale del modello, non
-- specifica di questa esecuzione — idempotente di conseguenza (una seconda
-- esecuzione non trova piu' nessuna DISPONIBILE accanto a un'ATTIVATA).
-- ---------------------------------------------------------------------------

WITH opzioni_da_annullare AS (
    SELECT op.id
    FROM opzioni_pagamento op
    JOIN pd_debitori pd ON pd.id = op.id_pd_debitore
    WHERE op.stato = 'DISPONIBILE'
      AND EXISTS (
          SELECT 1 FROM opzioni_pagamento altra
          JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
          WHERE pd_altra.id_documento = pd.id_documento
            AND altra.stato = 'ATTIVATA'
      )
),
aggiornamento_opzioni AS (
    UPDATE opzioni_pagamento
    SET stato = 'ANNULLATA',
        data_ultimo_aggiornamento = now()
    WHERE id IN (SELECT id FROM opzioni_da_annullare)
    RETURNING id
)
UPDATE versamenti v
SET stato_versamento = 'ANNULLATO',
    data_ora_ultimo_aggiornamento = now()
FROM aggiornamento_opzioni ao
WHERE v.id_opzione_pagamento = ao.id;

COMMIT;

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi dalla sintesi automatica sopra —
-- da eseguire ed esaminare DOPO lo script, non parte dell'automazione.
--
-- Non c'e' piu' un report sul mix NON_ESEGUITO/ANNULLATO (gap di
-- ValidatorePosizioneDebitoria.validaStati, chiuso in libreria il
-- 2026-10-22): quei documenti sono ora migrati normalmente come DISPONIBILE
-- dal blocco sopra, non piu' esclusi.
-- ---------------------------------------------------------------------------

-- Gruppi (documento + debitore) con cod_rata genuinamente anomalo: non tutti
-- NULL, non tutti numerici validi senza duplicati, e non tutti valori-soglia
-- riconosciuti senza duplicati (RIDOTTO/SCONTATO/ENTRO/OLTRE) — pattern misto
-- tra le tre famiglie, o duplicato di valore-soglia nello stesso gruppo (es.
-- due "ENTRO5"), o dato corrotto. Da rivedere a mano.
WITH versamenti_candidati AS (
    SELECT v.id, v.id_documento, upper(v.debitore_identificativo) AS chiave, v.cod_rata
    FROM versamenti v
    WHERE v.id_documento IS NOT NULL AND v.id_opzione_pagamento IS NULL
),
classificazione AS (
    SELECT
        id_documento,
        chiave,
        COUNT(*) AS totale,
        COUNT(*) FILTER (WHERE cod_rata IS NULL) AS n_null,
        COUNT(*) FILTER (WHERE cod_rata ~ '^[1-9][0-9]*$') AS n_numerico,
        COUNT(DISTINCT CASE WHEN cod_rata ~ '^[1-9][0-9]*$' THEN cod_rata::int END) AS n_numerico_distinti,
        COUNT(*) FILTER (WHERE cod_rata ~ '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$') AS n_soglia,
        COUNT(DISTINCT CASE WHEN cod_rata ~ '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$' THEN cod_rata END) AS n_soglia_distinti
    FROM versamenti_candidati
    GROUP BY id_documento, chiave
)
SELECT id_documento, chiave AS debitore_identificativo, totale, n_null, n_numerico,
       n_numerico_distinti, n_soglia, n_soglia_distinti
FROM classificazione
WHERE NOT (n_null = totale)
  AND NOT (n_numerico = totale AND n_numerico_distinti = totale)
  AND NOT (n_soglia = totale AND n_soglia_distinti = totale);

-- Debitori migrati senza alcuna opzione di pagamento: non dovrebbero esistere
-- (la transazione lo impedisce); se compaiono, derivano da dati scritti fuori
-- da questo script e vanno completati a mano.
SELECT pd.id AS id_pd_debitori, pd.id_documento, d.identificativo
FROM pd_debitori pd
JOIN debitori d ON d.id = pd.id_debitore
WHERE NOT EXISTS (SELECT 1 FROM opzioni_pagamento op WHERE op.id_pd_debitore = pd.id);

-- Debitori con notifica SEND prevista ma senza NAV di notifica (versamento v2 con
-- send_abilitato e numero_avviso assente): SEND non verra' interrogato per loro.
SELECT pd.id AS id_pd_debitori, pd.id_documento, d.identificativo
FROM pd_debitori pd
JOIN debitori d ON d.id = pd.id_debitore
WHERE pd.notifica_send AND pd.notifica_send_nav IS NULL;
