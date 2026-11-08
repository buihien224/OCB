#!/usr/bin/env python3
"""Overlay VSOCB on the pinned Shizuku Next checkout used by CI.

Run only on a fresh disposable checkout; the upstream source stays attributable.
"""
import pathlib
import os
import shutil
import sys
import xml.etree.ElementTree as ET

upstream = pathlib.Path(sys.argv[1]).resolve()
app = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main'
manager = upstream / 'manager'
android = 'http://schemas.android.com/apk/res/android'
tools = 'http://schemas.android.com/tools'
ET.register_namespace('android', android)
ET.register_namespace('tools', tools)
attr = lambda name: '{' + android + '}' + name

for folder in ('java', 'aidl', 'assets', 'jniLibs'):
    src = app / folder
    if src.exists():
        shutil.copytree(src, manager / 'src/main' / folder, dirs_exist_ok=True)

# Copy VSOCB's icon resources into manager so APK shows VSOCB identity
vsocb_res = app / 'res'
manager_res = manager / 'src/main/res'
for mipmap_dir in sorted(vsocb_res.glob('mipmap-*')):
    shutil.copytree(mipmap_dir, manager_res / mipmap_dir.name, dirs_exist_ok=True)
ic_fg = vsocb_res / 'drawable/ic_launcher_foreground.xml'
if ic_fg.exists():
    (manager_res / 'drawable').mkdir(parents=True, exist_ok=True)
    shutil.copy2(ic_fg, manager_res / 'drawable/ic_launcher_foreground.xml')
# Copy all other VSOCB drawables (icons, etc.) into the manager
(manager_res / 'drawable').mkdir(parents=True, exist_ok=True)
for f in sorted((vsocb_res / 'drawable').glob('*.xml')):
    if f.name == 'ic_launcher_foreground.xml':
        continue
    shutil.copy2(f, manager_res / 'drawable' / f.name)
# Merge ic_launcher_background color so the adaptive icon has a background
for colors_xml in (vsocb_res / 'values/colors.xml', vsocb_res / 'values-night/colors.xml'):
    if colors_xml.exists():
        shutil.copy2(colors_xml, manager_res / colors_xml.parent.name / 'vsocb_colors.xml')

# Keep the manager namespace (and its generated R/BuildConfig), but give the
# combined APK VSOCB's identity. Server/starter derive this ID from :manager.
gradle = manager / 'build.gradle'
source = gradle.read_text()
assert 'applicationId = "moe.shizuku.privileged.api"' in source
source = source.replace('applicationId = "moe.shizuku.privileged.api"',
                        'applicationId = "com.bhlnk.ocb"\n        minSdk = 30')
# Store .so uncompressed for 16 KB page-alignment (Android 15+)
source = source.replace('            useLegacyPackaging = true',
                        '            useLegacyPackaging = false')
# Only build for arm64-v8a — skips emulator ABIs, cuts lib/ size and cmake build time
source = source.replace(
    '        externalNativeBuild {\n            cmake {\n                arguments = ["-DANDROID_STL=none"]\n            }\n        }',
    '        externalNativeBuild {\n            cmake {\n                arguments = ["-DANDROID_STL=none"]\n            }\n        }\n        ndk {\n            abiFilters "arm64-v8a"\n        }')
# Enable R8 shrinking on debug too — Compose deps shrink from ~86 MB to ~15 MB
source = source.replace(
    '        debug {\n            signingConfig = signingConfigs.sign\n        }',
    '        debug {\n            signingConfig = signingConfigs.sign\n            minifyEnabled = true\n            shrinkResources = true\n            proguardFiles = ["proguard-rules.pro"]\n        }')
gradle.write_text(source)

