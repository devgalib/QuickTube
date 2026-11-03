# Our games on the home screen (Remote Config)

The Download tab's home page shows a row of our own games (where the music row used to be). **See all** opens a full list.
Tapping a game opens its Play Store page (the store shows *Open* when it's already installed).

The list lives in **Firebase Remote Config** (project `quicktube-app`), so games can be added, removed or reordered without
an app update.

## Parameters

All four sit in the **Promo games** group.

| Key | Type | Default | What it does |
| --- | --- | --- | --- |
| `qt_games` | JSON | – | The games (format below). |
| `qt_games_refresh_hours` | Number | `24` | How often the app fetches the list, in hours (e.g. `6`, `24`, `72`). |
| `qt_games_title` | String | `Our games` | Title of the row and of the See all screen. |
| `qt_games_enabled` | Boolean | `true` | `false` hides the row. |

### `qt_games` format

```json
{
  "games": [
    {
      "id": "arrow",
      "name": "Arrow Puzzle: Puzzle Escape",
      "package": "com.edureminder.arrow",
      "icon": "https://play-lh.googleusercontent.com/…=s256-rw",
      "banner": "https://play-lh.googleusercontent.com/…=w1052-h592-rw",
      "tagline": "Tap arrows to clear the maze!",
      "genre": "Puzzle",
      "url": "https://play.google.com/store/apps/details?id=com.edureminder.arrow",
      "enabled": true
    }
  ]
}
```

| Field | Required | Notes |
| --- | --- | --- |
| `id` | yes | Unique in the list (the same game can be listed twice with different ids). |
| `name` | yes | Shown under the logo (two lines at most on the home row). |
| `package` | yes | Play Store package id. |
| `icon` | yes | Square logo, 256–512 px. |
| `banner` | no | Wide image for the See all card (feature graphic or a landscape screenshot). The icon is used when missing. |
| `tagline` | no | One short line for the See all card. |
| `genre` | no | E.g. `Puzzle`, shown under the name. |
| `url` | no | Store link; defaults to `https://play.google.com/store/apps/details?id=<package>`. |
| `enabled` | no | `false` hides this entry without deleting it. |

Unknown fields are ignored, so new ones can be added later without breaking older app versions. Entries missing `name` or
`package` are skipped. If the whole value is empty or isn't valid JSON, the app keeps showing the last good list.

## How the app uses it

- **First visit:** fetches from Remote Config and saves the list on the phone (images are cached by the image loader).
- **Later visits:** shows the saved list straight away and does **not** fetch again until `qt_games_refresh_hours` have
  passed since the last successful fetch. So a change in the console reaches users within that interval.
- **Order:** rotates by one on every app start, so each game gets the first place in turn (A B C → B C A → C A B…).
- **No network / no Firebase** (forks build without `google-services.json`): the last saved list, or the one built-in game
  (`RemoteConfigGamesRepository.BUILT_IN`).

Code: `core/data/…/RemoteConfigGamesRepository.kt` (fetching, caching, rotation), `feature/home/…/Games.kt` (row, See all).

## Getting a game's images from the Play Store

Open the game's store page in a desktop browser, right-click the icon → *Copy image address*. The address ends in a size,
e.g. `=w240-h480-rw`; replace it with `=s256-rw` for the icon. For the banner, copy a landscape screenshot or the feature
graphic and end it with `=w1052-h592-rw`.

## Adding or changing a game

**In the console:** Firebase console → Remote Config → `qt_games` → edit the JSON → *Publish changes*.

**From this repo** (the whole template is in [remote-config/promo-games.template.json](remote-config/promo-games.template.json)):

1. Edit the `qt_games` value in the template (it's a JSON string; keep the escaping valid).
2. Publish it with the Firebase CLI from a folder holding this `firebase.json`:
   ```json
   { "remoteconfig": { "template": "promo-games.template.json" } }
   ```
   ```bash
   firebase deploy --only remoteconfig --project quicktube-app
   ```
   Deploying replaces the **whole** Remote Config template, so first run
   `firebase remoteconfig:get --project quicktube-app -o current.json` and copy over any parameters that aren't in this file.

To test a change at once on a phone, clear the app's data (or wait for the refresh interval).
