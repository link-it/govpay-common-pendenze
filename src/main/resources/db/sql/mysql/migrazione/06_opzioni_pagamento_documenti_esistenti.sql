-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo: raggruppamento dei
-- versamenti per debitore, tre pattern A/B/C, cascata di mutua esclusione) —
-- qui la sintassi MySQL, compatibile anche con MariaDB. Richiede MySQL
-- 8.0.16+/MariaDB 10.2+ (funzioni finestra, REGEXP, DELETE con alias).
-- NON eseguito contro un'istanza reale: da riverificare prima dell'uso.
--
-- Atomico: tutto lo script gira in un'unica transazione (START TRANSACTION ...
-- COMMIT prima del report; CREATE/DROP TEMPORARY TABLE non fanno commit
-- implicito). Se un passo fallisce, eseguire ROLLBACK: non restano gruppi
-- migrati a meta'. Richiede tabelle InnoDB.
--
-- MySQL non supporta CTE scrivibili: la classificazione viene materializzata
-- in tabelle temporanee di sessione (si eliminano da sole a fine sessione,
-- comunque droppate esplicitamente in fondo).
--
-- Gli id di debitori e pd_debitori sono assegnati a mano (MAX(id) + numero di
-- riga): una colonna AUTO_INCREMENT accetta un valore esplicito e riallinea da
-- sola il proprio contatore. Lo script va eseguito a sistema fermo.
--
-- id_opzione_pagamento (BINARY(16)) e' generato con UNHEX(REPLACE(UUID(),
-- '-', '')), non UUID_TO_BIN(UUID()): quest'ultima esiste solo su MySQL 8,
-- non su MariaDB. id e' AUTO_INCREMENT (issue #10): non piu' pre-calcolabile
-- come con la sequenza emulata, quindi viene materializzato prima
-- l'id_opzione_pagamento (UUID, univoco per vincolo) in ciascuna tabella
-- temporanea, poi usato per ritrovare l'id fisico generato dall'insert e
-- collegarlo al versamento corrispondente.
-- ---------------------------------------------------------------------------

-- Pulizia iniziale delle sole tabelle di appoggio di questo script: se una sessione
-- prosegue dopo un errore, il DROP finale non e' stato eseguito.
DROP TEMPORARY TABLE IF EXISTS tmp_vrs_rata;
DROP TEMPORARY TABLE IF EXISTS tmp_gruppi;
DROP TEMPORARY TABLE IF EXISTS tmp_vrs_soglia;
DROP TEMPORARY TABLE IF EXISTS tmp_nuove_opzioni_ab;
DROP TEMPORARY TABLE IF EXISTS tmp_nuove_opzioni_c;

START TRANSACTION;

CREATE TEMPORARY TABLE tmp_vrs_rata AS
SELECT
    v.id,
    v.id_documento,
    UPPER(v.debitore_identificativo) AS chiave,
    v.cod_rata,
    v.stato_versamento,
    CASE WHEN v.cod_rata IS NULL THEN 1 ELSE 0 END AS flag_null,
    CASE WHEN v.cod_rata REGEXP '^[1-9][0-9]*$' THEN 1 ELSE 0 END AS flag_numerico,
    CASE WHEN v.cod_rata REGEXP '^[1-9][0-9]*$' THEN CAST(v.cod_rata AS UNSIGNED) END AS valore_numerico,
    CASE WHEN v.cod_rata REGEXP '^(ENTRO[0-9]+|OLTRE[0-9]+|RIDOTTO|SCONTATO)$' THEN 1 ELSE 0 END AS flag_soglia
FROM versamenti v
WHERE v.id_documento IS NOT NULL
  AND v.id_opzione_pagamento IS NULL;

