# Publishing WORM to GitHub Packages

This guide explains how to publish WORM as a Maven library on GitHub Packages.

## Prerequisites

- GitHub account with push access to the repository
- Maven installed locally (or use `./mvnw`)
- Git configured with your credentials

## Step 1: Authenticate with GitHub Packages

### Option A: Using GitHub Personal Access Token (Recommended for CI/CD)

1. Go to GitHub Settings → Developer settings → Personal access tokens
2. Create a new token with scopes:
   - `write:packages` - Deploy packages
   - `read:packages` - Install packages
   - `delete:packages` - Delete packages (optional)

3. Copy the token and use it as `GITHUB_TOKEN` in your CI/CD pipeline (already configured in `.github/workflows/publish.yml`)

### Option B: Local Authentication

Create or update `~/.m2/settings.xml`:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                      http://maven.apache.org/xsd/settings-1.0.0.xsd">

  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_PERSONAL_ACCESS_TOKEN</password>
    </server>
  </servers>

</settings>
```

## Step 2: Publish via GitHub Actions (Automatic)

The project includes an automated workflow (`.github/workflows/publish.yml`) that:

1. Runs on every push to `main`/`master` branch
2. Runs tests to ensure quality
3. Builds the project
4. Publishes to GitHub Packages automatically

**No additional setup needed!** The workflow uses `${{ secrets.GITHUB_TOKEN }}` which is automatically available in GitHub Actions.

### Publishing a Release

To publish a specific version:

1. Update the version in `pom.xml`:
   ```xml
   <version>1.0.2</version>
   ```

2. Commit and create a git tag:
   ```bash
   git tag -a v1.0.2 -m "Release version 1.0.2"
   git push origin v1.0.2
   ```

3. The workflow will automatically:
   - Build and test the project
   - Publish to GitHub Packages
   - Create a GitHub Release

## Step 3: Manual Local Publishing (Optional)

If you need to publish locally:

```bash
# Ensure you're authenticated (see Step 1)
./mvnw clean deploy -DskipTests
```

## Step 4: Using WORM in Your Projects

After publishing, add WORM to your project's `pom.xml`:

```xml
<!-- Add GitHub repository to pom.xml -->
<repositories>
  <repository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/rfdetoni/worm</url>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>

<!-- Add dependency -->
<dependency>
  <groupId>br.com.liviacare</groupId>
  <artifactId>worm</artifactId>
  <version>1.0.2</version>
</dependency>
```

### Authenticate for Dependency Resolution

Users also need to authenticate to download packages. Update their `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>GITHUB_USERNAME</username>
    <password>PERSONAL_ACCESS_TOKEN</password>
  </server>
</servers>
```

## Step 5: Verify Publication

After publishing, verify the package is available:

1. Go to: https://github.com/rfdetoni/worm/packages
2. Look for `br.com.liviacare.worm` package
3. Click to view versions and download instructions

## Troubleshooting

### Issue: "No plugin found for prefix 'deploy'"
**Solution:** Ensure your `pom.xml` has the `distributionManagement` section configured (already done).

### Issue: "401 Unauthorized"
**Solution:**
- Verify your GitHub token is valid and hasn't expired
- Check that token has `write:packages` scope
- Ensure username and token are correct in `settings.xml`

### Issue: Package not visible in GitHub Packages
**Solution:**
- Wait a few moments for GitHub to process the upload
- Refresh the GitHub Packages page
- Check the workflow logs for any errors

## Version Management

Follow semantic versioning:
- **MAJOR.MINOR.PATCH** (e.g., `1.0.0`, `1.0.1`, `2.0.0`)
- Update `pom.xml` version before tagging
- Tag format: `v1.0.0` (with `v` prefix)

## CI/CD Integration

The `.github/workflows/publish.yml` automatically:
- Runs on every push to main/master
- Executes all Maven tests
- Publishes successful builds
- Creates GitHub releases for tags

No manual intervention needed after push!

## Next Steps

1. Push your changes to GitHub
2. Monitor the GitHub Actions workflow
3. Share the installation instructions with users
4. Users can now add WORM as a Maven dependency

For more info, see [GitHub Packages Documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).

