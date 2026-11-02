# Phone Stats

A small phone monitor, in two versions: one for Android and one for iPhone.

## Android (`android/`)

Built for a Pixel 11 Pro XL. Open the `android` folder in Android Studio and press Run.

- **Phone:** RAM, per-core CPU speeds, battery (temperature, voltage, current), heat and throttling, storage, system info
- **Cell:** each SIM's carrier, 5G/LTE, band, signal strength (RSRP/RSRQ/SINR) and nearby towers
- **Wi-Fi:** every nearby access point with signal, band, channel, width and Wi-Fi generation
- **Survey:** record named spots (such as "3rd floor, east corner"), list them weakest first, export as CSV
- **Log:** commute logger that checks every 2 seconds whether data actually works; runs in the background with a notification and stops itself after 30 minutes

Needs Location permission for the Cell, Wi-Fi, Survey and background Log features. That is Android's rule for cell-tower and Wi-Fi data.

## iPhone (`ios/`)

Built for an iPhone 17 Pro Max. Open `ios/PhoneStats.xcodeproj` in Xcode, set your signing team, and press Run.

- **Stats:** RAM, per-core CPU use, battery, heat, storage, network type for each SIM line, system info
- **Commute Log:** the same data check as Android, while the app is on screen

iOS doesn't let apps read cell signal strength, bands, towers or nearby Wi-Fi networks, so those features are Android-only.

## Privacy

Everything stays on the phone. Nothing is sent anywhere, except a tiny test request to `www.gstatic.com/generate_204` while the commute logger is running, to check whether data works.
