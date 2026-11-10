# Project Conventions — Sonabud

## Language

- App UI: English by default, French through `res/values-fr`.
- Code, comments, commit messages and documentation: English.

## Branching model

- `main`: release-only branch. It must stay stable and must never receive
  feature commits.
- `develop`: integration branch. It receives completed feature/fix branches.
- Short-lived work branches are cut from `develop`:
  - `feat/<short-description>`
  - `fix/<short-description>`
  - `chore/<short-description>`
  - `docs/<short-description>`
- Pull requests target `develop`, never `main`.
- Release flow: merge `develop` into `main`, then tag `vX.Y.Z`.

Always fetch and update `develop` before creating a work branch:

```bash
git fetch origin
git switch develop
git pull --ff-only
git switch -c feat/<short-description>
```

## Commits

- Conventional Commits format: `type(scope): short description`.
- One logical change per commit.
- Never commit `local.properties`, `rea/`, build outputs, APKs/AABs or secrets.

## Validation

- `git add`, `git commit` and `git push` require explicit user approval.
- Every PR must include a `Manually verified:` section describing what was
  checked by hand.
