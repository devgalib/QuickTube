# Privacy

QuickTube has no accounts and no servers of its own. Your downloads, history, vault and settings stay on your device.

## What the official releases collect

Builds published on the [Releases page](https://github.com/devgalib/QuickTube/releases) include Google Firebase:

- **Crashlytics**: crash reports (stack trace, device model, Android version, app version) so we can fix crashes.
- **Analytics**: anonymous usage counts, for example "a download started" (video or audio, HTTP or HLS) or "a screen was opened".

We **never** send the URLs you visit or download, video titles, search terms, file names, site names or anything you type.

You can turn both off at any time in **Settings → Privacy**. Builds made from source without a Firebase configuration collect nothing.

## Network requests

To work, the app talks directly to the sites you use (YouTube, the pages you open in the browser, the media servers they point to)
and to GitHub to update the yt-dlp engine and to check for new app versions (this request only reveals your IP address to GitHub). These requests go from your device to those services; they are not routed through us.
