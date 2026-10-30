-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo sui tre pattern A/B/C e
-- sulla cascata di mutua esclusione) — qui la sintassi MySQL, compatibile
-- anche con MariaDB. Richiede MySQL 8.0+/MariaDB 10.2+ (funzioni finestra,
-- REGEXP compatibile).
--
-- MySQL non supporta CTE scrivibili: la classificazione viene materializzata
-- in tabelle temporanee di sessione (si eliminano da sole a fine sessione,
-- comunque droppate esplicitamente in fondo).
--
-- id_opzione_pagamento (BINARY(16)) e' generato con UNHEX(REPLACE(UUID(),
-- '-', '')), non UUID_TO_BIN(UUID()): quest'ultima esiste solo su MySQL 8,
-- non su MariaDB. id (seq_opzioni_pagamento emulata con una tabella a riga
-- singola, vedi 02_opzioni_pagamento.sql di questa cartella) segue la stessa
-- convenzione gia' in uso in questo script: un blocco di id viene riservato
-- in anticipo (una sola volta, per entrambi i pattern A/B e C) e assegnato
-- per riga con ROW_NUMBER().
-- ---------------------------------------------------------------------------

CREATE TEMPORARY TABLE tmp_vrs_rata AS
SELECT
    v.id,
    v.id_documento,
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
-- Pattern A/B: un'opzione per documento (SOLUZIONE_UNICA se tutti i cod_rata
-- sono NULL, PIANO_RATEALE se tutti numerici distinti). Stato: DISPONIBILE e'
-- il caso di default — copre sia "tutte NON_ESEGUITO" sia il mix
-- NON_ESEGUITO/ANNULLATO senza alcuna pendenza eseguita (annullamento
-- indipendente della singola pendenza, possibile solo in v2).
-- ---------------------------------------------------------------------------

CREATE TEMPORARY TABLE tmp_classificazione AS
SELECT
    id_documento,
    CASE WHEN n_null = totale THEN 'SOLUZIONE_UNICA'
         WHEN n_numerico = totale AND n_numerico_distinti = totale THEN 'PIANO_RATEALE'
    END AS tipologia,
    CASE WHEN n_annullato = totale THEN 'ANNULLATA'
         WHEN n_altro > 0 THEN 'ATTIVATA'
         ELSE 'DISPONIBILE'
    END AS stato
FROM (
    SELECT
        id_documento,
        COUNT(*) AS totale,
        SUM(flag_null) AS n_null,
        SUM(flag_numerico) AS n_numerico,
        COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) AS n_numerico_distinti,
        SUM(CASE WHEN stato_versamento = 'ANNULLATO' THEN 1 ELSE 0 END) AS n_annullato,
        SUM(CASE WHEN stato_versamento NOT IN ('NON_ESEGUITO', 'ANNULLATO') THEN 1 ELSE 0 END) AS n_altro
    FROM tmp_vrs_rata
    GROUP BY id_documento
) aggr;

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO. Una
-- opzione PER VERSAMENTO (cardinalita' massima 1 per queste tipologie), non
-- un'aggregazione per documento come A/B — solo se TUTTO il documento e'
-- fatto di valori-soglia riconosciuti e senza duplicati.
-- ---------------------------------------------------------------------------

CREATE TEMPORARY TABLE tmp_doc_soglia AS
SELECT id_documento
FROM tmp_vrs_rata
GROUP BY id_documento
HAVING SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT cod_rata) = COUNT(*) AND COUNT(*) > 0;

CREATE TEMPORARY TABLE tmp_vrs_soglia AS
SELECT
    vc.id,
    vc.id_documento,
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
JOIN tmp_doc_soglia ds ON ds.id_documento = vc.id_documento;

-- ---------------------------------------------------------------------------
-- Riserva di un blocco di id per entrambi i pattern (A/B + C) in un solo
-- colpo, poi assegnazione per riga con ROW_NUMBER() sulle due tabelle.
-- ---------------------------------------------------------------------------

SET @cnt_ab = (SELECT COUNT(*) FROM tmp_classificazione WHERE tipologia IS NOT NULL);
SET @cnt_c = (SELECT COUNT(*) FROM tmp_vrs_soglia);
SET @start_id = (SELECT next_val FROM seq_opzioni_pagamento);
UPDATE seq_opzioni_pagamento SET next_val = next_val + @cnt_ab + @cnt_c;

