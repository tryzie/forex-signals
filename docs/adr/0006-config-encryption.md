# ADR 0006: conf.xml with CLEARTEXT to ENCRYPTED rewrite

- Status: Accepted (Phase 0, 2026-11-18; implemented Phase 1)

## Decision
- Secrets live only in `~/.forexsignals/conf.xml` (gitignored, chmod 600). Elements carrying `TYPE` are
  secrets. On load, every non-empty `TYPE="CLEARTEXT"` value is encrypted with **AES-256-GCM** (fresh
  12-byte IV, stored as `base64(iv‖ciphertext‖tag)`). The file is rewritten atomically (600 temp file,
  then rename) with `TYPE="ENCRYPTED"`.
- The element path (e.g. `config/ctrader/clientSecret`) is bound as GCM additional data, so an encrypted
  value moved to another element fails to decrypt.
- Key: 32 random bytes at `~/.forexsignals/master.key` (or `$FOREXSIGNALS_KEY_FILE`), created 600 in a
  700 directory. The app refuses to start if the key is wider than 600 or owned by another user.
- DTDs and external entities are refused when parsing. Secret values never appear in logs or exceptions.
- Refreshed cTrader tokens are written back encrypted.

## Consequences
Losing the key means re-entering secrets as CLEARTEXT. Tests cover the round trip, a wrong key, a moved
value, permissions and the committed `conf.example.xml`.
