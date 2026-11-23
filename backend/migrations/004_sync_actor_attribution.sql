-- The actor is the authenticated account submitting a sync event, not proof of
-- who authored the offline action on the device. Existing rows remain nullable.
ALTER TABLE sync_events
    ADD COLUMN IF NOT EXISTS actor_user_id UUID REFERENCES users(user_id),
    ADD COLUMN IF NOT EXISTS actor_role TEXT
        CHECK (actor_role IS NULL OR actor_role IN ('owner', 'manager', 'cashier'));

CREATE INDEX IF NOT EXISTS sync_events_actor_shop_revision_idx
    ON sync_events(shop_id, actor_user_id, server_revision DESC);
