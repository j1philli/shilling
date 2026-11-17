-- Initial cloud-peer setup for a fresh deployment.
-- Backup payloads are reachable only by the WebRTC worker, never the Data API.
CREATE SCHEMA private_backup;
REVOKE ALL ON SCHEMA private_backup FROM PUBLIC, anon, authenticated, service_role;

-- Provision its login/password separately through your deployment secret manager.
CREATE ROLE shilling_backup_worker NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
GRANT USAGE ON SCHEMA private_backup TO shilling_backup_worker;

-- Live cloud sync is a durable ordered stream; it has no snapshot retention deletion.
CREATE TABLE private_backup.cloud_entries (
    household text NOT NULL,
    id text NOT NULL CHECK (id ~ '^[A-Za-z0-9-]{16,64}$'),
    chunks integer NOT NULL CHECK (chunks BETWEEN 1 AND 4096),
    created bigint NOT NULL,
    complete integer NOT NULL DEFAULT 0 CHECK (complete IN (0,1)),
    mode text NOT NULL CHECK (mode IN ('END_TO_END','TRUSTED_CLOUD')),
    key_id text NOT NULL CHECK (length(key_id) <= 64),
    sequence bigint NOT NULL DEFAULT 0,
    kind text NOT NULL DEFAULT 'DELTA' CHECK (kind IN ('DELTA','RECEIPT','CHECKPOINT')),
    refs text NOT NULL DEFAULT '[]',
    base_sequence bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (household,id),
    CHECK ((complete=0 AND sequence=0) OR (complete=1 AND ((kind='RECEIPT' AND sequence=0) OR (kind<>'RECEIPT' AND sequence>0))))
);
CREATE UNIQUE INDEX cloud_cursor ON private_backup.cloud_entries(household,sequence) WHERE complete=1 AND kind='DELTA';
CREATE TABLE private_backup.cloud_chunks (
    household text NOT NULL,
    snapshot text NOT NULL,
    idx integer NOT NULL CHECK (idx BETWEEN 0 AND 4095),
    ciphertext bytea NOT NULL CHECK (octet_length(ciphertext) BETWEEN 1 AND 36864),
    PRIMARY KEY (household,snapshot,idx),
    FOREIGN KEY (household,snapshot) REFERENCES private_backup.cloud_entries(household,id) ON DELETE CASCADE
);
REVOKE ALL ON private_backup.cloud_entries, private_backup.cloud_chunks FROM PUBLIC,anon,authenticated,service_role;
GRANT SELECT,INSERT,UPDATE,DELETE ON private_backup.cloud_entries,private_backup.cloud_chunks TO shilling_backup_worker;
ALTER TABLE private_backup.cloud_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE private_backup.cloud_chunks ENABLE ROW LEVEL SECURITY;
CREATE POLICY worker_cloud_entries ON private_backup.cloud_entries TO shilling_backup_worker USING(true) WITH CHECK(true);
CREATE POLICY worker_cloud_chunks ON private_backup.cloud_chunks TO shilling_backup_worker USING(true) WITH CHECK(true);

CREATE TABLE private_backup.cloud_gc (household text NOT NULL, id text NOT NULL, PRIMARY KEY(household,id));
REVOKE ALL ON private_backup.cloud_gc FROM PUBLIC,anon,authenticated,service_role;
GRANT SELECT,INSERT,DELETE ON private_backup.cloud_gc TO shilling_backup_worker;
ALTER TABLE private_backup.cloud_gc ENABLE ROW LEVEL SECURITY;
CREATE POLICY worker_cloud_gc ON private_backup.cloud_gc TO shilling_backup_worker USING(true) WITH CHECK(true);

-- Separate control-plane credentials cannot access sync contents.
CREATE ROLE shilling_cloud_control NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
GRANT USAGE ON SCHEMA private_backup TO shilling_cloud_control;
CREATE TABLE private_backup.cloud_policies (household text PRIMARY KEY, worker text, enabled boolean NOT NULL, policy text NOT NULL);
CREATE INDEX cloud_worker_assignments ON private_backup.cloud_policies(worker) WHERE enabled=true;
REVOKE ALL ON private_backup.cloud_policies FROM PUBLIC,anon,authenticated,service_role,shilling_backup_worker;
GRANT SELECT,INSERT,UPDATE ON private_backup.cloud_policies TO shilling_cloud_control;
ALTER TABLE private_backup.cloud_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY control_policies ON private_backup.cloud_policies TO shilling_cloud_control USING(true) WITH CHECK(true);

-- Encrypted MCP write receipts survive stream compaction and make retries idempotent.
CREATE TABLE private_backup.cloud_commands (
    household text NOT NULL, id text NOT NULL, expires bigint NOT NULL,
    ciphertext bytea NOT NULL CHECK (octet_length(ciphertext) <= 131072),
    PRIMARY KEY(household,id)
);
REVOKE ALL ON private_backup.cloud_commands FROM PUBLIC,anon,authenticated,service_role,shilling_cloud_control;
GRANT SELECT,INSERT,DELETE ON private_backup.cloud_commands TO shilling_backup_worker;
ALTER TABLE private_backup.cloud_commands ENABLE ROW LEVEL SECURITY;
CREATE POLICY worker_cloud_commands ON private_backup.cloud_commands TO shilling_backup_worker USING(true) WITH CHECK(true);
