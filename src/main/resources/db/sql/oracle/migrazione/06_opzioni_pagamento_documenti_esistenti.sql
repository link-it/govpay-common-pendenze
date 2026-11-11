-- ---------------------------------------------------------------------------
-- Stessa logica di postgresql/migrazione/06_opzioni_pagamento_documenti_esistenti.sql
-- (leggere quel file per il ragionamento completo: raggruppamento dei
-- versamenti per debitore, tre pattern A/B/C, cascata di mutua esclusione) —
-- qui la sintassi Oracle, NON verificata contro un'istanza Oracle reale.
--
-- Atomico: ogni DDL (CREATE/DROP TABLE) fa commit implicito, quindi le
-- tabelle d'appoggio sono create TUTTE in testa e il blocco DML che segue e'
-- una sola transazione, chiusa da un unico COMMIT prima del report;
-- WHENEVER SQLERROR (SQL*Plus, impostato in testa) annulla tutto al primo
-- errore. Le tabelle di appoggio si eliminano in fondo, e all'inizio di ogni
-- esecuzione se un errore precedente le ha lasciate.
--
-- Oracle non supporta CTE scrivibili (niente "WITH cte AS (INSERT ...
-- RETURNING ...) UPDATE ..." come in PostgreSQL): la classificazione viene
-- materializzata in tabelle di appoggio (DROP alla fine).
-- ---------------------------------------------------------------------------

-- Al primo errore SQL*Plus annulla la transazione e termina: impostato prima di
-- qualunque istruzione, cosi' nessun passo prosegue su dati di appoggio incerti.
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

-- Pulizia iniziale delle SOLE tabelle di appoggio di questo script (tmp_vrs_rata,
-- tmp_gruppi, tmp_vrs_soglia): dopo un errore il DROP finale non e' stato
-- eseguito e le tabelle restano; senza questa pulizia una riesecuzione
-- fallirebbe nei CREATE TABLE, o userebbe dati di una esecuzione precedente.
-- ORA-00942 (tabella assente) e' il caso normale e si ignora.
BEGIN
    FOR t IN (SELECT column_value AS nome
                FROM TABLE(sys.odcivarchar2list('TMP_VRS_RATA', 'TMP_GRUPPI', 'TMP_VRS_SOGLIA'))) LOOP
        BEGIN
            EXECUTE IMMEDIATE 'DROP TABLE ' || t.nome || ' PURGE';
        EXCEPTION
            WHEN OTHERS THEN
                IF SQLCODE <> -942 THEN RAISE; END IF;
        END;
    END LOOP;
END;
/

CREATE TABLE tmp_vrs_rata AS
SELECT
    v.id,
    v.id_documento,
    UPPER(v.debitore_identificativo) AS chiave,
    v.cod_rata,
    v.stato_versamento,
    CASE WHEN v.cod_rata IS NULL THEN 1 ELSE 0 END AS flag_null,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^[1-9][0-9]*$') THEN 1 ELSE 0 END AS flag_numerico,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^[1-9][0-9]*$') THEN TO_NUMBER(v.cod_rata) END AS valore_numerico,
    CASE WHEN REGEXP_LIKE(v.cod_rata, '^(RIDOTTO|SCONTATO|ENTRO[0-9]+|OLTRE[0-9]+)$') THEN 1 ELSE 0 END AS flag_soglia
FROM versamenti v
WHERE v.id_documento IS NOT NULL
  AND v.id_opzione_pagamento IS NULL;

-- ---------------------------------------------------------------------------
-- Gruppi (documento, debitore) da migrare, con la loro classificazione.
-- Pattern A/B: un'opzione per gruppo. Stato: DISPONIBILE e' il caso di
-- default — copre sia "tutte NON_ESEGUITO" sia il mix NON_ESEGUITO/ANNULLATO
-- senza alcuna pendenza eseguita (annullamento indipendente della singola
-- pendenza, possibile solo in v2). I gruppi non classificabili non entrano:
-- restano nel report in fondo.
-- ---------------------------------------------------------------------------