-- ---------------------------------------------------------------------------
-- Gruppi (documento, debitore) da migrare, con la loro classificazione.
-- Pattern A/B: un'opzione per gruppo (SOLUZIONE_UNICA se tutti i cod_rata
-- sono NULL, PIANO_RATEALE se tutti numerici distinti). Stato: DISPONIBILE e'
-- il caso di default — copre sia "tutte NON_ESEGUITO" sia il mix
-- NON_ESEGUITO/ANNULLATO senza alcuna pendenza eseguita (annullamento
-- indipendente della singola pendenza, possibile solo in v2).
-- I gruppi non classificabili non entrano: restano nel report in fondo.
-- ---------------------------------------------------------------------------

SET @base_debitori := (SELECT COALESCE(MAX(id), 0) FROM debitori);
SET @base_pd_debitori := (SELECT COALESCE(MAX(id), 0) FROM pd_debitori);

CREATE TEMPORARY TABLE tmp_gruppi AS
SELECT
    x.*,
    -- Dopo i debitori gia' migrati del documento: una riesecuzione non duplica l'ordine.
    COALESCE((SELECT MAX(pd.posizione) + 1 FROM pd_debitori pd WHERE pd.id_documento = x.id_documento), 0)
      + DENSE_RANK() OVER (PARTITION BY x.id_documento ORDER BY x.primo_versamento) - 1 AS posizione,
    @base_debitori + ROW_NUMBER() OVER (ORDER BY x.id_documento, x.chiave) AS id_debitore,
    @base_pd_debitori + ROW_NUMBER() OVER (ORDER BY x.id_documento, x.chiave) AS id_pd_debitore
FROM (
    SELECT
        aggr.*,
        CASE WHEN n_null = totale THEN 'SOLUZIONE_UNICA'
             WHEN n_numerico = totale AND n_numerico_distinti = totale THEN 'PIANO_RATEALE'
        END AS tipologia,
        CASE WHEN n_soglia = totale AND n_soglia_distinti = totale THEN 1 ELSE 0 END AS e_soglia,
        CASE WHEN n_annullato = totale THEN 'ANNULLATA'
             WHEN n_altro > 0 THEN 'ATTIVATA'
             ELSE 'DISPONIBILE'
        END AS stato
    FROM (
        SELECT
            id_documento,
            chiave,
            MIN(id) AS primo_versamento,
            COUNT(*) AS totale,
            SUM(flag_null) AS n_null,
            SUM(flag_numerico) AS n_numerico,
            COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) AS n_numerico_distinti,
            SUM(flag_soglia) AS n_soglia,
            COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti,
            SUM(CASE WHEN stato_versamento = 'ANNULLATO' THEN 1 ELSE 0 END) AS n_annullato,
            SUM(CASE WHEN stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO') THEN 1 ELSE 0 END) AS n_altro
        FROM tmp_vrs_rata
        GROUP BY id_documento, chiave
    ) aggr
) x
WHERE x.tipologia IS NOT NULL OR x.e_soglia = 1;

-- Un gruppo gia' presente come pd_debitori dello stesso documento (stesso
-- identificativo) non si duplica: protegge da una riesecuzione dopo una
-- correzione manuale parziale.
DELETE g FROM tmp_gruppi g
 WHERE EXISTS (SELECT 1 FROM pd_debitori pd JOIN debitori d ON d.id = pd.id_debitore
                WHERE pd.id_documento = g.id_documento AND UPPER(d.identificativo) = g.chiave);

-- Anagrafica del debitore: copiata dal primo versamento del gruppo.
-- debitore_tipo e' nullable in v2, tipo no: i dati senza tipo diventano 'F'.
INSERT INTO debitori
    (id, tipo, identificativo, anagrafica, indirizzo, civico, cap, localita,
     provincia, nazione, email, cellulare)
SELECT
    g.id_debitore, COALESCE(v.debitore_tipo, 'F'), v.debitore_identificativo, v.debitore_anagrafica,
    v.debitore_indirizzo, v.debitore_civico, v.debitore_cap, v.debitore_localita,
    v.debitore_provincia, v.debitore_nazione, v.debitore_email, v.debitore_cellulare
FROM tmp_gruppi g
JOIN versamenti v ON v.id = g.primo_versamento;