# Release signing: set OCB_SIGN=1 and provide signing.properties.
# Debug builds use the default debug keystore.
sign_release = os.environ.get('OCB_SIGN') == '1'
with gradle.open('a') as out:
    if not sign_release:
        out.write('\nandroid.buildTypes.release.signingConfig = null\n')
    if os.environ.get('VSOCB_VERSION_CODE'):
        code = 3000 + int(os.environ['VSOCB_VERSION_CODE'])
        out.write(f'android.defaultConfig.versionCode = {code}\n')
        out.write('android.defaultConfig.versionName = "3.1"\n')
with (manager / 'proguard-rules.pro').open('a') as out:
    out.write('\n-keep class com.bhlnk.ocb.UserService { *; }\n')

# The pinned upstream root script contains Kotlin conditional syntax in Groovy.
root_gradle = upstream / 'build.gradle'
source = root_gradle.read_text()
source = source.replace('${if (isBeta) "-beta" else ""}', '${isBeta ? "-beta" : ""}')
# Override version to VSOCB's identity (not Shizuku's "14.x.x-next")
source = source.replace('def baseVersionName = "14.0.32"', 'def baseVersionName = "3.1"')
source = source.replace(
    'versionName = "${baseVersionName}${isBeta ? "-beta" : ""}-next"',
    'versionName = "${baseVersionName}"')
source = source.replace(
    'versionCode = gitCommitCount',
    'versionCode = 3001')
root_gradle.write_text(source)

manifest = manager / 'src/main/AndroidManifest.xml'
tree = ET.parse(manifest)
application = tree.getroot().find('application')
application.set(attr('label'), 'VSOCB')
application.set(attr('icon'), '@mipmap/ic_launcher')
application.set(attr('roundIcon'), '@mipmap/ic_launcher')
application.set(attr('allowBackup'), 'false')
application.set(attr('extractNativeLibs'), 'true')
# Remove TV banner (references Shizuku's drawable)
if attr('banner') in application.attrib:
    del application.attrib[attr('banner')]
for activity in application.findall('activity'):
    for intent in list(activity.findall('intent-filter')):
        if any(c.get(attr('name')) in ('android.intent.category.LAUNCHER',
                'android.intent.category.LEANBACK_LAUNCHER') for c in intent.findall('category')):
            activity.remove(intent)
activity = ET.SubElement(application, 'activity', {
    attr('name'): 'com.bhlnk.ocb.MainActivity', attr('exported'): 'true',
    attr('configChanges'): 'orientation|screenSize|screenLayout',
    attr('theme'): '@android:style/Theme.DeviceDefault.NoActionBar'})
intent = ET.SubElement(activity, 'intent-filter')
ET.SubElement(intent, 'action', {attr('name'): 'android.intent.action.MAIN'})
ET.SubElement(intent, 'category', {attr('name'): 'android.intent.category.LAUNCHER'})
tree.write(manifest, encoding='utf-8', xml_declaration=True)

# Provider discovery must target the embedded manager, not an external APK.
provider = upstream / 'api/provider/src/main/java/rikka/shizuku/ShizukuProvider.java'
source = provider.read_text()
assert 'MANAGER_APPLICATION_ID = "moe.shizuku.privileged.api"' in source
provider.write_text(source.replace('MANAGER_APPLICATION_ID = "moe.shizuku.privileged.api"',
                                  'MANAGER_APPLICATION_ID = "com.bhlnk.ocb"'))

# Both screens share the manager Application and its provider/binder lifecycle.
activity_file = manager / 'src/main/java/com/bhlnk/ocb/MainActivity.java'
source = activity_file.read_text()
source = source.replace('private int transportMode = TM_ADB;',
                        'private int transportMode = TM_SHIZUKU;')
# This process IS the manager. Server authorization checks its UID; the client
# permission dialog is for other apps and must not be requested from itself.
source = source.replace('return Shizuku.checkSelfPermission();',
                        'return Shizuku.pingBinder() ? PackageManager.PERMISSION_GRANTED : -1;')
