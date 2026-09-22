-- ---------------------------------------------------------------------------
-- Schema di test dell'aggregato pendenza (modello nativo v3).
--
-- Provenienza: nessun DDL di produzione esiste ancora (nessuna migrazione dati
--   scritta finora) — questo schema e' derivato colonna per colonna dalle
--   annotazioni JPA delle 5 entita' in src/main/java/it/govpay/pendenze/entity,
--   che restano la fonte di verita'. Se le entita' cambiano, questo file va
--   riallineato: e' su questo che i test verificano il mapping con
--   spring.jpa.hibernate.ddl-auto=validate.
--
-- Non sono presenti foreign key verso l'anagrafica (domini, unita' operative,
-- tipi pendenza), che qui non esiste: la libreria mappa quelle colonne come
-- semplici FK Long, senza relazioni JPA (M4 di proposta-modello-nativo-v3.md).
-- ---------------------------------------------------------------------------

CREATE SEQUENCE seq_posizioni_debitorie start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE seq_soggetti_debitori start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE seq_opzioni_pagamento start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE seq_pendenze start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE seq_voci_pendenza start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE posizioni_debitorie
(
	id_a2a VARCHAR(35) NOT NULL,
	id_posizione_debitoria VARCHAR(35) NOT NULL,
	id_dominio BIGINT NOT NULL,
	id_unita_operativa BIGINT,
	descrizione VARCHAR(140) NOT NULL,
	data_pubblicazione DATE,
	notifica_send BOOLEAN NOT NULL,
	nav_notifica VARCHAR(18),
	data_ultima_modifica_aca TIMESTAMP,
	data_ultima_comunicazione_aca TIMESTAMP,
	data_creazione TIMESTAMP NOT NULL,
	data_ultimo_aggiornamento TIMESTAMP NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_posizioni_debitorie') NOT NULL,
	-- unique constraints
	CONSTRAINT unique_posizioni_debitorie_1 UNIQUE (id_a2a, id_posizione_debitoria),
	-- fk/pk keys constraints
	CONSTRAINT pk_posizioni_debitorie PRIMARY KEY (id)
);

CREATE TABLE soggetti_debitori
(
	ordine INT NOT NULL,
	tipo VARCHAR(1) NOT NULL,
	identificativo VARCHAR(16) NOT NULL,
	anagrafica VARCHAR(70),
	indirizzo VARCHAR(70),
	civico VARCHAR(16),
	cap VARCHAR(16),
	localita VARCHAR(35),
	provincia VARCHAR(35),
	nazione VARCHAR(2),
	email VARCHAR(256),
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_soggetti_debitori') NOT NULL,
	id_posizione_debitoria BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_soggetti_debitori_1 UNIQUE (id_posizione_debitoria, ordine),
	-- fk/pk keys constraints
	CONSTRAINT fk_sgd_id_posizione_debitoria FOREIGN KEY (id_posizione_debitoria) REFERENCES posizioni_debitorie(id),
	CONSTRAINT pk_soggetti_debitori PRIMARY KEY (id)
);

CREATE TABLE opzioni_pagamento
(
	versione BIGINT NOT NULL,
	id_opzione_pagamento UUID NOT NULL,
	tipologia VARCHAR(35) NOT NULL,
	giorni INT,
	stato VARCHAR(35) NOT NULL,
	data_inizio_validita DATE,
	data_scadenza DATE,
	data_creazione TIMESTAMP NOT NULL,
	data_ultimo_aggiornamento TIMESTAMP NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_opzioni_pagamento') NOT NULL,
	id_posizione_debitoria BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_opzioni_pagamento_id_opzione UNIQUE (id_opzione_pagamento),
	-- fk/pk keys constraints
	CONSTRAINT fk_opz_id_posizione_debitoria FOREIGN KEY (id_posizione_debitoria) REFERENCES posizioni_debitorie(id),
	CONSTRAINT pk_opzioni_pagamento PRIMARY KEY (id)
);

CREATE TABLE pendenze
(
	id_dominio BIGINT NOT NULL,
	id_pendenza VARCHAR(35) NOT NULL,
	id_tipo_pendenza BIGINT NOT NULL,
	numero_rata INT NOT NULL,
	importo NUMERIC(19,2) NOT NULL,
	numero_avviso VARCHAR(18) NOT NULL,
	iuv VARCHAR(35) NOT NULL,
	stato VARCHAR(35) NOT NULL,
	data_pagamento DATE,
	data_caricamento DATE NOT NULL,
	data_validita DATE,
	data_scadenza_avviso DATE,
	data_ultima_modifica_aca TIMESTAMP,
	data_ultima_comunicazione_aca TIMESTAMP,
	data_creazione TIMESTAMP NOT NULL,
	data_ultimo_aggiornamento TIMESTAMP NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_pendenze') NOT NULL,
	id_opzione_pagamento BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_pendenze_numero_avviso UNIQUE (id_dominio, numero_avviso),
	CONSTRAINT unique_pendenze_iuv UNIQUE (id_dominio, iuv),
	-- fk/pk keys constraints
	CONSTRAINT fk_pnd_id_opzione_pagamento FOREIGN KEY (id_opzione_pagamento) REFERENCES opzioni_pagamento(id),
	CONSTRAINT pk_pendenze PRIMARY KEY (id)
);

CREATE TABLE voci_pendenza
(
	id_voce_pendenza VARCHAR(35) NOT NULL,
	importo NUMERIC(19,2) NOT NULL,
	descrizione VARCHAR(140) NOT NULL,
	indice INT NOT NULL,
	stato VARCHAR(35) NOT NULL,
	id_dominio BIGINT,
	tipo_riferimento VARCHAR(35) NOT NULL,
	cod_entrata VARCHAR(35),
	iban_accredito VARCHAR(35),
	iban_appoggio VARCHAR(35),
	tassonomia VARCHAR(35),
	tipo_bollo VARCHAR(2),
	hash_documento VARCHAR(72),
	provincia_residenza VARCHAR(2),
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_voci_pendenza') NOT NULL,
	id_pendenza BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_voci_pendenza_1 UNIQUE (id_pendenza, indice),
	-- fk/pk keys constraints
	CONSTRAINT fk_vcp_id_pendenza FOREIGN KEY (id_pendenza) REFERENCES pendenze(id),
	CONSTRAINT pk_voci_pendenza PRIMARY KEY (id)
);
