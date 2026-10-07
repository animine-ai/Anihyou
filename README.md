<p align="center">
  <img src="docs/kiyori-logo.svg" width="112" alt="Kiyori"/>
</p>

<h1 align="center">Kiyori</h1>

<p align="center">
  A calm, modern <a href="https://anilist.co">AniList</a> client for Android,<br/>
  with release times from signed extensions.
</p>

<p align="center">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-8b7cff?style=flat-square"/>
  <img alt="Android" src="https://img.shields.io/badge/Android-7.0%2B-8b7cff?style=flat-square"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-8b7cff?style=flat-square"/>
</p>

## What it is

Kiyori tracks your anime and manga on AniList and adds one thing the AniList schedule cannot give you: when an episode
really becomes available on a source you watch on, in which language, and how many episodes you are behind.

- **Everything AniList**: lists, search, seasons, charts, activity, reviews, notifications, statistics, widgets.
- **Calendar** in two styles, a list of days or tabs per weekday. Both read the same rows: the active source first, with
  SUB and DUB shown together when they release at the same time, and AniList entries for the rest.
- **Behind and watch next**: the number of episodes you have not seen, and one tap to the next one.
- **Extensions**: release sources are signed packages that run in a sandbox. You add a repository, accept the source
  once in a dialog that names what you are trusting, and install the extension. Kiyori matches the series of a source
  to AniList entries by itself and shows what it could not match, so you can assign it by hand.
- **Looks**: Material You or your own colors, a black theme for OLED, and a choice of app icons, among them a thin
  white one in the manner of an icon pack.

## App icons

*Settings, Display, App icon.* Kiyori (dark, the default), Kiyori Light, Arctic (no background, one thin white line,
made for dark wallpapers and themed icons) and the classic AniHyou icon. The launcher may need a moment to show the
new icon.

## Build

You need JDK 17 and the Android SDK with platform 37.

```sh
./gradlew :app:assembleFossDebug
```

Pushes are checked by the workflows in `.github/workflows`: the focused gate for tests and compilation, and a quick
workflow that builds an APK.

## Extensions

The extension format and the packages that exist live in their own repository,
[release-extentions](https://github.com/animine-ai/release-extentions). A package is signed, its permissions and
hosts are declared, and the app checks the signature and the declared limits before it runs anything. A source you
accept without an independent check stays marked as accepted by you.

## Credits and license

Kiyori is built on [AniHyou](https://github.com/axiel7/AniHyou-android) by [axiel7](https://github.com/axiel7) and
keeps its GPL-3.0 license: the code stays free, and changes you distribute stay free under the same terms. The upstream
project is the place to support the original author: [Ko-fi](https://ko-fi.com/axiel7).

Kiyori is not affiliated with AniList. Anime data and images belong to AniList and the rights holders.

See [LICENSE](LICENSE).