CREATE TABLE tmp_gruppi AS
SELECT
    x.*,
    CAST(NULL AS NUMBER) AS posizione,
    CAST(NULL AS NUMBER) AS id_debitore,
    CAST(NULL AS NUMBER) AS id_pd_debitore
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

-- Pattern C: le righe da migrare (una per versamento), preparate qui perche' e' un DDL.
-- Il collegamento al pd_debitori si completa piu' sotto, quando gli id sono assegnati.
CREATE TABLE tmp_vrs_soglia AS
SELECT
    vc.id,
    CAST(NULL AS NUMBER) AS id_pd_debitore,
    g.id_documento,
    g.chiave,
    SYS_GUID() AS id_opzione_pagamento,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' THEN 'SOLUZIONE_UNICA_ENTRO'
         WHEN vc.cod_rata LIKE 'OLTRE%' THEN 'SOLUZIONE_UNICA_OLTRE'
         WHEN vc.cod_rata = 'SCONTATO' THEN 'SOLUZIONE_UNICA_SCONTATO'
         ELSE 'SOLUZIONE_UNICA_RIDOTTO'
    END AS tipologia,
    CASE WHEN vc.cod_rata LIKE 'ENTRO%' OR vc.cod_rata LIKE 'OLTRE%'
         THEN TO_NUMBER(SUBSTR(vc.cod_rata, 6))
    END AS giorni,
    CASE WHEN vc.stato_versamento = 'NON_ESEGUITO' THEN 'DISPONIBILE'
         WHEN vc.stato_versamento = 'ANNULLATO' THEN 'ANNULLATA'
         ELSE 'ATTIVATA'
    END AS stato
FROM tmp_vrs_rata vc
JOIN tmp_gruppi g ON g.id_documento = vc.id_documento AND g.chiave = vc.chiave
WHERE g.tipologia IS NULL AND g.e_soglia = 1;

-- Da qui in poi solo DML: una sola transazione, chiusa dal COMMIT prima del report.

-- Un gruppo gia' presente come pd_debitori dello stesso documento (stesso
-- identificativo) non si duplica: protegge da una riesecuzione dopo una
-- correzione manuale parziale.
DELETE FROM tmp_gruppi g
 WHERE EXISTS (SELECT 1 FROM pd_debitori pd JOIN debitori d ON d.id = pd.id_debitore
                WHERE pd.id_documento = g.id_documento AND UPPER(d.identificativo) = g.chiave);

-- Ordine dei debitori nel documento: prima comparsa. Gli id vengono dalle
-- sequenze delle due tabelle (NEXTVAL e' ammesso nella SET di una UPDATE,
-- valutato una volta per riga).
UPDATE tmp_gruppi g
   -- Dopo i debitori gia' migrati del documento: una riesecuzione non duplica l'ordine.
   SET posizione = COALESCE((SELECT MAX(pd.posizione) + 1 FROM pd_debitori pd
                              WHERE pd.id_documento = g.id_documento), 0)
                   + (SELECT COUNT(*) FROM tmp_gruppi g2
                       WHERE g2.id_documento = g.id_documento
                         AND g2.primo_versamento < g.primo_versamento),
       id_debitore = seq_debitori.NEXTVAL,
       id_pd_debitore = seq_pd_debitori.NEXTVAL;

-- Collegamento delle righe pattern C al pd_debitori del loro gruppo; quelle di
-- gruppi gia' migrati (tolti da tmp_gruppi) non hanno corrispondenza e si scartano.
UPDATE tmp_vrs_soglia vs
   SET id_pd_debitore = (SELECT g.id_pd_debitore FROM tmp_gruppi g
                          WHERE g.id_documento = vs.id_documento AND g.chiave = vs.chiave);
DELETE FROM tmp_vrs_soglia WHERE id_pd_debitore IS NULL;

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

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_pd_debitore, tipologia, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT
    seq_opzioni_pagamento.NEXTVAL,
    SYS_GUID(),
    g.id_pd_debitore,
    g.tipologia,
    g.stato,
    0,
    SYSTIMESTAMP,
    SYSTIMESTAMP
