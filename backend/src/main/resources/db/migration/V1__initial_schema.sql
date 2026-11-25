CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE app_user (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), school_id UUID, email VARCHAR(320) UNIQUE,
 student_no VARCHAR(40), full_name VARCHAR(160) NOT NULL, password_hash TEXT NOT NULL,
 role VARCHAR(24) NOT NULL CHECK(role IN ('SYSTEM_ADMIN','SCHOOL_ADMIN','LECTURER','STUDENT')),
 status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(school_id,student_no)
);
CREATE TABLE school (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), code VARCHAR(32) UNIQUE NOT NULL, name VARCHAR(200) NOT NULL,
 admin_user_id UUID UNIQUE REFERENCES app_user(id), campus_lat DOUBLE PRECISION, campus_lng DOUBLE PRECISION,
 radius_m INTEGER NOT NULL DEFAULT 50 CHECK(radius_m BETWEEN 5 AND 1000), gps_accuracy_m INTEGER NOT NULL DEFAULT 30,
 max_deviation_m INTEGER NOT NULL DEFAULT 500, cluster_mad_m INTEGER NOT NULL DEFAULT 50,
 min_cluster_records INTEGER NOT NULL DEFAULT 3, review_hours INTEGER NOT NULL DEFAULT 24,
 allowed_cidrs CIDR[] NOT NULL DEFAULT '{}', timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE app_user ADD CONSTRAINT fk_user_school FOREIGN KEY(school_id) REFERENCES school(id);
CREATE TABLE course_class (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), school_id UUID NOT NULL REFERENCES school(id),
 lecturer_id UUID NOT NULL REFERENCES app_user(id), name VARCHAR(180) NOT NULL, code VARCHAR(40) NOT NULL,
 term VARCHAR(80), school_year VARCHAR(30), active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(school_id,code)
);
CREATE TABLE class_student (class_id UUID REFERENCES course_class(id), student_id UUID REFERENCES app_user(id), enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(class_id,student_id));
CREATE TABLE device_registry (student_id UUID PRIMARY KEY REFERENCES app_user(id), fingerprint_hash VARCHAR(128) NOT NULL, confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE attendance_session (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), class_id UUID NOT NULL REFERENCES course_class(id), lecturer_id UUID NOT NULL REFERENCES app_user(id),
 status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RECONCILING','FINALIZED')),
 center_lat DOUBLE PRECISION NOT NULL, center_lng DOUBLE PRECISION NOT NULL, center_source VARCHAR(20) NOT NULL CHECK(center_source IN ('MAP','PHONE_GPS')), center_accuracy_m DOUBLE PRECISION,
 radius_m INTEGER NOT NULL, gps_accuracy_m INTEGER NOT NULL, max_deviation_m INTEGER NOT NULL, cluster_mad_m INTEGER NOT NULL, min_cluster_records INTEGER NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(), ends_at TIMESTAMPTZ NOT NULL, closed_at TIMESTAMPTZ,
 reconcile_until TIMESTAMPTZ, finalized_at TIMESTAMPTZ, code_interval_seconds INTEGER NOT NULL DEFAULT 15, review_hours INTEGER NOT NULL DEFAULT 24,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_open_session_per_class ON attendance_session(class_id) WHERE status='OPEN';
CREATE TABLE session_code (
 session_id UUID PRIMARY KEY REFERENCES attendance_session(id) ON DELETE CASCADE,
 qr_hash VARCHAR(128) NOT NULL, pin_hash VARCHAR(128) NOT NULL, valid_from TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE attendance_record (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), session_id UUID NOT NULL REFERENCES attendance_session(id),
 student_id UUID NOT NULL REFERENCES app_user(id), result VARCHAR(24) NOT NULL CHECK(result IN ('PRESENT','REVIEW','PENDING','ABSENT')),
 flags TEXT[] NOT NULL DEFAULT '{}', latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, gps_accuracy_m DOUBLE PRECISION,
 distance_m DOUBLE PRECISION, client_ip INET, ip_in_school BOOLEAN, device_fingerprint_hash VARCHAR(128),
 message TEXT, checked_in_at TIMESTAMPTZ NOT NULL DEFAULT now(), reviewed_by UUID REFERENCES app_user(id), reviewed_at TIMESTAMPTZ,
 UNIQUE(session_id,student_id)
);
CREATE TABLE attendance_audit (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), session_id UUID REFERENCES attendance_session(id), record_id UUID REFERENCES attendance_record(id),
 actor_id UUID NOT NULL REFERENCES app_user(id), action VARCHAR(40) NOT NULL, before_value JSONB, after_value JSONB,
 reason TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE auth_token (token_hash VARCHAR(128) PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id), expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE pin_attempt_window (student_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE, window_started_at TIMESTAMPTZ NOT NULL, attempts INTEGER NOT NULL DEFAULT 0);
CREATE INDEX attendance_session_class_time ON attendance_session(class_id,started_at DESC);
CREATE INDEX attendance_record_session_result ON attendance_record(session_id,result);
