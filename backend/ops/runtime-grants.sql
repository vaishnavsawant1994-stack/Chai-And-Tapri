-- Apply as the schema/database administrator after running migrations.
-- Create the LOGIN role separately with a secret supplied through the
-- administrator's managed credential process; never put that secret here.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'chai_tapri_api') THEN
    CREATE ROLE chai_tapri_api NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
END $$;

GRANT CONNECT ON DATABASE :"database_name" TO chai_tapri_api;
GRANT USAGE ON SCHEMA public TO chai_tapri_api;

GRANT SELECT, INSERT ON businesses, users, shops, shop_memberships, devices TO chai_tapri_api;
GRANT SELECT, INSERT ON business_memberships, business_branch_audit TO chai_tapri_api;
GRANT UPDATE (role), DELETE ON shop_memberships TO chai_tapri_api;
GRANT INSERT ON devices TO chai_tapri_api;
GRANT SELECT, INSERT, UPDATE ON staff_invitations TO chai_tapri_api;
GRANT SELECT, INSERT ON shop_staff_audit TO chai_tapri_api;
GRANT SELECT, INSERT, UPDATE ON sessions TO chai_tapri_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON auth_failures TO chai_tapri_api;
GRANT SELECT, INSERT ON sync_events TO chai_tapri_api;
GRANT SELECT, INSERT ON sync_conflicts TO chai_tapri_api;
GRANT UPDATE (server_revision,display_name) ON shops TO chai_tapri_api;
GRANT UPDATE (revoked_at) ON devices TO chai_tapri_api;

-- The application role never owns the tables. FORCE RLS policies on sync tables
-- still apply, and only the explicit shop in app.current_shop is visible.
