-- Archive branches without deleting any transactional or audit history.
ALTER TABLE shops ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;
ALTER TABLE business_branch_audit
    DROP CONSTRAINT IF EXISTS business_branch_audit_action_check;
ALTER TABLE business_branch_audit
    ADD CONSTRAINT business_branch_audit_action_check
    CHECK (action IN ('branch_created','branch_renamed','branch_archived','branch_restored','device_registered','device_revoked'));
