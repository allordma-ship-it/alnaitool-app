#!/usr/bin/env bash
# Applies native configuration required by Wanees' plugins after
# `flutter create` has generated the android/ and ios/ folders:
#   * flutter_stripe: FlutterFragmentActivity + AppCompat themes (Android), iOS 13+
#   * wanees:// deep-link scheme (Stripe 3DS return + Connect onboarding)
#   * cleartext HTTP to the local API in debug builds only
#   * Arabic as the primary iOS localization (system dialogs, RTL)
# Idempotent: safe to run repeatedly.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import pathlib, re

root = pathlib.Path(".")

def patch(path, fn):
    p = root / path
    if not p.exists():
        print(f"skip (missing): {path}")
        return
    old = p.read_text()
    new = fn(old)
    if new != old:
        p.write_text(new)
        print(f"patched: {path}")

# --- Template leftovers ----------------------------------------------------
# `flutter create` adds a counter-app sample test referencing `MyApp`.
sample = root / "test" / "widget_test.dart"
if sample.exists() and "MyApp" in sample.read_text():
    sample.unlink()
    print("removed: test/widget_test.dart (flutter create sample)")

# --- Android ---------------------------------------------------------------
for main in root.glob("android/app/src/main/kotlin/**/MainActivity.kt"):
    patch(main.as_posix(), lambda s: re.sub(
        r"import io\.flutter\.embedding\.android\.FlutterActivity",
        "import io.flutter.embedding.android.FlutterFragmentActivity", s
    ).replace(": FlutterActivity()", ": FlutterFragmentActivity()"))

for styles in ["android/app/src/main/res/values/styles.xml", "android/app/src/main/res/values-night/styles.xml"]:
    patch(styles, lambda s: s.replace("@android:style/Theme.Light.NoTitleBar", "Theme.AppCompat.Light.NoActionBar")
                             .replace("@android:style/Theme.Black.NoTitleBar", "Theme.AppCompat.NoActionBar"))

# flutter_stripe release builds: the Stripe SDK references Google's non-public
# Tap&Pay library (push provisioning, unused here). Keep R8 from failing on the
# missing classes and skip release lint, which would try to resolve that library.
PROGUARD = """# Stripe push provisioning (needs Google's private Tap&Pay SDK; not used by Wanees)
-dontwarn com.stripe.android.pushProvisioning.PushProvisioningActivity$g
-dontwarn com.stripe.android.pushProvisioning.PushProvisioningActivityStarter$Args
-dontwarn com.stripe.android.pushProvisioning.PushProvisioningActivityStarter$Error
-dontwarn com.stripe.android.pushProvisioning.PushProvisioningActivityStarter
-dontwarn com.stripe.android.pushProvisioning.PushProvisioningEphemeralKeyProvider
-dontwarn com.stripe.android.pushProvisioning.**
-dontwarn com.reactnativestripesdk.pushprovisioning.**
"""
rules = root / "android/app/proguard-rules.pro"
if (root / "android/app").exists():
    existing = rules.read_text() if rules.exists() else ""
    if "pushProvisioning" not in existing:
        rules.write_text(existing + PROGUARD)
        print("patched: android/app/proguard-rules.pro")

def gradle_kts(s):
    if "proguard-rules.pro" not in s:
        s = s.replace(
            '            signingConfig = signingConfigs.getByName("debug")\n',
            '            signingConfig = signingConfigs.getByName("debug")\n'
            '            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")\n', 1)
    if "checkReleaseBuilds" not in s:
        s = s.replace("    buildTypes {", "    lint {\n        checkReleaseBuilds = false\n    }\n\n    buildTypes {", 1)
    # Stable signing key from the environment (CI secrets). Without it the
    # builds fall back to the per-machine debug key.
    if "WANEES_KEYSTORE_FILE" not in s:
        s = s.replace("    lint {", SIGNING_KTS + "\n    lint {", 1)
        s = s.replace(
            '            signingConfig = signingConfigs.getByName("debug")\n',
            '            signingConfig = if (waneesKeystore != null) signingConfigs.getByName("release") '
            'else signingConfigs.getByName("debug")\n', 1)
        s = s.replace("    buildTypes {\n",
                      "    buildTypes {\n"
                      "        getByName(\"debug\") {\n"
                      "            if (waneesKeystore != null) signingConfig = signingConfigs.getByName(\"release\")\n"
                      "        }\n", 1)
        s = s.replace("android {\n", 'val waneesKeystore: String? = System.getenv("WANEES_KEYSTORE_FILE")\n\nandroid {\n', 1)
    return s

