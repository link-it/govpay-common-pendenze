-- ---------------------------------------------------------------------------
-- Ampliamento di documenti.descrizione e sintesi di un documento per ogni
-- versamento "orfano" (id_documento NULL).
--
-- Stesse motivazioni/precondizioni di postgresql/migrazione/05_documenti_orfani.sql
-- (leggere quel file per il dettaglio) — qui solo la sintassi MySQL.
--
-- Decodifica base64 adattata da govpay-aca-batch (sql/mysql/create-db.sql,
-- vista v_versamenti_gpd), confermata funzionante presso i clienti.
-- ---------------------------------------------------------------------------

ALTER TABLE documenti MODIFY COLUMN descrizione VARCHAR(1024) NOT NULL COMMENT 'Descrizione del documento';

-- Su MariaDB FROM_BASE64 di un testo non valido e' un errore, non NULL (a
-- differenza di MySQL) — interromperebbe l'intero script. Il formato si
-- controlla prima: corpo (dopo "01 ", posizione 4) non vuoto, lunghezza
-- multipla di 4, solo l'alfabeto base64 ammesso. Una causale che non supera
-- il controllo resta '' (stesso fallback della causale NULL), non blocca
-- l'INSERT.
-- id non specificato: AUTO_INCREMENT lo assegna da solo.
INSERT INTO documenti (id_dominio, id_applicazione, cod_documento, descrizione)
SELECT
    v.id_dominio,
    v.id_applicazione,
    CONCAT('MIGR-', v.id),
    COALESCE(
        CASE WHEN SUBSTRING(v.causale_versamento, 4) REGEXP '^[A-Za-z0-9+/]+={0,2}$'
              AND MOD(LENGTH(SUBSTRING(v.causale_versamento, 4)), 4) = 0
             THEN CAST(FROM_BASE64(SUBSTRING(v.causale_versamento, 3)) AS CHAR)
        END,
        ''
    )
FROM versamenti v
WHERE v.id_documento IS NULL;

UPDATE versamenti v
JOIN documenti d ON d.cod_documento = CONCAT('MIGR-', v.id)
SET v.id_documento = d.id
WHERE v.id_documento IS NULL;
