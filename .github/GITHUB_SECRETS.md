# GitHub Secrets Configuration

This file documents the GitHub secrets needed for the WORM publication workflow.

## Required Secrets

### `GITHUB_TOKEN` (Automatic)

**Already available by default in GitHub Actions**

- Provided automatically by GitHub Actions runner
- Used for: Publishing to GitHub Packages
- Permissions: Automatically includes `write:packages`
- No manual configuration needed

## Optional Secrets (For Future Use)

If you want to extend the workflow with additional features:

### `SONATYPE_USERNAME`
Username for Maven Central deployment (if publishing there in the future)

### `SONATYPE_PASSWORD`
Password for Maven Central deployment (if publishing there in the future)

## How to Set Secrets

1. Go to: **GitHub Repository → Settings → Secrets and variables → Actions**
2. Click **New repository secret**
3. Add the secret name and value
4. Click **Add secret**

The secret will be available as `${{ secrets.SECRET_NAME }}` in workflows.

## Current Workflow

The `.github/workflows/publish.yml` uses:
- `${{ secrets.GITHUB_TOKEN }}` ← Automatic, no setup needed
- Publishes to GitHub Packages on every push to `main`/`master`

## Publishing Process

1. **Code Push** → GitHub detects push to main/master
2. **Workflow Trigger** → `.github/workflows/publish.yml` runs automatically
3. **Tests** → All Maven tests execute
4. **Build** → Project builds with all artifacts (source, javadoc)
5. **Deploy** → `mvn deploy` publishes to GitHub Packages using `GITHUB_TOKEN`
6. **Release** → If tag pushed, creates GitHub Release automatically

No manual intervention required!