FROM tmp_gruppi g
WHERE g.tipologia IS NOT NULL;

-- numero_rata: valore dichiarato preservato (PIANO_RATEALE) oppure, per
-- SOLUZIONE_UNICA (cod_rata NULL), posizione 1-based per id crescente dentro
-- il gruppo. Limitato ai gruppi A/B: un gruppo con pattern C ha piu' opzioni
-- sotto lo stesso pd_debitori (una per versamento) — la sottoquery scalare
-- qui sotto resterebbe ambigua (ORA-01427) se applicata anche a quelli.
UPDATE versamenti v
SET (id_opzione_pagamento, numero_rata) = (
    SELECT o.id,
           COALESCE(
               vr.valore_numerico,
               (SELECT COUNT(*) FROM tmp_vrs_rata vr2
                WHERE vr2.id_documento = vr.id_documento AND vr2.chiave = vr.chiave AND vr2.id <= vr.id)
           )
    FROM tmp_vrs_rata vr
    JOIN tmp_gruppi g ON g.id_documento = vr.id_documento AND g.chiave = vr.chiave
    JOIN opzioni_pagamento o ON o.id_pd_debitore = g.id_pd_debitore
    WHERE vr.id = v.id AND g.tipologia IS NOT NULL
)
WHERE v.id IN (
    SELECT vr.id FROM tmp_vrs_rata vr
    JOIN tmp_gruppi g ON g.id_documento = vr.id_documento AND g.chiave = vr.chiave
    WHERE g.tipologia IS NOT NULL
);

