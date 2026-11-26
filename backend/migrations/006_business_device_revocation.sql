-- Allow a business owner to retire a lost/retired branch device without
-- changing other devices or deleting any prior branch audit history.
ALTER TABLE business_branch_audit
    ADD COLUMN IF NOT EXISTS device_id UUID;

ALTER TABLE business_branch_audit
    DROP CONSTRAINT IF EXISTS business_branch_audit_action_check;
ALTER TABLE business_branch_audit
    ADD CONSTRAINT business_branch_audit_action_check
    CHECK (action IN ('branch_created','device_revoked'));

ALTER TABLE business_branch_audit
    ADD CONSTRAINT business_branch_audit_revoked_device_fk
    FOREIGN KEY (shop_id,device_id) REFERENCES devices(shop_id,device_id);

ALTER TABLE business_branch_audit
    ADD CONSTRAINT business_branch_audit_revoked_device_required
    CHECK (action <> 'device_revoked' OR device_id IS NOT NULL);
