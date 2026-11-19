ALTER TABLE sync_events
    ALTER COLUMN command_id TYPE TEXT USING command_id::text;
ALTER TABLE sync_conflicts
    ALTER COLUMN command_id TYPE TEXT USING command_id::text;
ALTER TABLE sync_events
    ADD CONSTRAINT sync_events_command_id_length CHECK (length(command_id) BETWEEN 1 AND 160);
ALTER TABLE sync_conflicts
    ADD CONSTRAINT sync_conflicts_command_id_length CHECK (length(command_id) BETWEEN 1 AND 160);
