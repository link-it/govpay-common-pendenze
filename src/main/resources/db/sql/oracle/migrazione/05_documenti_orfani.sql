-- ---------------------------------------------------------------------------
-- Ampliamento di documenti.descrizione e sintesi di un documento per ogni
-- versamento "orfano" (id_documento NULL).
--
-- Stesse motivazioni/precondizioni di postgresql/migrazione/05_documenti_orfani.sql
-- (leggere quel file per il dettaglio) — qui solo la sintassi Oracle.
--
-- Decodifica base64 adattata da govpay-aca-batch (sql/oracle/create-db.sql,
-- vista v_versamenti_gpd, gia' in uso presso i clienti) — NON riverificata
-- qui contro un'istanza Oracle reale, va riconfermata prima dell'uso in
-- produzione (stessa cautela di 01_documenti.sql).
-- ---------------------------------------------------------------------------

ALTER TABLE documenti MODIFY (descrizione VARCHAR2(1024 CHAR));

-- NVL, non "COALESCE con stringa vuota": Oracle non distingue una stringa
-- vuota da NULL, quindi una causale NULL produrrebbe comunque NULL e
-- violerebbe il NOT NULL su descrizione. Un singolo spazio e' il piu' vicino
-- a "vuoto" rappresentabile.
INSERT INTO documenti (id, id_dominio, id_applicazione, cod_documento, descrizione)
SELECT
    seq_documenti.NEXTVAL,
    v.id_dominio,
    v.id_applicazione,
    'MIGR-' || v.id,
    NVL(
        CASE WHEN v.causale_versamento IS NOT NULL THEN
            UTL_RAW.CAST_TO_VARCHAR2(
                UTL_ENCODE.BASE64_DECODE(
                    UTL_RAW.CAST_TO_RAW(SUBSTR(v.causale_versamento, 4))
                )
            )
        END,
        ' '
    )
FROM versamenti v
WHERE v.id_documento IS NULL;

UPDATE versamenti v
SET id_documento = (
    SELECT d.id FROM documenti d WHERE d.cod_documento = 'MIGR-' || v.id
)
WHERE v.id_documento IS NULL;
