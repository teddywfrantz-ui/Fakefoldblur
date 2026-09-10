Fold Blur Screenshot POC

1. Keep One UI Home as your default launcher.
2. Build this project in GitHub Actions using .github/workflows/build-apk.yml.
3. Install FoldBlurPOC.apk.
4. Open the app.
5. Select an OUTER screenshot taken from the cover-screen One UI Home.
6. Select an INNER screenshot taken from the unfolded One UI Home.
7. Allow Draw over other apps.
8. Tap START HINGE TRANSITION SERVICE.
9. Return to One UI Home and slowly fold/unfold.

This is a screenshot-driven proof-of-concept. It does not modify Samsung SystemUI and cannot force Samsung to wake the inactive physical panel earlier than the OS allows.
