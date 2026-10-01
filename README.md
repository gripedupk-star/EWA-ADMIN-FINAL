# EWA Admin v0.7.2

Firebase-free Android admin app for EWA.

## Live refresh
- On app resume: activity sync runs immediately.
- While app remains open: activity sync runs every 60 seconds.
- New EWA activity can show a local Android notification and refresh the visible section.
- Uses EWA Core `/wp-json/ewa/v1/admin/app/activity`.

## GitHub build
Upload the contents of this folder to the repository root, preserving `android-admin/` and `.github/`.
No Firebase project, `google-services.json`, or GitHub Firebase secret is required.
Run **Build EWA Admin APK** in GitHub Actions. The artifact is `EWA-Admin-v0.7.2-APK`.

## Important
This Firebase-free build does not guarantee instant notifications while Android has the app closed/backgrounded. The 60-second refresh is active while the app is open.
