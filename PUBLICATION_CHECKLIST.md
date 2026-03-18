# WORM Publication Checklist

Complete these steps to publish WORM as a Maven library on GitHub Packages.

## ✅ Pre-Publication Setup

### Step 1: Verify Repository Settings
- [ ] Repository is public (or users won't be able to install the package)
- [ ] Repository name: `rfdetoni/worm`
- [ ] Main branch configured (GitHub detects `main` or `master`)

### Step 2: Verify POM Configuration
- [ ] `pom.xml` has correct `groupId`: `br.com.liviacare`
- [ ] `pom.xml` has correct `artifactId`: `worm`
- [ ] `version` is set correctly (e.g., `1.0.1`)
- [ ] `distributionManagement` section exists pointing to GitHub Packages
- [ ] SCM section is configured correctly

**Check:**
```bash
grep -A 5 "<distributionManagement>" pom.xml
```

### Step 3: Verify Workflow File
- [ ] `.github/workflows/publish.yml` exists
- [ ] Workflow has `write:packages` permission
- [ ] Uses `${{ secrets.GITHUB_TOKEN }}` (automatic)

**Check:**
```bash
ls -la .github/workflows/publish.yml
```

### Step 4: Local Test (Optional but Recommended)
- [ ] Run tests locally: `./mvnw clean verify`
- [ ] Build locally: `./mvnw clean package`
- [ ] Both succeed without errors

## 🚀 First Publication

### Option A: Automatic via Git Push (Recommended)

1. **Ensure everything is committed:**
   ```bash
   git status  # Should show clean working tree
   ```

2. **Push to main/master branch:**
   ```bash
   git push origin main
   ```

3. **Monitor the workflow:**
   - Go to: `https://github.com/rfdetoni/worm/actions`
   - Look for "Publish to GitHub Packages" workflow
   - Watch the build logs
   - Should show: ✅ Build → ✅ Tests → ✅ Deploy

4. **Verify publication:**
   - Go to: `https://github.com/rfdetoni/worm/packages`
   - Should see `br.com.liviacare.worm` package
   - Version `1.0.1` should be listed

### Option B: Manual Local Deployment

1. **Ensure local authentication is set up:**
   - Edit `~/.m2/settings.xml`
   - Add GitHub token credentials

2. **Deploy locally:**
   ```bash
   ./mvnw clean deploy -DskipTests
   ```

3. **Check results:**
   ```bash
   # Should show: BUILD SUCCESS
   # and: Uploading to github: https://maven.pkg.github.com/rfdetoni/worm/br/com/liviacare/worm/1.0.1/...
   ```

## 📦 Publishing a Release

After first publication, to release new versions:

### Step 1: Update Version
```bash
# Edit pom.xml
# Change: <version>1.0.1</version> → <version>1.0.2</version>

./mvnw versions:set -DnewVersion=1.0.2
./mvnw versions:commit
```

### Step 2: Commit Changes
```bash
git add pom.xml
git commit -m "Release version 1.0.2"
git push origin main
```

### Step 3: Create Release Tag
```bash
git tag -a v1.0.2 -m "Release version 1.0.2"
git push origin v1.0.2
```

### Step 4: Workflow Auto-Publishes
- Workflow detects tag push
- Builds and tests project
- Publishes to GitHub Packages
- Creates GitHub Release

## ✨ After Publication

### Users Can Now Install WORM

Your users can add to their `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/rfdetoni/worm</url>
  </repository>
</repositories>

<dependency>
  <groupId>br.com.liviacare</groupId>
  <artifactId>worm</artifactId>
  <version>1.0.2</version>
</dependency>
```

### Next Steps
1. Update README with installation instructions ✅ (Already done)
2. Share the GitHub Packages link: `https://github.com/rfdetoni/worm/packages`
3. Document in PUBLISHING.md ✅ (Already done)

## 🔧 Troubleshooting

### Workflow Fails at Deploy Step

**Check GitHub Actions Logs:**
1. Go to: `https://github.com/rfdetoni/worm/actions`
2. Click on the failed workflow run
3. Expand "Publish to GitHub Packages" step
4. Look for error messages

**Common Issues:**

| Error | Solution |
|-------|----------|
| `401 Unauthorized` | GITHUB_TOKEN not available (should be automatic) |
| `400 Bad Request` | Check `distributionManagement` URL in pom.xml |
| `Redeployment of artifacts to repository 'github'` | Version already exists; increment version |

### Package Not Visible in GitHub Packages UI

1. Wait 1-2 minutes for GitHub to process
2. Refresh the page
3. Check in "Packages" tab on repository
4. Should appear as public package

## 📝 Files Created/Modified

```
✅ pom.xml - Added distributionManagement
✅ .github/workflows/publish.yml - Automatic CI/CD
✅ .github/GITHUB_SECRETS.md - Secrets documentation
✅ .mvn/settings.xml - Local authentication template
✅ PUBLISHING.md - Full publishing guide
✅ README.md - Updated with installation instructions
```

## ✅ Final Verification Checklist

- [ ] All files created/updated
- [ ] POM has distributionManagement
- [ ] Workflow file exists and looks correct
- [ ] README has installation instructions
- [ ] Can access: https://github.com/rfdetoni/worm/packages
- [ ] Package is visible with correct version
- [ ] JAR, sources, and javadoc all published
- [ ] Tests pass in workflow

## 🎉 Success!

You now have WORM published as a Maven library on GitHub Packages!

**Share the link:** https://github.com/rfdetoni/worm/packages

**Installation command for users:**
```bash
# Add to pom.xml and authenticate, then:
mvn dependency:copy-dependencies
```

---

For more details, see:
- 📖 PUBLISHING.md
- 🔐 .github/GITHUB_SECRETS.md
- 🔄 .github/workflows/publish.yml

