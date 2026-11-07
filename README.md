# Build.APP

Full Build.APP Android Studio source is supplied in `BuildAPP_Release.tar.xz`. The first GitHub Actions run expands the source into the repository, builds the Builder APK, and commits the expanded source tree.

Open **Actions → Expand source and build Build.APP** to follow the first build. When it succeeds, download the `BuildAPP-debug` artifact. The `build-generated.yml` workflow is then available for the Android app to build generated APK projects.

Do not store API keys or GitHub personal access tokens in this repository. Configure them locally in Build.APP Settings. Use a private repository for generated project inputs.
