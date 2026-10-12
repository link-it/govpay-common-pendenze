-- ---------------------------------------------------------------------------
-- Migrazione di un DB GovPay v2 esistente alla struttura v3 (api-pendenze-v3).
--
-- Dialetto: HSQLDB — verificato eseguendo lo script contro HSQLDB 2.7.3.
-- Nota: HSQLDB richiede la clausola DEFAULT PRIMA di NOT NULL in una
-- definizione di colonna ("... DEFAULT <valore> NOT NULL", non il contrario);
-- l'ordine opposto, usato nei patch del core per questo dialetto (vedi
-- src/main/resources/db/sql/hsql/patch/3.10.0.sql), viene rifiutato da
-- HSQLDB 2.7.3 con un errore di sintassi su ADD COLUMN.
--
-- Script a se stanti, NON parte della catena di patch versionate del core
-- GovPay — vanno eseguiti a parte su un DB esistente, in ordine di
-- numerazione (01..04), una sola volta.
--
-- Convenzione dei valori sentinella: dove serve un default per righe v2 gia'
-- esistenti su colonne NOT NULL nuove, si usa un valore ovviamente non
-- plausibile come dato reale (1970-01-01 per le date, -1 per numero_rata),
-- cosi' e' sempre riconoscibile "questo record e' stato inserito dalla v2" —
-- mai un valore verosimile (es. CURRENT_TIMESTAMP) che potrebbe passare per un
-- dato genuino. Nessun codice v3 legge mai questi valori per righe v2 (un
-- cliente e' sempre o v2 o v3, mai entrambi sullo stesso record).
--
-- rpt/pagamenti/fr/rendicontazioni non hanno bisogno di alcuna modifica: le
-- colonne che servono a v3 esistono gia' tutte in produzione (vedi
-- docs/proposta-modello-nativo-v3.md).
-- ---------------------------------------------------------------------------

ALTER TABLE documenti ADD COLUMN id_unita_operativa BIGINT;

-- Nullable, nessuna sentinella necessaria: NULL significa "pubblicata subito"
-- (semantica dello YAML v3), che e' esattamente il significato corretto anche
-- per le righe v2 esistenti (v2 non ha mai avuto questo concetto).
ALTER TABLE documenti ADD COLUMN data_pubblicazione DATE;

ALTER TABLE documenti ADD COLUMN notifica_send BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE documenti ADD COLUMN nav_notifica VARCHAR(18);
ALTER TABLE documenti ADD COLUMN data_ultima_modifica_aca TIMESTAMP;
ALTER TABLE documenti ADD COLUMN data_ultima_comunicazione_aca TIMESTAMP;

-- Sentinella 1970-01-01: nessun equivalente v2 da cui derivare queste due date
-- per i documenti esistenti (v2 usa documenti/id_documento per l'avviso
-- cumulativo, ma non traccia una propria data di creazione/aggiornamento).
ALTER TABLE documenti ADD COLUMN data_creazione TIMESTAMP DEFAULT TIMESTAMP '1970-01-01 00:00:00' NOT NULL;
ALTER TABLE documenti ADD COLUMN data_ultimo_aggiornamento TIMESTAMP DEFAULT TIMESTAMP '1970-01-01 00:00:00' NOT NULL;

-- Lock ottimistico senza questa colonna, aggiungere un'opzione di pagamento e
-- attivarne un'altra sulla stessa posizione possono correre in parallelo
-- senza che nessuno dei due veda le modifiche dell'altro.
ALTER TABLE documenti ADD COLUMN versione BIGINT DEFAULT 0 NOT NULL;

-- Unicita' della posizione per applicativo, anche tra domini diversi.
-- La creazione fallisce se esistono duplicati: risolverli prima di riprovare.
-- Diagnostica:
-- SELECT cod_documento, id_applicazione, COUNT(*)
-- FROM documenti GROUP BY cod_documento, id_applicazione HAVING COUNT(*) > 1;
ALTER TABLE documenti ADD CONSTRAINT unique_documenti_applicazione
    UNIQUE (cod_documento, id_applicazione);
