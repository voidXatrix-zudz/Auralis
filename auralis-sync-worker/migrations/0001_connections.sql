CREATE TABLE connections (
  user_id TEXT NOT NULL,
  provider TEXT NOT NULL,
  payload TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, provider)
);
