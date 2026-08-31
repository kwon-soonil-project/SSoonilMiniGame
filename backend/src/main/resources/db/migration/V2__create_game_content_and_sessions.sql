CREATE TABLE content_packs (
  id UUID PRIMARY KEY,
  code VARCHAR(40) NOT NULL UNIQUE,
  game_type VARCHAR(20) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE content_items (
  id UUID PRIMARY KEY,
  pack_id UUID NOT NULL REFERENCES content_packs(id),
  value VARCHAR(120) NOT NULL,
  normalized_value VARCHAR(120) NOT NULL,
  aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT content_items_pack_normalized_value_key UNIQUE (pack_id, normalized_value)
);

CREATE TABLE game_sessions (
  id UUID PRIMARY KEY,
  room_id UUID NOT NULL,
  game_type VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  settings JSONB NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ,
  CONSTRAINT game_sessions_status_check CHECK (status IN ('RUNNING', 'COMPLETED', 'INTERRUPTED'))
);

CREATE TABLE game_participants (
  id UUID PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES game_sessions(id),
  actor_id UUID NOT NULL,
  nickname VARCHAR(40) NOT NULL,
  score INTEGER NOT NULL,
  rank INTEGER NOT NULL,
  rounds_played INTEGER NOT NULL,
  CONSTRAINT game_participants_session_actor_key UNIQUE (session_id, actor_id)
);
