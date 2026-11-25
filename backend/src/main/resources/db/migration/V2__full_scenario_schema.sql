ALTER TABLE app_user
  ADD COLUMN IF NOT EXISTS gender VARCHAR(16),
  ADD COLUMN IF NOT EXISTS date_of_birth DATE,
  ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS recovery_email VARCHAR(320);

UPDATE app_user SET email_verified = TRUE WHERE status = 'ACTIVE';
UPDATE app_user SET school_id = NULL WHERE role = 'LECTURER';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_user_status') THEN
    ALTER TABLE app_user ADD CONSTRAINT chk_user_status CHECK (status IN ('INACTIVE', 'ACTIVE', 'LOCKED'));
  END IF;
END $$;

ALTER TABLE school
  ADD COLUMN IF NOT EXISTS radius_min_m INTEGER NOT NULL DEFAULT 10,
  ADD COLUMN IF NOT EXISTS radius_max_m INTEGER NOT NULL DEFAULT 300,
  ADD COLUMN IF NOT EXISTS gps_accuracy_min_m INTEGER NOT NULL DEFAULT 5,
  ADD COLUMN IF NOT EXISTS gps_accuracy_max_m INTEGER NOT NULL DEFAULT 150,
  ADD COLUMN IF NOT EXISTS default_session_duration_s INTEGER NOT NULL DEFAULT 300,
  ADD COLUMN IF NOT EXISTS code_interval_seconds INTEGER NOT NULL DEFAULT 15,
  ADD COLUMN IF NOT EXISTS recovery_email VARCHAR(320);

UPDATE school s
SET recovery_email = u.email
FROM app_user u
WHERE u.id = s.admin_user_id AND s.recovery_email IS NULL;

CREATE TABLE IF NOT EXISTS activation_token (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  school_id UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
  token_hash VARCHAR(128) UNIQUE NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ,
  invalidated_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_activation_user ON activation_token(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS otp_token (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES app_user(id) ON DELETE CASCADE,
  email VARCHAR(320) NOT NULL,
  purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('LECTURER_REGISTER', 'PASSWORD_RESET')),
  code_hash VARCHAR(128) NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_otp_email_purpose ON otp_token(lower(email), purpose, created_at DESC);

CREATE TABLE IF NOT EXISTS school_lecturer_access (
  school_id UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
  lecturer_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED')),
  lock_reason TEXT,
  updated_by UUID REFERENCES app_user(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (school_id, lecturer_id)
);

CREATE TABLE IF NOT EXISTS identity_import_batch (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  school_id UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
  imported_by UUID NOT NULL REFERENCES app_user(id),
  batch_code VARCHAR(40) UNIQUE NOT NULL,
  total_rows INTEGER NOT NULL,
  created_count INTEGER NOT NULL,
  updated_count INTEGER NOT NULL,
  error_count INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS identity_import_row (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id UUID REFERENCES identity_import_batch(id) ON DELETE CASCADE,
  school_id UUID NOT NULL REFERENCES school(id) ON DELETE CASCADE,
  student_no VARCHAR(40) NOT NULL,
  full_name VARCHAR(160) NOT NULL,
  school_email VARCHAR(320) NOT NULL,
  date_of_birth DATE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_identity_row_school_student ON identity_import_row(school_id, student_no, created_at DESC);

INSERT INTO identity_import_row (school_id, student_no, full_name, school_email, date_of_birth)
SELECT u.school_id, u.student_no, u.full_name, u.email, u.date_of_birth
FROM app_user u
WHERE u.role = 'STUDENT' AND u.school_id IS NOT NULL AND u.student_no IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM identity_import_row r WHERE r.school_id = u.school_id AND r.student_no = u.student_no
  );

ALTER TABLE course_class
  ADD COLUMN IF NOT EXISTS static_qr_token VARCHAR(64);

UPDATE course_class
SET static_qr_token = encode(gen_random_bytes(16), 'hex')
WHERE static_qr_token IS NULL;

ALTER TABLE course_class
  ALTER COLUMN static_qr_token SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_course_class_static_qr') THEN
    ALTER TABLE course_class ADD CONSTRAINT uq_course_class_static_qr UNIQUE (static_qr_token);
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS class_whitelist (
  class_id UUID NOT NULL REFERENCES course_class(id) ON DELETE CASCADE,
  student_no VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (class_id, student_no)
);

INSERT INTO class_whitelist (class_id, student_no)
SELECT cs.class_id, u.student_no
FROM class_student cs
JOIN app_user u ON u.id = cs.student_id
WHERE u.student_no IS NOT NULL
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS session_center_probe (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  class_id UUID NOT NULL REFERENCES course_class(id) ON DELETE CASCADE,
  lecturer_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  probe_token VARCHAR(64) UNIQUE NOT NULL,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  accuracy_m DOUBLE PRECISION,
  captured_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE attendance_session
  ADD COLUMN IF NOT EXISTS original_center_lat DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS original_center_lng DOUBLE PRECISION,
  ADD COLUMN IF NOT EXISTS center_shifted BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS reconcile_outcome VARCHAR(32),
  ADD COLUMN IF NOT EXISTS reconcile_mad_m DOUBLE PRECISION;

CREATE TABLE IF NOT EXISTS export_template (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  lecturer_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  class_id UUID REFERENCES course_class(id) ON DELETE CASCADE,
  name VARCHAR(160) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  start_row INTEGER NOT NULL CHECK (start_row >= 1),
  mssv_col INTEGER NOT NULL CHECK (mssv_col >= 1),
  first_attendance_col INTEGER NOT NULL CHECK (first_attendance_col >= 1),
  present_symbol VARCHAR(16) NOT NULL,
  file_data BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS account_audit_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  school_id UUID REFERENCES school(id) ON DELETE CASCADE,
  target_user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
  actor_id UUID NOT NULL REFERENCES app_user(id),
  action VARCHAR(40) NOT NULL,
  reason TEXT,
  details JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dev_mail_outbox (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_email VARCHAR(320) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  body TEXT NOT NULL,
  delivered BOOLEAN NOT NULL DEFAULT FALSE,
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
