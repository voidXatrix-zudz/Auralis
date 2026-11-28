# AURALIS sync Worker

This Worker stores provider connection payloads encrypted in D1 and scopes every connection to the verified Clerk user. It currently supports the generic `youtube` and `spotify` provider names.

## Endpoints

All endpoints require a Clerk session JWT in `Authorization: Bearer <token>`.

- `GET /connections` returns the caller's decryptable provider connections.
- `PUT /connections/:provider` stores an encrypted connection for `youtube` or `spotify`.
- `DELETE /connections/:provider` removes one provider connection.
- `DELETE /account` removes all provider connections for the caller.

## Deploy

From this directory, run:

```sh
npm install
npx wrangler d1 create auralis-sync
```

Copy the returned database ID into `wrangler.toml` as `database_id`, then run:

```sh
npx wrangler d1 migrations apply DB --remote
npx wrangler deploy
npx wrangler secret put ENCRYPTION_KEY
```

Set `ENCRYPTION_KEY` to at least 32 characters. For example, generate a random value locally with `node -e "console.log(require('node:crypto').randomBytes(32).toString('base64url'))"`, then paste it into Wrangler's prompt. The value must not be committed or shared. Until it is set, requests return a generic server error.