CREATE TEMPORARY TABLE tmp_nuove_opzioni_ab AS
SELECT
    @start_id + ROW_NUMBER() OVER (ORDER BY id_documento) - 1 AS id,
    id_documento, tipologia, stato
FROM tmp_classificazione
WHERE tipologia IS NOT NULL;

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_documento, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT id, UNHEX(REPLACE(UUID(), '-', '')), id_documento, tipologia, stato, 0, NOW(3), NOW(3)
FROM tmp_nuove_opzioni_ab;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL), posizione 1-based per id crescente dentro
-- il documento — solo per garantire indici distinti.
UPDATE versamenti v
JOIN tmp_nuove_opzioni_ab no ON no.id_documento = v.id_documento
JOIN (
    SELECT id, id_documento,
           COALESCE(valore_numerico, ROW_NUMBER() OVER (PARTITION BY id_documento ORDER BY id)) AS numero_rata_calcolato
    FROM tmp_vrs_rata
) vn ON vn.id = v.id
SET v.id_opzione_pagamento = no.id,
    v.numero_rata = vn.numero_rata_calcolato;

CREATE TEMPORARY TABLE tmp_nuove_opzioni_c AS
SELECT
    @start_id + @cnt_ab + ROW_NUMBER() OVER (ORDER BY id) - 1 AS id_opzione,
    id AS id_versamento, id_documento, tipologia, giorni, stato
FROM tmp_vrs_soglia;

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_documento, tipologia, giorni, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT id_opzione, UNHEX(REPLACE(UUID(), '-', '')), id_documento, tipologia, giorni, stato, 0, NOW(3), NOW(3)
FROM tmp_nuove_opzioni_c;

UPDATE versamenti v
JOIN tmp_nuove_opzioni_c no ON no.id_versamento = v.id
SET v.id_opzione_pagamento = no.id_opzione,
    v.numero_rata = 1;

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
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
        WHERE op.stato = 'DISPONIBILE'
          AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                       WHERE altra.id_documento = op.id_documento
                         AND altra.stato = 'ATTIVATA'));

UPDATE opzioni_pagamento
   SET stato = 'ANNULLATA',
       data_ultimo_aggiornamento = NOW(3)
 WHERE id IN (SELECT id FROM (
       SELECT op.id FROM opzioni_pagamento op
        WHERE op.stato = 'DISPONIBILE'
          AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                       WHERE altra.id_documento = op.id_documento
                         AND altra.stato = 'ATTIVATA')) da_annullare);

-- ---------------------------------------------------------------------------
-- Report diagnostico sui documenti esclusi dalla sintesi automatica sopra —
-- da eseguire ed esaminare DOPO lo script, non parte dell'automazione.
-- Documenti con cod_rata genuinamente anomalo: non tutti NULL, non tutti
-- numerici validi senza duplicati, e non tutti valori-soglia riconosciuti
-- senza duplicati — pattern misto tra le tre famiglie, duplicato di
-- valore-soglia nello stesso documento, o dato corrotto. Da rivedere a mano.
-- ---------------------------------------------------------------------------

SELECT
    id_documento,
    COUNT(*) AS totale,
    SUM(flag_null) AS n_null,
    SUM(flag_numerico) AS n_numerico,
    COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) AS n_numerico_distinti,
    SUM(flag_soglia) AS n_soglia,
    COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) AS n_soglia_distinti
FROM tmp_vrs_rata
GROUP BY id_documento
HAVING NOT (SUM(flag_null) = COUNT(*))
   AND NOT (SUM(flag_numerico) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_numerico = 1 THEN valore_numerico END) = COUNT(*))
   AND NOT (SUM(flag_soglia) = COUNT(*) AND COUNT(DISTINCT CASE WHEN flag_soglia = 1 THEN cod_rata END) = COUNT(*));

DROP TEMPORARY TABLE tmp_vrs_rata;
DROP TEMPORARY TABLE tmp_classificazione;
DROP TEMPORARY TABLE tmp_doc_soglia;
DROP TEMPORARY TABLE tmp_vrs_soglia;
DROP TEMPORARY TABLE tmp_nuove_opzioni_ab;
DROP TEMPORARY TABLE tmp_nuove_opzioni_c;
