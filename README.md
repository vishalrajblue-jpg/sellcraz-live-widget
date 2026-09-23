# SellCraz Live: floating bid widget (Android demo)

A small companion app. Buyer opens Instagram Live, and a SellCraz bid card
floats on top. Hold the button to bid. Bids go through the same `place_bid`
function as the web app, so the auction rules, anti-snipe and rate limits all
still apply.

Plain Android + Kotlin, no third-party libraries. Built in the cloud by
GitHub Actions, so nothing needs installing on your PC.

## One-time setup (about 10 minutes)

1. On github.com create a new **private** repo called `sellcraz-live-widget`.
   Keep it separate from the main `sellcraz` repo so Vercel never rebuilds for it.
2. Unzip this folder on your PC. Open the repo page, click **Add file > Upload files**,
   and drag in everything inside the folder, including the `.github` folder.
   Commit to `main`.
   If `.github` doesn't upload: **Add file > Create new file**, name it
   `.github/workflows/build.yml`, and paste the contents of that file.
3. Optional but nicer for demo phones: repo **Settings > Secrets and variables > Actions**,
   add `SUPABASE_ANON_KEY` (the same value as `NEXT_PUBLIC_SUPABASE_ANON_KEY` in Vercel).
   Skip this and you paste the key into the app once instead.
4. Open the **Actions** tab. The build starts on every upload and takes about 4 minutes.
   Open the finished run, download **sellcraz-live-apk**, unzip it, and you have `app-debug.apk`.

## Install on each demo phone

1. Send `app-debug.apk` to the phone (WhatsApp to yourself or Drive), tap it, and allow
   "install unknown apps" when asked.
2. Open **SellCraz Live**, sign in with a **buyer** account. Not the seller's account,
   because `place_bid` blocks self-bidding.
3. Paste a lot link or lot ID, tap **Test load**. The screen shows what the app
   read (title, current bid, end time). If a field says "none found", screenshot
   the raw row and send it to Claude.
4. Tap **Start floating widget**. The first time, Android opens the
   "Display over other apps" screen: switch it on, come back, tap Start again.
5. Tap **Open Instagram**. The card floats on top. Drag it by the "LIVE" header,
   tap – to shrink it to a bubble, tap the bubble to open it again.

Each new cloud build is signed with a fresh debug key, so **uninstall the old
version before installing a new build**.

## Demo script

- Laptop: SellCraz seller dashboard, lot running.
- Phone A and Phone B (ideally different brands): Instagram Live playing, widget on top,
  each signed in as a different buyer.
- A bids, B's card flips to the new price within a second and A sees "You're winning".
  B bids back, A sees "You've been outbid". The laptop shows both bids arrive.

## If bids fail

Run this in the Supabase SQL editor. Read-only, it changes nothing:

```sql
select string_agg(x, chr(10)) from (
  select 'FN ' || p.proname || '(' || pg_get_function_arguments(p.oid) || ')' as x
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.proname = 'place_bid'
  union all
  select 'COL ' || column_name || ' ' || data_type
  from information_schema.columns
  where table_schema = 'public' and table_name = 'lots'
) t;
```

Send the output to Claude. Parameter names can then be fixed in the app's
**Advanced** section without a rebuild. The error shown on the card usually
names the problem already (for example "Could not find the function
place_bid(p_amount, p_lot_id)" means the parameter names differ).

## What's in the widget

- Hold-to-bid (0.65s), never a single tap. The timer, not the animation, fires
  the bid, so it still needs a real hold with phone animations switched off.
- The amount bid is the one on screen when the hold started. If someone outbid
  in between, the server rejects it.
- Bidding is disabled if no fresh update for 4 seconds; the card turns amber.
- Countdown runs on the server's clock, not the phone's.
- Polls the lot once a second. Realtime subscriptions can replace this later.
