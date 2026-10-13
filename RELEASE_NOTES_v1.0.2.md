# LAN Browser Relay 1.0.2

Hotfix for Android TV server startup when the preferred LAN listener ports are already occupied.

- Tries UI/content listener pairs 8080/8081 first, then falls back through 8110/8111.
- Displays the actual selected LAN URL so the UI and isolated content listener stay aligned.
- Collapses repeated bind failures into one diagnostic entry per startup attempt.
- Reloads the read-only TV preview after the server is back online.

The release workflow runs the browser security checks, unit tests, Android lint, and APK signature/version verification before publishing.
