-- ---------------------------------------------------------------------------
-- Tutte le colonne aggiunte sono nullable: nessun default/sentinella
-- necessario. tipo_riferimento in particolare resta NULL per le voci v2
-- esistenti (nessun valore sensato da retro-assegnare) — l'obbligatorieta' per
-- le voci create da v3 e' solo una validazione applicativa (campo obbligatorio
-- dello YAML v3), non un vincolo DB (vedi Javadoc di VocePendenza.tipoRiferimento).
-- ---------------------------------------------------------------------------

ALTER TABLE singoli_versamenti ADD COLUMN IF NOT EXISTS tipo_riferimento VARCHAR(35);
ALTER TABLE singoli_versamenti ADD COLUMN IF NOT EXISTS cod_entrata VARCHAR(35);
ALTER TABLE singoli_versamenti ADD COLUMN IF NOT EXISTS iban_accredito_v3 VARCHAR(35);
ALTER TABLE singoli_versamenti ADD COLUMN IF NOT EXISTS iban_appoggio_v3 VARCHAR(35);
ALTER TABLE singoli_versamenti ADD COLUMN IF NOT EXISTS tassonomia_v3 VARCHAR(35);
