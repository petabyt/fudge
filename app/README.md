Kotlin Multiplatform application for iOS and Android

Code guidelines (consider it bugged/incomplete if the code doesn't do this)
- Run all long lived tasks (longer than few ms) on background thread, not main thread
- Nearly all long lived actions should show a progress bar to the user (screen shouldn't sit still for too long)
