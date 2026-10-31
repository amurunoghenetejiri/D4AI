# Code Assist — Run D4AI

## If Play says “Nothing to run”

1. Tap **More (⋯)** → **Sync project with Gradle files** (or **Build**).
2. Wait until sync finishes (progress at top).
3. Tap the config dropdown near Play (may say “Nothing to run”).
4. Choose **app**.
5. Tap **Play** again.

If there is no **app** config:

1. **More → Build → Make Project**
2. Or **More → Terminal** and run:
   ```text
   ./gradlew :app:assembleDebug
   ```
3. After a successful build, **Play** should list **app**.

## First open

1. Open project **D4AI** (must show **Android · 1 module**).
2. Allow Gradle download/sync (needs internet).
3. Select **app** → **Run** → install on phone.
4. Allow **Microphone** → **Wake D4AI**.

## Do not open

- Random files like `gradle-wrapper.properties` as “run target”
- `.platform` or `gradle-backup` folders