-- ---------------------------------------------------------------------------
-- Pattern C: ENTRO/OLTRE espliciti, inclusa la coppia RIDOTTO/SCONTATO. Una
-- opzione PER VERSAMENTO (cardinalita' massima 1 per queste tipologie), non
-- un'aggregazione per gruppo come A/B — solo se TUTTO il gruppo e' fatto di
-- valori-soglia riconosciuti e senza duplicati. Il GUID si genera prima,
-- come colonna: serve a ritrovare l'id della singola opzione appena
-- inserita per collegarla al SUO versamento (un debitore con piu' soglie,
-- es. ENTRO+OLTRE, ha piu' opzioni sotto lo stesso pd_debitori — il solo
-- id_pd_debitore non basta a distinguerle).
-- ---------------------------------------------------------------------------

INSERT INTO opzioni_pagamento
    (id, id_opzione_pagamento, id_pd_debitore, tipologia, giorni, stato, versione,
     data_creazione, data_ultimo_aggiornamento)
SELECT
    seq_opzioni_pagamento.NEXTVAL,
    vs.id_opzione_pagamento,
    vs.id_pd_debitore,
    vs.tipologia,
    vs.giorni,
    vs.stato,
    0,
    SYSTIMESTAMP,
    SYSTIMESTAMP
FROM tmp_vrs_soglia vs;

UPDATE versamenti v
SET (id_opzione_pagamento, numero_rata) = (
    SELECT o.id, 1
    FROM tmp_vrs_soglia vs
    JOIN opzioni_pagamento o ON o.id_opzione_pagamento = vs.id_opzione_pagamento
    WHERE vs.id = v.id
)
WHERE v.id IN (SELECT id FROM tmp_vrs_soglia);

-- ---------------------------------------------------------------------------
-- Notifica SEND per debitore: un debitore con almeno un versamento v2 con
-- send_abilitato porta notifica_send e il NAV di notifica. Il NAV e' quello della
-- pendenza di numero_rata piu' basso (soluzione unica, rata del piano rateale) e,
-- per i gruppi con sole soglie, del primo versamento con send_abilitato. Un NAV
-- assente resta NULL ed e' elencato dal report diagnostico finale.
-- ---------------------------------------------------------------------------

UPDATE pd_debitori p SET p.notifica_send = 1
WHERE p.id IN (SELECT g.id_pd_debitore FROM tmp_gruppi g)
  AND EXISTS (SELECT 1 FROM opzioni_pagamento o JOIN versamenti v ON v.id_opzione_pagamento = o.id
              WHERE o.id_pd_debitore = p.id AND v.send_abilitato = 1);

UPDATE pd_debitori p SET p.notifica_send_nav = (SELECT v.numero_avviso
    FROM opzioni_pagamento o JOIN versamenti v ON v.id_opzione_pagamento = o.id
    WHERE o.id_pd_debitore = p.id
      AND v.numero_rata = (SELECT MIN(v2.numero_rata)
                           FROM opzioni_pagamento o2 JOIN versamenti v2 ON v2.id_opzione_pagamento = o2.id
                           WHERE o2.id_pd_debitore = p.id))
WHERE p.notifica_send = 1
  AND p.id IN (SELECT g.id_pd_debitore FROM tmp_gruppi g WHERE g.tipologia IS NOT NULL);

UPDATE pd_debitori p SET p.notifica_send_nav = (SELECT v.numero_avviso
    FROM opzioni_pagamento o JOIN versamenti v ON v.id_opzione_pagamento = o.id
    WHERE o.id_pd_debitore = p.id
      AND v.id = (SELECT MIN(v2.id)
                  FROM opzioni_pagamento o2 JOIN versamenti v2 ON v2.id_opzione_pagamento = o2.id
                  WHERE o2.id_pd_debitore = p.id AND v2.send_abilitato = 1))
WHERE p.notifica_send = 1
  AND p.id IN (SELECT g.id_pd_debitore FROM tmp_gruppi g WHERE g.tipologia IS NULL);

-- ---------------------------------------------------------------------------
-- Cascata della mutua esclusione: un'opzione ancora DISPONIBILE sulla stessa
-- posizione (di qualunque suo debitore) di un'opzione ATTIVATA e' un'alternativa non piu' applicabile —
-- stessa semantica di PosizioneDebitoriaService.attiva() della libreria.
-- Vale su tutta la tabella, non solo sulle righe appena migrate. Prima i
-- versamenti: dopo, le opzioni da annullare non sarebbero piu'
-- riconoscibili (non sarebbero piu' DISPONIBILE).
-- ---------------------------------------------------------------------------

UPDATE versamenti v
SET stato_versamento = 'ANNULLATO',
    data_ora_ultimo_aggiornamento = SYSTIMESTAMP
WHERE stato_versamento = 'NON_ESEGUITO'
  AND id_opzione_pagamento IN (
      SELECT op.id FROM opzioni_pagamento op
       JOIN pd_debitori pd ON pd.id = op.id_pd_debitore
       WHERE op.stato = 'DISPONIBILE'
         AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
                      JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
                      WHERE pd_altra.id_documento = pd.id_documento
                        AND altra.stato = 'ATTIVATA'));

UPDATE opzioni_pagamento op
SET stato = 'ANNULLATA',
    data_ultimo_aggiornamento = SYSTIMESTAMP
WHERE op.stato = 'DISPONIBILE'
  AND EXISTS (SELECT 1 FROM opzioni_pagamento altra
               JOIN pd_debitori pd_altra ON pd_altra.id = altra.id_pd_debitore
               WHERE altra.stato = 'ATTIVATA'
                 AND pd_altra.id_documento = (SELECT pd_op.id_documento FROM pd_debitori pd_op
                                               WHERE pd_op.id = op.id_pd_debitore));

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

-- Debitori con notifica SEND prevista ma senza NAV di notifica (versamento v2 con
-- send_abilitato e numero_avviso assente): SEND non verra' interrogato per loro.
SELECT pd.id AS id_pd_debitori, pd.id_documento, d.identificativo
FROM pd_debitori pd
JOIN debitori d ON d.id = pd.id_debitore
WHERE pd.notifica_send = 1 AND pd.notifica_send_nav IS NULL;

DROP TABLE tmp_vrs_rata;
DROP TABLE tmp_gruppi;
DROP TABLE tmp_vrs_soglia;
