<!-- Anything larger than a small fix needs an agreed issue first: see CONTRIBUTING.md. -->

## What this changes, and why

<!-- Link the issue, for example "Closes #123". -->

## How it was tested

<!-- The box or emulator and its Android version, and the Jellyfin version: a test server, never someone else's.
     For a change you can see: the themes you checked it in.
     For translations: the language, and whether you are a native speaker. -->

## Checklist

- [ ] `.\gradlew.bat testDebugUnitTest :app:testNixieDebugUnitTest detekt lintDebug lintNixieDebug :app:assembleNixieDebug` passes
- [ ] Navigated the change with a D-pad (remote or emulator), not a mouse
- [ ] Checked against the design rules in `docs/DESIGN-RULES.md`
- [ ] A change to a Glacier file is a small hook marked `// Nixie:`, with its row in `DIVERGENCE.md`
- [ ] New files have their REUSE entry (`REUSE.toml`)
- [ ] No server address, user name or token in the code, tests, screenshots or logs
