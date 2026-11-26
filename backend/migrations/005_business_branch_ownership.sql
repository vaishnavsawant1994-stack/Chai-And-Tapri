-- Give a business an explicit owner principal for safe multi-shop discovery.
-- Existing owner shop memberships are backfilled because registration is the
-- only current path that creates a business, and it grants that user owner.
CREATE TABLE IF NOT EXISTS business_memberships (
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    user_id UUID NOT NULL REFERENCES users(user_id),
    role TEXT NOT NULL CHECK (role = 'owner'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_id, user_id)
);

INSERT INTO business_memberships(business_id,user_id,role,created_at)
SELECT business_id,user_id,'owner',min(created_at)
FROM shop_memberships
WHERE role='owner'
GROUP BY business_id,user_id
ON CONFLICT (business_id,user_id) DO NOTHING;

ALTER TABLE business_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE business_memberships FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS business_memberships_actor_scope ON business_memberships;
CREATE POLICY business_memberships_actor_scope ON business_memberships
    USING (
        business_id = nullif(current_setting('app.current_business', true), '')::uuid
        AND user_id = nullif(current_setting('app.current_user', true), '')::uuid
    )
    WITH CHECK (
        business_id = nullif(current_setting('app.current_business', true), '')::uuid
        AND user_id = nullif(current_setting('app.current_user', true), '')::uuid
    );

CREATE TABLE IF NOT EXISTS business_branch_audit (
    branch_audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES businesses(business_id),
    shop_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(user_id),
    action TEXT NOT NULL CHECK (action = 'branch_created'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (business_id,shop_id) REFERENCES shops(business_id,shop_id)
);
CREATE INDEX IF NOT EXISTS business_branch_audit_business_idx
    ON business_branch_audit(business_id,created_at DESC,branch_audit_id DESC);

ALTER TABLE business_branch_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE business_branch_audit FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS business_branch_audit_actor_scope ON business_branch_audit;
CREATE POLICY business_branch_audit_actor_scope ON business_branch_audit
    USING (
        business_id = nullif(current_setting('app.current_business', true), '')::uuid
        AND actor_user_id = nullif(current_setting('app.current_user', true), '')::uuid
    )
    WITH CHECK (
        business_id = nullif(current_setting('app.current_business', true), '')::uuid
        AND actor_user_id = nullif(current_setting('app.current_user', true), '')::uuid
    );
