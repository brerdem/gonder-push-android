# GonderPush (Android)

Official Kotlin SDK for Gönder Android push notifications (FCM).

## Requirements

- Android 7.0+ (API 24)
- A Firebase project with Cloud Messaging enabled
- `google-services.json` in your app module
- Your Gönder App ID (Platforms → Android Push)

## Install (JitPack)

**settings.gradle.kts** (or root `build.gradle`):

```groovy
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven { url 'https://jitpack.io' }
  }
}
```

**app/build.gradle**:

```groovy
dependencies {
  implementation 'com.github.Gonder-AI:gonder-push-android:0.2.0'
}
```

Also apply the Google Services plugin and add Firebase Messaging if not already present.

## Usage

```kotlin
import ai.gonder.push.GonderPush

class MyApp : Application() {
  override fun onCreate() {
    super.onCreate()
    GonderPush.initialize(this, "YOUR_APP_ID")
    // Optional staging:
    // GonderPush.initialize(this, "YOUR_APP_ID", "https://staging.gonder.ai")
    GonderPush.registerForPushNotifications()
  }
}
```

### Listeners

```kotlin
GonderPush.addClickListener { n -> /* deep link using n.url / n.campaignId */ }
GonderPush.addForegroundLifecycleListener { true } // return false to suppress
GonderPush.addPermissionObserver { granted -> }
GonderPush.addSubscriptionObserver { state -> }

// Also call from Activity.onCreate / onNewIntent for system-tray opens:
GonderPush.handleIntent(intent)
```

### Identify / mute / tags

```kotlin
GonderPush.login("crm-user-123") // or setExternalId
GonderPush.addTag("plan", "pro")
GonderPush.optOut() // soft mute
GonderPush.optIn()
GonderPush.logout()
GonderPush.unsubscribe() // hard unsubscribe
```

## Publish (maintainers)

1. Push this package to `https://github.com/Gonder-AI/gonder-push-android`
2. Tag a version: `git tag 0.1.0 && git push --tags`
3. JitPack builds from the tag — verify at `https://jitpack.io/#Gonder-AI/gonder-push-android`

## Test app

See `../../../../gonder-android-test` (sibling of `gonder.ai` in the workspace).
