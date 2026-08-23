CREATE TABLE members (
  id UUID PRIMARY KEY,
  google_subject VARCHAR(128) NOT NULL UNIQUE,
  email VARCHAR(320) NOT NULL,
  nickname VARCHAR(40) NOT NULL,
  avatar_url TEXT,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  last_login_at TIMESTAMPTZ NOT NULL
);