INSERT INTO pd_debitori (id, id_documento, id_debitore, posizione, notifica_send_payed)
SELECT g.id_pd_debitore, g.id_documento, g.id_debitore, g.posizione, 0
FROM tmp_gruppi g;

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO. Una
-- opzione PER VERSAMENTO (cardinalita' massima 1 per queste tipologie), non
-- un'aggregazione per documento come A/B — solo se TUTTO il documento e'
-- fatto di valori-soglia riconosciuti e senza duplicati.
-- ---------------------------------------------------------------------------

CREATE TEMPORARY TABLE tmp_vrs_soglia AS
SELECT
    vc.id,
    vc.id_documento,
    g.id_pd_debitore,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%'  THEN 'SOLUZIONE_UNICA_ENTRO'
         WHEN vc.cod_rata LIKE 'OLTRE%'  THEN 'SOLUZIONE_UNICA_OLTRE'
         WHEN vc.cod_rata = 'SCONTATO'   THEN 'SOLUZIONE_UNICA_SCONTATO'
         ELSE 'SOLUZIONE_UNICA_RIDOTTO'
    END AS tipologia,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' OR vc.cod_rata LIKE 'OLTRE%'
         THEN CAST(SUBSTRING(vc.cod_rata, 6) AS UNSIGNED)
    END AS giorni,
    CASE WHEN vc.stato_versamento = 'NON_ESEGUITO' THEN 'DISPONIBILE'
         WHEN vc.stato_versamento = 'ANNULLATO' THEN 'ANNULLATA'
         ELSE 'ATTIVATA'
    END AS stato
FROM tmp_vrs_rata vc
JOIN tmp_gruppi g ON g.id_documento = vc.id_documento AND g.chiave = vc.chiave
WHERE g.tipologia IS NULL AND g.e_soglia = 1;

-- ---------------------------------------------------------------------------
-- Materializzazione dell'id_opzione_pagamento (UUID) per entrambi i pattern
-- PRIMA dell'insert: con id AUTO_INCREMENT non e' piu' pre-calcolabile come
-- con la sequenza emulata, quindi e' questo UUID — univoco per vincolo — a
-- correlare ogni riga neo-inserita in opzioni_pagamento con il relativo
-- versamento, dopo l'insert.
-- ---------------------------------------------------------------------------

CREATE TEMPORARY TABLE tmp_nuove_opzioni_ab AS
SELECT
    id_documento, chiave, id_pd_debitore, tipologia, stato,
    UNHEX(REPLACE(UUID(), '-', '')) AS id_opzione_pagamento
FROM tmp_gruppi
WHERE tipologia IS NOT NULL;

INSERT INTO opzioni_pagamento
    (id_opzione_pagamento, id_pd_debitore, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT id_opzione_pagamento, id_pd_debitore, tipologia, stato, 0, NOW(3), NOW(3)
FROM tmp_nuove_opzioni_ab;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL), posizione 1-based per id crescente dentro
-- il gruppo — solo per garantire indici distinti.
UPDATE versamenti v
JOIN tmp_nuove_opzioni_ab no ON no.id_documento = v.id_documento AND no.chiave = UPPER(v.debitore_identificativo)
JOIN opzioni_pagamento op ON op.id_opzione_pagamento = no.id_opzione_pagamento
JOIN (
    SELECT id,
           COALESCE(valore_numerico, ROW_NUMBER() OVER (PARTITION BY id_documento, chiave ORDER BY id)) AS numero_rata_calcolato
    FROM tmp_vrs_rata
) vn ON vn.id = v.id
SET v.id_opzione_pagamento = op.id,
    v.numero_rata = vn.numero_rata_calcolato;

CREATE TEMPORARY TABLE tmp_nuove_opzioni_c AS
SELECT
    id AS id_versamento, id_pd_debitore, tipologia, giorni, stato,
    UNHEX(REPLACE(UUID(), '-', '')) AS id_opzione_pagamento
FROM tmp_vrs_soglia;

INSERT INTO opzioni_pagamento
    (id_opzione_pagamento, id_pd_debitore, tipologia, giorni, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT id_opzione_pagamento, id_pd_debitore, tipologia, giorni, stato, 0, NOW(3), NOW(3)
FROM tmp_nuove_opzioni_c;

UPDATE versamenti v
JOIN tmp_nuove_opzioni_c no ON no.id_versamento = v.id
JOIN opzioni_pagamento op ON op.id_opzione_pagamento = no.id_opzione_pagamento
SET v.id_opzione_pagamento = op.id,
    v.numero_rata = 1;

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione (di qualunque suo debitore) di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
-- stessa semantica di PosizioneDebitoriaService.attiva() della libreria.
-- Vale su tutta la tabella, non solo sulle righe appena migrate. Prima i
-- versamenti: dopo, le opzioni da annullare non sarebbero piu'
-- riconoscibili. La tabella derivata (doppiamente annidata) serve perche'
-- MySQL non permette di aggiornare una tabella letta nella stessa sottoquery.
-- ---------------------------------------------------------------------------

UPDATE versamenti v
   SET v.stato_versamento = 'ANNULLATO',
       v.data_ora_ultimo_aggiornamento = NOW(3)
 WHERE v.stato_versamento = 'NON_ESEGUITO'
   AND v.id_opzione_pagamento IN (
       SELECT op.id FROM opzioni_pagamento op
        JOIN pd_debitori pd ON pd.id = op.id_pd_debitore
        WHERE op.stato = 'DISPONIBILE'
          AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                       JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
                       WHERE pd_altra.id_documento = pd.id_documento
                         AND altra.stato = 'ATTIVATA'));

UPDATE opzioni_pagamento
   SET stato = 'ANNULLATA',
       data_ultimo_aggiornamento = NOW(3)
 WHERE id IN (SELECT id FROM (
       SELECT op.id FROM opzioni_pagamento op
        JOIN pd_debitori pd ON pd.id = op.id_pd_debitore
        WHERE op.stato = 'DISPONIBILE'
          AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                       JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
                       WHERE pd_altra.id_documento = pd.id_documento
                         AND altra.stato = 'ATTIVATA')) da_annullare);

