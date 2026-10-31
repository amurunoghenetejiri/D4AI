package com.d4tech.d4ai;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

/**
 * Capacitor shell — keeps D4AI UI (logo, colors, splash).
 * ASSIST / VOICE_COMMAND wakes native listen service.
 */
public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleAssistIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAssistIntent(intent);
    }

    private void handleAssistIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_ASSIST.equals(action)
                || Intent.ACTION_VOICE_COMMAND.equals(action)
                || "com.d4tech.d4ai.WAKE".equals(action)) {
            Intent i = new Intent(this, com.d4tech.d4ai.voice.D4AiListenService.class);
            i.setAction(com.d4tech.d4ai.voice.D4AiListenService.ACTION_WAKE);
            try {
                startForegroundService(i);
            } catch (Exception e) {
                try {
                    startService(i);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
