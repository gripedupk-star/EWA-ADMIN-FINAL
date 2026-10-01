# EWA Admin v0.7.2

- Firebase/FCM completely removed.
- No google-services.json or Firebase repository secret required.
- When the app is open, it syncs EWA activity immediately on resume and every 60 seconds thereafter.
- New activity can generate local Android notifications and refresh the currently visible section.
- Uses the existing EWA Core `/admin/app/activity` endpoint.
- Background/closed-app instant push is intentionally not provided in this Firebase-free build.
