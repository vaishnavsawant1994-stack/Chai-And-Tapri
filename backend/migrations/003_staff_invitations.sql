CREATE TABLE IF NOT EXISTS staff_invitations (
    invitation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    shop_id UUID NOT NULL,
    email TEXT NOT NULL CHECK (length(email) BETWEEN 3 AND 254),
    role TEXT NOT NULL CHECK (role IN ('manager', 'cashier')),
    token_hash BYTEA NOT NULL UNIQUE,
    invited_by UUID NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    accepted_user_id UUID REFERENCES users(user_id),
    FOREIGN KEY (business_id, shop_id) REFERENCES shops(business_id, shop_id),
    CHECK (expires_at > created_at),
    CHECK (NOT (revoked_at IS NOT NULL AND accepted_at IS NOT NULL)),
    CHECK ((accepted_at IS NULL) = (accepted_user_id IS NULL))
);

CREATE INDEX IF NOT EXISTS staff_invitations_shop_pending_idx
    ON staff_invitations(shop_id, created_at DESC)
    WHERE revoked_at IS NULL AND accepted_at IS NULL;

CREATE TABLE IF NOT EXISTS shop_staff_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    shop_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(user_id),
    target_user_id UUID REFERENCES users(user_id),
    invitation_id UUID REFERENCES staff_invitations(invitation_id),
    action TEXT NOT NULL CHECK (action IN ('invitation_created','invitation_revoked','invitation_accepted','role_changed','membership_removed')),
    previous_role TEXT CHECK (previous_role IS NULL OR previous_role IN ('owner','manager','cashier')),
    next_role TEXT CHECK (next_role IS NULL OR next_role IN ('owner','manager','cashier')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (business_id, shop_id) REFERENCES shops(business_id, shop_id),
    CHECK (target_user_id IS NOT NULL OR invitation_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS shop_staff_audit_shop_time_idx ON shop_staff_audit(shop_id, occurred_at DESC);

ALTER TABLE staff_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_invitations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS staff_invitations_shop_scope ON staff_invitations;
CREATE POLICY staff_invitations_shop_scope ON staff_invitations
    USING (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid)
    WITH CHECK (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid);
DROP POLICY IF EXISTS staff_invitations_token_lookup ON staff_invitations;
CREATE POLICY staff_invitations_token_lookup ON staff_invitations FOR SELECT
    USING (token_hash = decode(nullif(current_setting('app.staff_invitation_hash', true), ''), 'hex'));

ALTER TABLE shop_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_memberships FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS shop_memberships_shop_scope ON shop_memberships;
CREATE POLICY shop_memberships_shop_scope ON shop_memberships
    USING (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid)
    WITH CHECK (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid);

ALTER TABLE shop_staff_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE shop_staff_audit FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS shop_staff_audit_shop_scope ON shop_staff_audit;
CREATE POLICY shop_staff_audit_shop_scope ON shop_staff_audit
    USING (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid)
    WITH CHECK (shop_id = nullif(current_setting('app.current_shop', true), '')::uuid);

