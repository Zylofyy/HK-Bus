# HK Bus

HK Bus is a native Android app for tracking Hong Kong franchised bus arrival times.

## Features

- Search KMB and Citybus routes.
- Save routes as bookmarks.
- Group saved routes with custom colors and ordering.
- Show the next three arrivals for the nearest stop using device location.
- Check GitHub Releases for new APK versions from inside the app.

## Data Sources

- KMB ETA API: `https://data.etabus.gov.hk`
- Citybus ETA API: `https://rt.data.gov.hk`

## Releases

Publish signed APKs as GitHub Release assets. The app checks `Zylofyy/HK-Bus` releases and downloads the first `.apk` asset from the latest release.

## Build

The project is a small native Android Gradle app. Local signing files are intentionally ignored by Git.
