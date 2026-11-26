-- Complete owner-managed branch device lifecycle: device enrollment is
-- explicit and represented in the append-only branch/device audit history.
ALTER TABLE business_branch_audit
    DROP CONSTRAINT IF EXISTS business_branch_audit_action_check;
ALTER TABLE business_branch_audit
    ADD CONSTRAINT business_branch_audit_action_check
    CHECK (action IN ('branch_created','device_registered','device_revoked'));
