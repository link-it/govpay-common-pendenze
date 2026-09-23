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
--
-- Tutte le istruzioni sono IF NOT EXISTS: H2 riesegue lo script INIT su OGNI nuova
-- connessione alla stessa URL, non solo alla prima. Con AllocatoreBloccoProgressivoIuv
-- che apre una connessione dedicata (REQUIRES_NEW), una seconda connessione e' normale
-- durante i test; senza IF NOT EXISTS quella riesecuzione fallisce su "already exists" e
-- HikariCP passa 30s a ritentare la creazione della connessione prima di rinunciare.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS seq_posizioni_debitorie start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_soggetti_debitori start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_opzioni_pagamento start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_pendenze start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_voci_pendenza start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS posizioni_debitorie
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

CREATE TABLE IF NOT EXISTS soggetti_debitori
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

CREATE TABLE IF NOT EXISTS opzioni_pagamento
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

CREATE TABLE IF NOT EXISTS pendenze
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

CREATE TABLE IF NOT EXISTS voci_pendenza
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

-- ---------------------------------------------------------------------------
-- Tabella dei progressivi IUV: NON una tabella nuova di questa libreria, ma la
-- stessa tabella fisica ID_MESSAGGIO_RELATIVO gia' scritta in produzione dal
-- generatore IUV legacy (org.openspcoop2.utils.id.serial.IDSerialGenerator,
-- invocato da IuvBD con protocollo="GovPay"). Vedi ProgressivoIuv per l'analisi
-- completa di questa scelta.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS id_messaggio_relativo
(
	counter BIGINT NOT NULL,
	protocollo VARCHAR(255) NOT NULL,
	info_associata VARCHAR(255) NOT NULL,
	ora_registrazione TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
	CONSTRAINT pk_id_messaggio_relativo PRIMARY KEY (protocollo, info_associata)
);

-- ---------------------------------------------------------------------------
-- Anagrafica di govpay-common necessaria a GeneratoreIuvStandard: stesso DDL di
-- test di govpay-common (src/test/resources/schema.sql), non una definizione
-- indipendente. Tutte le tabelle di it.govpay.common.entity sono richieste
-- perche' @EntityScan(basePackageClasses = DominioEntity.class) in
-- PendenzeTestApplication scansiona l'intero package, non le singole classi —
-- incluso it.govpay.common.entity.batch (le tre tabelle standard di Spring
-- Batch qui sotto), che questa libreria non usa ma deve comunque validare.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS batch_job_instance (
    job_instance_id BIGINT PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    job_key VARCHAR(32) NOT NULL,
    version BIGINT
);

CREATE TABLE IF NOT EXISTS batch_job_execution (
    job_execution_id BIGINT PRIMARY KEY,
    job_instance_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(10),
    exit_message VARCHAR(2500),
    version BIGINT,
    exit_code VARCHAR(2500),
    last_updated TIMESTAMP,
    CONSTRAINT fk_batch_job_execution_instance FOREIGN KEY (job_instance_id) REFERENCES batch_job_instance(job_instance_id)
);

CREATE TABLE IF NOT EXISTS batch_job_execution_params (
    job_execution_id BIGINT NOT NULL,
    parameter_name VARCHAR(100) NOT NULL,
    parameter_type VARCHAR(100) NOT NULL,
    parameter_value VARCHAR(2500),
    identifying CHAR(1) NOT NULL,
    CONSTRAINT pk_batch_job_execution_params PRIMARY KEY (job_execution_id, parameter_name),
    CONSTRAINT fk_batch_job_execution_params_exec FOREIGN KEY (job_execution_id) REFERENCES batch_job_execution(job_execution_id)
);

CREATE TABLE IF NOT EXISTS connettori (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_connettore VARCHAR(255) NOT NULL,
    cod_proprieta VARCHAR(255) NOT NULL,
    valore VARCHAR(255) NOT NULL,
    CONSTRAINT uk_connettori UNIQUE (cod_connettore, cod_proprieta)
);

CREATE TABLE IF NOT EXISTS configurazione (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    nome VARCHAR(255) NOT NULL,
    -- ConfigurazioneEntity.valore usa @JdbcTypeCode(SqlTypes.LONGVARCHAR), non @Lob: con
    -- ddl-auto=validate un CLOB (usato invece dallo schema.sql di govpay-common, mai
    -- validato li' perche' i loro test usano create-drop) viene rifiutato.
    valore VARCHAR,
    CONSTRAINT uk_configurazione_nome UNIQUE (nome)
);

CREATE TABLE IF NOT EXISTS intermediari (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_intermediario VARCHAR(255) NOT NULL,
    cod_connettore_pdd VARCHAR(255),
    cod_connettore_recupero_rt VARCHAR(255),
    cod_connettore_aca VARCHAR(255),
    cod_connettore_gpd VARCHAR(255),
    cod_connettore_fr VARCHAR(255),
    cod_connettore_backoffice_ec VARCHAR(255),
    denominazione VARCHAR(255),
    principal VARCHAR(255),
    principal_originale VARCHAR(255),
    abilitato BOOLEAN NOT NULL,
    CONSTRAINT uk_intermediari_cod UNIQUE (cod_intermediario)
);

CREATE TABLE IF NOT EXISTS stazioni (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_stazione VARCHAR(255) NOT NULL,
    password VARCHAR(255),
    abilitato BOOLEAN NOT NULL,
    application_code INTEGER,
    versione VARCHAR(255),
    id_intermediario BIGINT NOT NULL,
    CONSTRAINT uk_stazioni_cod UNIQUE (cod_stazione),
    CONSTRAINT fk_stazioni_intermediario FOREIGN KEY (id_intermediario) REFERENCES intermediari(id)
);

CREATE TABLE IF NOT EXISTS applicazioni (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_applicazione VARCHAR(35) NOT NULL,
    auto_iuv BOOLEAN NOT NULL,
    firma_ricevuta VARCHAR(1) NOT NULL,
    trusted BOOLEAN NOT NULL,
    cod_connettore_integrazione VARCHAR(255),
    cod_applicazione_iuv VARCHAR(3),
    reg_exp VARCHAR(1024),
    CONSTRAINT uk_applicazioni_cod UNIQUE (cod_applicazione)
);

CREATE TABLE IF NOT EXISTS domini (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_dominio VARCHAR(35) NOT NULL,
    gln VARCHAR(35),
    abilitato BOOLEAN NOT NULL,
    ragione_sociale VARCHAR(70) NOT NULL,
    aux_digit INT NOT NULL DEFAULT 0,
    iuv_prefix VARCHAR(255),
    segregation_code INT,
    -- Mappata da DominioLogoEntity (proiezione della stessa tabella per il solo BLOB del
    -- logo), non da DominioEntity: serve comunque perche' e' nello stesso package scansionato.
    logo VARBINARY(255),
    cbill VARCHAR(255),
    aut_stampa_poste VARCHAR(255),
    cod_connettore_my_pivot VARCHAR(255),
    cod_connettore_secim VARCHAR(255),
    cod_connettore_gov_pay VARCHAR(255),
    cod_connettore_hyper_sic_apk VARCHAR(255),
    intermediato BOOLEAN NOT NULL,
    tassonomia_pago_pa VARCHAR(35),
    scarica_fr BOOLEAN NOT NULL,
    id_stazione BIGINT,
    id_applicazione_default BIGINT,
    CONSTRAINT uk_domini_cod UNIQUE (cod_dominio),
    CONSTRAINT fk_domini_stazione FOREIGN KEY (id_stazione) REFERENCES stazioni(id)
);