SIGNING_KTS = """    signingConfigs {
        create("release") {
            if (waneesKeystore != null) {
                storeFile = file(waneesKeystore!!)
                storePassword = System.getenv("WANEES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WANEES_KEY_ALIAS")
                keyPassword = System.getenv("WANEES_KEY_PASSWORD")
            }
        }
    }
"""
patch("android/app/build.gradle.kts", gradle_kts)

def gradle_groovy(s):
    if "proguard-rules.pro" not in s:
        s = s.replace(
            '            signingConfig = signingConfigs.debug\n',
            '            signingConfig = signingConfigs.debug\n'
            "            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'\n", 1)
    if "checkReleaseBuilds" not in s:
        s = s.replace("    buildTypes {", "    lintOptions {\n        checkReleaseBuilds false\n    }\n\n    buildTypes {", 1)
    return s
patch("android/app/build.gradle", gradle_groovy)

DEEP_LINK = """            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="wanees"/>
            </intent-filter>
        </activity>"""

def manifest(s):
    # Home-screen name shown under the icon.
    s = re.sub(r'android:label="[^"]*"', 'android:label="ونيس"', s, count=1)
    if 'android:scheme="wanees"' not in s:
        s = s.replace("        </activity>", DEEP_LINK, 1)
    return s
patch("android/app/src/main/AndroidManifest.xml", manifest)

def debug_manifest(s):
    if "usesCleartextTraffic" in s:
        return s
    if 'xmlns:tools' not in s:
        s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
                      '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools"', 1)
    return s.replace("</manifest>",
                     '    <application android:usesCleartextTraffic="true" tools:replace="android:usesCleartextTraffic"/>\n</manifest>', 1)
patch("android/app/src/debug/AndroidManifest.xml", debug_manifest)

# --- iOS -------------------------------------------------------------------
URL_TYPES = """	<key>CFBundleDevelopmentRegion</key>
	<string>ar</string>
	<key>CFBundleLocalizations</key>
	<array>
		<string>ar</string>
		<string>en</string>
	</array>
	<key>CFBundleURLTypes</key>
	<array>
		<dict>
			<key>CFBundleURLName</key>
			<string>sy.wanees.app</string>
			<key>CFBundleURLSchemes</key>
			<array>
				<string>wanees</string>
			</array>
		</dict>
	</array>
	<key>NSAppTransportSecurity</key>
	<dict>
		<key>NSAllowsLocalNetworking</key>
		<true/>
	</dict>
</dict>
</plist>"""

def plist(s):
    s = re.sub(r"(<key>CFBundleDisplayName</key>\s*<string>)[^<]*(</string>)", r"\1ونيس\2", s)
    if "CFBundleURLSchemes" in s:
        return s
    # Replace the template's development region; ours is set in URL_TYPES.
    s = re.sub(r"\t<key>CFBundleDevelopmentRegion</key>\n\t<string>[^<]*</string>\n", "", s)
    return re.sub(r"</dict>\s*</plist>\s*$", URL_TYPES + "\n", s)
patch("ios/Runner/Info.plist", plist)

def podfile(s):
    s = re.sub(r"^#\s*platform :ios, '[\d.]+'", "platform :ios, '13.0'", s, flags=re.M)
    return re.sub(r"^platform :ios, '(?:9|10|11|12)\.\d+'", "platform :ios, '13.0'", s, flags=re.M)
patch("ios/Podfile", podfile)
PY

echo "Native platform configuration complete."
