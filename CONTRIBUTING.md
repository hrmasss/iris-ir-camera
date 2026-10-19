# Contributing to Iris

Thanks for helping. Iris is a small hobby project, so the process is light.

## Reporting a bug

Open an issue with the bug template. The most useful details:

- Phone model and ROM, with the Android version (for example "Poco F1, LineageOS 22, Android 15")
- The camera list Iris shows: open it with the list button at the top of the camera screen
- What you expected and what happened, with a screenshot if you can

Reports from ROMs where the IR camera is not found are especially welcome.

## Changing code

1. Fork the repo and create a branch from `main`.
2. Build with `./gradlew assembleDebug` and try your change on a device or emulator.
   The emulator has no IR sensor; the camera list and the normal cameras still work there.
3. Keep the app free of network access, ads and analytics. Pull requests that add any of these
   will not be merged.
4. Open a pull request that says what changed and how you tested it.

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
`feat:`, `fix:`, `docs:`, `build:`, `ci:`, `chore:`, `refactor:`.

## Code style

Kotlin with the official style, Jetpack Compose for UI. Match the code around your change.

## Licence

By contributing you agree that your work is released under the [Apache-2.0](LICENSE) licence.
