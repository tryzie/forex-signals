# Account setup (you do these; none of it needs code)

Never paste tokens or secrets into chat. Put them in `conf.xml` as `TYPE="CLEARTEXT"`.
The app encrypts them on its first start.

## 1. cTrader ID (cTID)
1. Go to **https://id.ctrader.com** and sign up with your email and a strong password.
   Turn on 2FA if it's offered.
2. Confirm your email.
   (If you start with step 2 below, Pepperstone may create a cTID for you during
   cTrader demo sign-up. If it does, use that one and skip this step.)

## 2. Pepperstone cTrader demo account
1. Go to **pepperstone.com** (Kenyan entity: Pepperstone Markets Kenya, CMA licence 128)
   and choose **Demo account**.
2. Choose these options:
   - Platform: **cTrader**
   - Account type: **Razor** (pending your confirmation in PLAN §17; otherwise Standard)
   - Currency: **USD**
   - Balance: **10,000**
   - Leverage: anything. The app sizes by risk, not leverage.
3. Log in at **https://app.ctrader.com** with your cTID and check that the demo account appears.
4. Write down the account **number** (shown in cTrader). The app finds the internal
   `ctidTraderAccountId` itself after authorisation.
5. Open each symbol in cTrader to check it exists: EURUSD, GBPUSD, USDJPY, AUDUSD, XAUUSD, XAGUSD.
   **Search for USDX** and tell me the exact name shown, or whether it's missing.
   Demo accounts can expire after a period of inactivity. Log in occasionally.

## 3. cTrader Open API application
1. Go to **https://openapi.ctrader.com** and log in with the same cTID.
2. **Applications → Add new app.**
   - Name: e.g. "Personal Signal Monitor".
   - Description: explain that it's a personal, read-only app that streams market data
     and reads account info for a demo account, for a signal and research dashboard,
     and that it places no orders.
   - Redirect URI: `http://localhost:8765/oauth/callback` (the app runs a one-time local
     listener for this).
3. Submit. **Spotware reviews new apps by hand, and approval can take days.** We'll build
   on Deriv and synthetic data in the meantime.
4. Once it's approved, copy the **Client ID** and **Client Secret** into `conf.xml` (CLEARTEXT).
5. Getting a token: the quickest way is **Applications → Playground**.
   - Choose the scope **`accounts`** (read-only). **Never choose `trading`.**
   - Click **Get token**, log in, and allow access to the demo account.
   - Copy the **access token** and **refresh token** into `conf.xml` (CLEARTEXT).
   - The access token lasts about 30 days. The app refreshes it on its own and saves the new one encrypted.

## 4. Telegram bot
1. In Telegram, open **@BotFather**. Make sure it has the verified blue tick.
2. Send `/newbot`. Give it a display name (e.g. "FX Signals Demo") and a username ending in `bot`.
3. BotFather replies with an **HTTP API token** that looks like `123456789:AA...`. That's the secret.
4. Optional hardening in BotFather:
   - `/setjoingroups` → Disable
   - `/setprivacy` → Enable
5. Open your new bot and press **Start**, then send it any message (e.g. "hi").
6. Find your chat ID. On the VM, run the command below. Type it yourself so the token
   doesn't end up in shell history files you share:
   ```
   read -s TOKEN; curl -s "https://api.telegram.org/bot$TOKEN/getUpdates" | grep -o '"chat":{"id":[0-9-]*'
   ```
   The number shown is your `chatId`. It isn't a secret, but don't publish it.
7. Put it in `conf.xml`:
   ```xml
   <telegram chatId="123456789"><botToken TYPE="CLEARTEXT">123456789:AA...</botToken></telegram>
   ```
8. On first start the app replaces `TYPE="CLEARTEXT"` with `TYPE="ENCRYPTED"` and an encrypted value.
   It only sends to, and only listens to, that one chat ID.

## 5. Deriv (fallback, works now: nothing to set up)
Market data comes from the public endpoint `wss://api.derivws.com/trading/v1/options/ws/public`, which
needs **no account, no token and no App ID** (checked 2026-11-19: symbols, ticks and history all work
without one). Leave `<deriv appId="">` empty.

Optional: if you want your own App ID (e.g. for higher limits later):
1. Log in or sign up at **https://developers.deriv.com**.
2. Dashboard → register a new application.
3. Copy the generated **App ID** into `~/.forexsignals/conf.xml` as `<deriv appId="...">`.
   It is not a secret. Legacy App IDs (such as 1089) do not work with the new API.

What Deriv can and cannot do for us (measured 2026-11-19):
- Live ticks carry bid, ask and a mid "quote". History (ticks and candles) is **quote only**, so Deriv
  candles are built on the quote, not the bid. Deriv data is tagged `deriv` and never mixed with cTrader.
- M1 history reaches back about **one year**, not three. The 3-year import needs cTrader.
- Deriv's FX week runs Monday 00:00 UTC to about Friday 20:55 UTC, not the broker week.
- USDSEK is not listed on Deriv.

## 6. VM access (do this now)
1. On the Mac, run `ssh-copy-id parallels@10.211.55.5`. It uses your existing `~/.ssh/id_ed25519.pub`.
2. Test with `ssh parallels@10.211.55.5` (it shouldn't ask for a password).
3. On the VM, run `passwd` to change the password. The old one was shared in chat.
4. Optional: set `PasswordAuthentication no` in `/etc/ssh/sshd_config.d/` and restart ssh.
