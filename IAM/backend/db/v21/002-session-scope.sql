-- Apply once through the controlled IAM migration process before this application version.
-- Existing sessions keep NULL (default scope); no account, credential or binding is rewritten.
ALTER TABLE iam.iam_session ADD COLUMN scope_selection_json TEXT;
COMMENT ON COLUMN iam.iam_session.scope_selection_json IS
  'Server-generated scope selection bound to this central session and user; never client SQL';
