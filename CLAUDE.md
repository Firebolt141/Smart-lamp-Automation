# Claude Code — Project Preferences

## Git & GitHub workflow

**Handle everything autonomously — no confirmation needed:**

- Push branches to remote as soon as changes are committed
- Create a PR immediately when a feature branch is ready
- Merge the PR to `main` without waiting for input (use squash merge by default)
- Once merged to `main`, the GitHub Actions workflow auto-builds and releases the APK

This applies to this project, all past projects, and all future projects created in any session.

## Project: Smart-lamp-Automation

Android BLE controller app for LampSmart Pro ceiling lamps.

- App code lives in `lampsmart-controller/`
- GitHub Actions workflow at `.github/workflows/build.yml` (repo root) fires on every push to `main`
- Each merge to `main` auto-increments the version and publishes a new GitHub Release with the APK
- Feature branches follow the pattern `claude/<slug>`
