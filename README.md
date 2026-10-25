# TV Pad (Android)

One small app. It runs a mini server inside your phone and shows the joystick. The smart TV opens the address shown at the top of the app and plays the game. Same Wi-Fi needed (or the phone's hotspot).

- No permissions beyond network, no ads, no extra libraries. Pure Java, APK of roughly 20-40 KB of code.
- `app/src/main/assets/tv.html` is the game (Orb Arena). Edit it to make your own game.
- `app/src/main/assets/controller.html` is the joystick screen.
- `app/src/main/java/tv/pad/Relay.java` is the server. `MainActivity.java` starts it and shows a full-screen WebView.

Build: open this folder in Android Studio and run, or use `gradle assembleDebug`.
