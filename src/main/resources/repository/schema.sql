-- Reference schema for the three PIC-SURE HPDS identity tables on AWS RDS Postgres.
--
-- This file documents the target shape and is used to initialize the Postgres
-- Testcontainer during integration tests. In production the schema is owned and
-- migrated externally (spring.sql.init.mode=never) -- this file does NOT run
-- against RDS at application startup.

-- Sequence shared by all three tables: every HPDS identity gets a unique integer.
CREATE SEQUENCE IF NOT EXISTS hpds_id_seq AS BIGINT;

-- Table 1: participants
-- Maps a generated HPDS id to potentially many origin ids.
-- Uniqueness of an origin id is scoped by its `source` (dbgap_id, a study id, etc).
CREATE TABLE IF NOT EXISTS participants (
    hpds_id   BIGINT NOT NULL DEFAULT nextval('hpds_id_seq'),
    source_id TEXT NOT NULL,
    source    TEXT NOT NULL,
    CONSTRAINT uq_participants_source_id_source UNIQUE (source_id, source)
);
CREATE INDEX IF NOT EXISTS ix_participants_hpds_id ON participants (hpds_id);

-- Table 2: consents
-- Maps an HPDS id to study_id/consent_code pairs.
-- A participant never belongs to more than one consent group within the same study,
-- so (hpds_id, study_id) is unique.
CREATE TABLE IF NOT EXISTS consents (
    hpds_id              BIGINT NOT NULL,
    study_id             TEXT NOT NULL,
    consent_code        TEXT NOT NULL,
    consent_abbreviation TEXT NOT NULL,
    CONSTRAINT uq_consents_hpds_id_study UNIQUE (hpds_id, study_id)
);
CREATE INDEX IF NOT EXISTS ix_consents_hpds_id ON consents (hpds_id);

-- Table 3: samples
-- Maps an HPDS id to potentially many source_sample_id/sample_source pairs.
CREATE TABLE IF NOT EXISTS samples (
    hpds_id          BIGINT NOT NULL,
    source_sample_id TEXT NOT NULL,
    sample_source    TEXT NOT NULL,
    CONSTRAINT uq_samples UNIQUE (hpds_id, source_sample_id, sample_source)
);
CREATE INDEX IF NOT EXISTS ix_samples_hpds_id ON samples (hpds_id);
