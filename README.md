# DrinkWatch Android

Companion app for the DrinkWatch API (https://cans.hazzardous.online): prices of
energy drinks near you, plus crowd price capture, basket and budget planning.

Download: https://cans.hazzardous.online/app

## What it does

* **Prices** - cheapest can of each tracked drink nearby, with per-can price, deal
  badge, store distance and how old the price is. Tap a row for every store's
  price, "Navigate" (opens Maps) and "Alert me" (notify under a threshold).
* **Add** - three ways to contribute:
  * scan a barcode (Play Services code scanner) or type the digits; unknown codes
    open a "new can" form (name/brand/size) that queues a product for review;
  * photograph a shelf price tag: ML Kit reads the text on device, the app picks
    the sell price (ignoring "was/save" and unit prices), and the photo is attached
    as evidence (EXIF/GPS stripped server-side);
  * type it in manually.
* **Basket** - add drinks and quantities, compare against pack sizes and multibuys
  at every nearby store, see the split-across-stores total, then plan the drive
  with a FuelWatch-derived fuel cost and a "worth it?" verdict.
* **Budget** - "max caffeine for my money" (single store or multi-store), with the
  400 mg/day guideline warning.
* **You** - caffeine log for today, price alerts, notifications, leaderboard-ish
  contribution history.
* **Widget** - home-screen widget with the four cheapest tracked drinks, refreshed
  every 30 minutes by WorkManager, plus background polling that turns new price
  alerts into phone notifications.

## Architecture

    data/
      Api.kt          OkHttp + org.json client for every endpoint (no codegen)
      Models.kt       immutable models parsed from JSON
      Prefs.kt        EncryptedSharedPreferences: device token, home location,
                      radius, tracked drinks, widget cache
      BasketStorage.kt  basket persisted as "slug:qty"
      Geo.kt          LocationManager helper (no Play Services needed) + Maps intent
      Images.kt       decode/rotate/downscale camera output for OCR
      Ocr.kt          ML Kit text recognition + shelf-price heuristic
    ui/
      Nav.kt          bottom-tab NavHost
      screens/        Home, Add, Basket, Budget, You, Settings
      components/     shared cards, chips, pickers
      theme/          Material 3 palette
    widget/
      PriceWidget.kt  Glance widget
      WidgetRefresh.kt  WorkManager periodic refresh + notification polling

Design choices worth knowing:

* **org.json over kotlinx.serialization** - no compiler plugin, no codegen, and the
  payloads are small. Parsing lives in `Api.kt` next to each endpoint.
* **No CameraX** - scanning uses the Play Services code scanner and price tags use
  the system camera app + `TakePicture`. Far less code than a camera pipeline, and
  full-resolution photos for OCR.
* **LocationManager over FusedLocationProvider** - works without Play Services.
* **Every screen owns its state** with `remember`/`LaunchedEffect`; there are no
  ViewModels yet, which keeps the app small but means cancellation is coarse.

## Building

Requires JDK 17 and an Android SDK with platform 35. On powermax everything is
installed under `~/android-build`:

    ./scripts/build.sh assembleDebug      # debug APK
    ./scripts/build.sh assembleRelease    # signed with keystore.properties
    ./scripts/build.sh testDebugUnitTest  # JVM unit tests (price/OCR parsing)

`keystore.properties` (gitignored) points at `keystore/drinkwatch.jks`. Losing that
key means future APKs cannot update an installed app, so it is backed up to the
redmax disk. Version bumps go in `app/build.gradle.kts`.

## Distribution

Sideloading: build, copy to `~/drinkwatch/apk/drinkwatch.apk` on powermax, bump
`~/drinkwatch/apk/version.json`, and the API serves it at `/app/drinkwatch.apk`.
Play Store would need a listing, data-safety declaration (location + user-submitted
prices) and review; nothing in the code blocks that, but the app is not ready for
commercial distribution while it depends on scraped supermarket prices.

## Not built yet

* Wear OS tile + complication (the API payload is already the right shape).
* Android Auto: not possible - Google's car app categories (navigation, POI, IOT,
  messaging, calling, weather, media, parked video/games/browsers) do not include
  price comparison, so review would reject it.
* Receipt OCR flow (ML Kit text on a docket + `POST /v1/receipts/parse`), which is
  the fastest way to add many prices at once.
* Offline-first caching beyond the widget payload.