# Nút "Bắt đầu" riêng không còn cần thiết:
# khi Exploit được nhấn mà Shizuku chưa chạy, onShizukuNotConnected() sẽ mở pairing.
shizuku_not_connected_anchor = '    /** Shizuku chưa chạy khi cần transport. Integrated build override để mở pairing tutorial. */\n    private void onShizukuNotConnected() {\n        showShizuku();\n    }'
assert shizuku_not_connected_anchor in source
source = source.replace(shizuku_not_connected_anchor,
'''    /** Integrated: open AdbPairingTutorialActivity, or show setup guide if wireless debugging is off. */
    private void onShizukuNotConnected() {
        if (isWirelessDebuggingEnabled()) {
            startActivity(new Intent().setClassName(getPackageName(),
                    "moe.shizuku.manager.adb.AdbPairingTutorialActivity"));
        } else {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Enable Wireless Debugging")
                    .setMessage("1. Settings → About device → tap Build number 7 times to unlock Developer options.\\n2. Settings → Developer options → enable Wireless debugging.\\n3. Return to VSOCB and tap Shizuku — enter the pairing code from the notification.\\n\\nMenu names may vary by device.")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        try {
                            startActivity(new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
                        } catch (Throwable ignored) {
                            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                        }
                    }).show();
        }
    }''')

marker = '    private boolean transportReady() {'
assert marker in source
source = source.replace(marker, '''    private boolean isWirelessDebuggingEnabled() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

''' + marker, 1)
source = source.replace('Shizuku.unbindUserService(serviceArgs(), connection, true)',
                        'Shizuku.unbindUserService(serviceArgs(), connection, false)')
activity_file.write_text(source)

# ── AdbPairingService: auto-start Shizuku after successful pairing ──────────
# After pairing succeeds the upstream code only shows a notification with a
# manual "Start" button. We replace that with an immediate auto-start and
# navigate back to VSOCB MainActivity, eliminating the extra tap.
pairing_service = (upstream / 'manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt')
source = pairing_service.read_text()

# 1. Add ShizukuReceiverStarter import (right after the existing MainAcivity import)
source = source.replace(
    'import moe.shizuku.manager.MainActivity\n',
    'import moe.shizuku.manager.MainActivity\nimport moe.shizuku.manager.receiver.ShizukuReceiverStarter\n')

# 2. After "Pair succeed" log: auto-start Shizuku and return to main screen.
#    Replace the success branch body before it builds the notification.
source = source.replace(
    '        if (success) {\n'
    '            Log.i(tag, "Pair succeed")\n'
    '\n'
    '            title = getString(R.string.notification_adb_pairing_succeed_title)\n'
    '            text = getString(R.string.notification_adb_pairing_succeed_text)\n'
    '\n'
    '            stopSearch()\n',
    '        if (success) {\n'
    '            Log.i(tag, "Pair succeed")\n'
    '            // Auto-start Shizuku immediately — no manual "Start" tap needed.\n'
    '            ShizukuReceiverStarter.start(this, forceStart = true, userInitiated = true, reason = "pairing-adb")\n'
    '            // Bring VSOCB MainActivity back to the foreground.\n'
    '            startActivity(launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))\n'
    '\n'
    '            title = getString(R.string.notification_adb_pairing_succeed_title)\n'
    '            text = getString(R.string.notification_adb_pairing_succeed_text)\n'
    '\n'
    '            stopSearch()\n')

# 3. Remove the "Start" button from the success notification — Shizuku already
#    started, so the action is redundant and confusing.
source = source.replace(
    '                    if (!success) {\n'
    '                        addAction(retryNotificationAction)\n'
    '                    } else {\n'
    '                        setContentIntent(launchPendingIntent)\n'
    '                        addAction(startNotificationAction)\n'
    '                        setAutoCancel(true)\n'
    '                    }\n',
    '                    if (!success) {\n'
    '                        addAction(retryNotificationAction)\n'
    '                    } else {\n'
    '                        setContentIntent(launchPendingIntent)\n'
    '                        setAutoCancel(true)\n'
    '                    }\n')

pairing_service.write_text(source)
print('Prepared integrated VSOCB manager, pairing, starter and server sources')
