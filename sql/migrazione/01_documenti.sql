-- ---------------------------------------------------------------------------
-- Migrazione di un DB GovPay v2 esistente alla struttura v3 (api-pendenze-v3).
--
-- Ambito: solo PostgreSQL (nessun altro dialetto e' stato verificato in questa
-- analisi). Script a se stanti, NON parte della catena di patch versionate del
-- core GovPay (src/main/resources/db/sql/<dialetto>/patch) — vanno eseguiti a
-- parte su un DB esistente, in ordine di numerazione (01..05), una sola volta.
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

ALTER TABLE documenti ADD COLUMN IF NOT EXISTS id_unita_operativa BIGINT;

-- Nullable, nessuna sentinella necessaria: NULL significa "pubblicata subito"
-- (semantica dello YAML v3), che e' esattamente il significato corretto anche
-- per le righe v2 esistenti (v2 non ha mai avuto questo concetto).
ALTER TABLE documenti ADD COLUMN IF NOT EXISTS data_pubblicazione DATE;

ALTER TABLE documenti ADD COLUMN IF NOT EXISTS notifica_send BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE documenti ADD COLUMN IF NOT EXISTS nav_notifica VARCHAR(18);
ALTER TABLE documenti ADD COLUMN IF NOT EXISTS data_ultima_modifica_aca TIMESTAMP;
ALTER TABLE documenti ADD COLUMN IF NOT EXISTS data_ultima_comunicazione_aca TIMESTAMP;

-- Sentinella 1970-01-01: nessun equivalente v2 da cui derivare queste due date
-- per i documenti esistenti (v2 usa documenti/id_documento per l'avviso
-- cumulativo, ma non traccia una propria data di creazione/aggiornamento).
ALTER TABLE documenti ADD COLUMN IF NOT EXISTS data_creazione TIMESTAMP NOT NULL DEFAULT '1970-01-01 00:00:00';
ALTER TABLE documenti ADD COLUMN IF NOT EXISTS data_ultimo_aggiornamento TIMESTAMP NOT NULL DEFAULT '1970-01-01 00:00:00';

-- Unicita' della posizione per applicativo, anche tra domini diversi.
-- La creazione fallisce se esistono duplicati: risolverli prima di riprovare.
-- Diagnostica:
-- SELECT cod_documento, id_applicazione, COUNT(*)
-- FROM documenti GROUP BY cod_documento, id_applicazione HAVING COUNT(*) > 1;
ALTER TABLE documenti ADD CONSTRAINT unique_documenti_applicazione
    UNIQUE (cod_documento, id_applicazione);
