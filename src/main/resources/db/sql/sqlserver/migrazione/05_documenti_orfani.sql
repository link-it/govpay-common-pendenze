-- ---------------------------------------------------------------------------
-- Ampliamento di documenti.descrizione e sintesi di un documento per ogni
-- versamento "orfano" (id_documento NULL).
--
-- Stesse motivazioni/precondizioni di postgresql/migrazione/05_documenti_orfani.sql
-- (leggere quel file per il dettaglio) — qui solo la sintassi SQL Server.
--
-- Decodifica base64: CONVERT(VARCHAR, varbinary) legge i byte decodificati
-- nella code page del database, non in UTF-8 — una COLLATE applicata DOPO
-- non li reinterpreta, risultato corrotto su qualunque lettera accentata
-- (es. "città" diventa "citt?" o peggio). I byte passano invece da un
-- documento XML che dichiara esplicitamente la codifica UTF-8: i metodi del
-- tipo XML la decodificano correttamente. TRY_CONVERT torna NULL se i byte
-- non sono UTF-8 valido (causale corrotta) invece di interrompere lo
-- script. Richiede SET QUOTED_IDENTIFIER ON (sotto), che sqlcmd non imposta
-- da solo.
-- ---------------------------------------------------------------------------

SET QUOTED_IDENTIFIER ON;
GO

ALTER TABLE documenti ALTER COLUMN descrizione VARCHAR(1024) NOT NULL;

-- id non specificato: IDENTITY lo assegna da solo.
INSERT INTO documenti (id_dominio, id_applicazione, cod_documento, descrizione)
SELECT
    v.id_dominio,
    v.id_applicazione,
    'MIGR-' + CAST(v.id AS VARCHAR(20)),
    COALESCE(
        CAST(TRY_CONVERT(XML,
            CONVERT(VARBINARY(MAX), '<?xml version="1.0" encoding="UTF-8"?><x><![CDATA[')
            + CAST(N'' AS XML).value('xs:base64Binary(sql:column("b.b64"))', 'VARBINARY(MAX)')
            + CONVERT(VARBINARY(MAX), ']]></x>')
        ).value('(/x)[1]', 'NVARCHAR(1024)') AS VARCHAR(1024)),
        ''
    )
FROM versamenti v
CROSS APPLY (SELECT SUBSTRING(v.causale_versamento, 3, 8000) AS b64) b
WHERE v.id_documento IS NULL;

UPDATE v
SET v.id_documento = d.id
FROM versamenti v
JOIN documenti d ON d.cod_documento = 'MIGR-' + CAST(v.id AS VARCHAR(20))
WHERE v.id_documento IS NULL;
