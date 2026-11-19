CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS businesses (
    business_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 2 AND 120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS shops (
    shop_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 2 AND 120),
    server_revision BIGINT NOT NULL DEFAULT 0 CHECK (server_revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, shop_id)
);

CREATE TABLE IF NOT EXISTS users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 2 AND 100),
    password_salt BYTEA NOT NULL,
    password_hash BYTEA NOT NULL,
    password_parameters JSONB NOT NULL,
    disabled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS shop_memberships (
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    shop_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(user_id),
    role TEXT NOT NULL CHECK (role IN ('owner', 'manager', 'cashier')),
    permissions JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_id, user_id),
    FOREIGN KEY (business_id, shop_id) REFERENCES shops(business_id, shop_id)
);

CREATE TABLE IF NOT EXISTS devices (
    device_id UUID NOT NULL,
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    shop_id UUID NOT NULL,
    label TEXT NOT NULL CHECK (length(label) BETWEEN 1 AND 80),
    registered_by UUID NOT NULL REFERENCES users(user_id),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_id, device_id),
    FOREIGN KEY (business_id, shop_id) REFERENCES shops(business_id, shop_id)
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash BYTEA PRIMARY KEY,
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    shop_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(user_id),
    device_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    FOREIGN KEY (business_id, shop_id) REFERENCES shops(business_id, shop_id),
    FOREIGN KEY (shop_id, device_id) REFERENCES devices(shop_id, device_id)
);
CREATE INDEX IF NOT EXISTS sessions_user_shop_idx ON sessions(user_id, shop_id, expires_at);

CREATE TABLE IF NOT EXISTS auth_failures (
    email_key TEXT PRIMARY KEY,
    failures INTEGER NOT NULL DEFAULT 0 CHECK (failures >= 0),
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS sync_conflicts (
    conflict_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    shop_id UUID NOT NULL,
    event_id UUID NOT NULL,
    command_id UUID NOT NULL,
    device_id UUID NOT NULL,
    expected_revision BIGINT NOT NULL,
    current_revision BIGINT NOT NULL,
    envelope JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    FOREIGN KEY (business_id, shop_id) REFERENCES shops(business_id, shop_id)
);
CREATE INDEX IF NOT EXISTS sync_conflicts_open_idx ON sync_conflicts(shop_id, created_at) WHERE resolved_at IS NULL;

CREATE TABLE IF NOT EXISTS sync_events (
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    shop_id UUID NOT NULL,
    server_revision BIGINT NOT NULL,
    event_id UUID NOT NULL,
    command_id UUID NOT NULL,
    device_id UUID NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type = 'business.command.v1'),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    base_revision BIGINT NOT NULL CHECK (base_revision >= 0),
    payload_hash BYTEA NOT NULL,
    envelope JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_id, event_id),
    UNIQUE (shop_id, command_id),
    UNIQUE (shop_id, server_revision),
    FOREIGN KEY (business_id, shop_id) REFERENCES shops(business_id, shop_id)
);
CREATE INDEX IF NOT EXISTS sync_events_shop_revision_idx ON sync_events(shop_id, server_revision);

-- App queries also verify membership. RLS is a second boundary against accidental
-- cross-shop reads/writes for synchronization data.
ALTER TABLE sync_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS sync_events_shop_scope ON sync_events;
CREATE POLICY sync_events_shop_scope ON sync_events
    USING (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid)
    WITH CHECK (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid);

ALTER TABLE sync_conflicts ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_conflicts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS sync_conflicts_shop_scope ON sync_conflicts;
CREATE POLICY sync_conflicts_shop_scope ON sync_conflicts
    USING (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid)
    WITH CHECK (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid);

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
