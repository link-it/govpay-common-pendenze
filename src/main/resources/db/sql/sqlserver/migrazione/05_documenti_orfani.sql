-- ---------------------------------------------------------------------------
-- Ampliamento di documenti.descrizione e sintesi di un documento per ogni
-- versamento "orfano" (id_documento NULL).
--
-- Stesse motivazioni/precondizioni di postgresql/migrazione/05_documenti_orfani.sql
-- (leggere quel file per il dettaglio) — qui solo la sintassi SQL Server.
--
-- Decodifica base64: la vista v_versamenti_gpd di govpay-aca-batch
-- (sql/sqlserver/create-db.sql) si ferma alla conversione a VARBINARY(MAX),
-- senza un passo finale a testo — qui completata con una collation UTF-8
-- (richiede SQL Server 2019+). NON verificata contro un'istanza reale: va
-- riconfermata prima dell'uso in produzione, con attenzione particolare a
-- caratteri accentati nella causale (es. "città", "perché").
-- ---------------------------------------------------------------------------

ALTER TABLE documenti ALTER COLUMN descrizione VARCHAR(1024) NOT NULL;

-- id non specificato: IDENTITY lo assegna da solo.
INSERT INTO documenti (id_dominio, id_applicazione, cod_documento, descrizione)
SELECT
    v.id_dominio,
    v.id_applicazione,
    'MIGR-' + CAST(v.id AS VARCHAR(20)),
    COALESCE(
        CONVERT(VARCHAR(1024),
            CAST(N'' AS XML).value(
                'xs:base64Binary(substring(sql:column("v.causale_versamento"), 3))',
                'VARBINARY(MAX)'
            )
        ) COLLATE Latin1_General_100_CI_AS_SC_UTF8,
        ''
    )
FROM versamenti v
WHERE v.id_documento IS NULL;

UPDATE v
SET v.id_documento = d.id
FROM versamenti v
JOIN documenti d ON d.cod_documento = 'MIGR-' + CAST(v.id AS VARCHAR(20))
WHERE v.id_documento IS NULL;