COMMIT;

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi dalla sintesi automatica sopra —
-- da eseguire ed esaminare DOPO lo script, non parte dell'automazione.
-- Gruppi (documento + debitore) con cod_rata genuinamente anomalo: non tutti NULL, non tutti
-- numerici validi senza duplicati, e non tutti valori-soglia riconosciuti
-- senza duplicati — pattern misto tra le tre famiglie, duplicato di
-- valore-soglia nello stesso documento, o dato corrotto. Da rivedere a mano.
-- ---------------------------------------------------------------------------

SELECT
    id_documento,
    chiave AS debitore_identificativo,
    COUNT(*) AS totale,
    SUM(flag_null) AS n_null,
    SUM(flag_numerico) AS n_numerico,
    COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) AS n_numerico_distinti,
    SUM(flag_soglia) AS n_soglia,
    COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti
FROM tmp_vrs_rata
GROUP BY id_documento, chiave
HAVING NOT (SUM(flag_null) = COUNT(*))
   AND NOT (SUM(flag_numerico) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) = COUNT(*))
   AND NOT (SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) = COUNT(*));

-- Debitori migrati senza alcuna opzione di pagamento: non dovrebbero esistere
-- (la transazione lo impedisce); se compaiono, derivano da dati scritti fuori
-- da questo script e vanno completati a mano.
SELECT pd.id AS id_pd_debitori, pd.id_documento, d.identificativo
FROM pd_debitori pd
JOIN debitori d ON d.id = pd.id_debitore
WHERE NOT EXISTS (SELECT 1 FROM opzioni_pagamento op WHERE op.id_pd_debitore = pd.id);

DROP TEMPORARY TABLE tmp_vrs_rata;
DROP TEMPORARY TABLE tmp_gruppi;
DROP TEMPORARY TABLE tmp_vrs_soglia;
DROP TEMPORARY TABLE tmp_nuove_opzioni_ab;
DROP TEMPORARY TABLE tmp_nuove_opzioni_c;
