# Code Assist — how to run D4AI

## Correct project

D4AI is now a **real Android project at the repo root**.

After you **pull / re-clone**, you should see:

**D4AI · Android · 1 module**

Not: *Files · 0 modules*

## Steps

1. In Code Assist **Projects** list, remove/close the old **D4AI (0 modules)** if it is still there.
2. **Clone again** or **pull** from:
   ```text
   https://github.com/amurunoghenetejiri/D4AI.git
   ```
3. Open **D4AI** (the new one — must say **Android · 1 module**).
4. Wait for **Gradle sync**.
5. Tap **Run** (green play) → install on phone.
6. Allow **Microphone** → tap **Wake D4AI**.

## Do not open

- Old “D4AI · 0 modules” (web-only snapshot)
- Only the nested `android` folder is no longer required — root is the app

## “D4AI assistant”

If you still have **D4AI assistant (1 module)** from earlier, you can ignore it or delete it. Use the main **D4AI** repo after this update.
