-- Keep branch metadata edits attributable without rewriting earlier audit events.
ALTER TABLE business_branch_audit
    ADD COLUMN IF NOT EXISTS details JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE business_branch_audit
    DROP CONSTRAINT IF EXISTS business_branch_audit_action_check;
ALTER TABLE business_branch_audit
    ADD CONSTRAINT business_branch_audit_action_check
    CHECK (action IN ('branch_created','branch_renamed','device_registered','device_revoked'));
ALTER TABLE business_branch_audit
    ADD CONSTRAINT business_branch_audit_details_object
    CHECK (jsonb_typeof(details) = 'object');
