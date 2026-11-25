ALTER TABLE school
  ADD COLUMN IF NOT EXISTS default_student_password VARCHAR(64);
