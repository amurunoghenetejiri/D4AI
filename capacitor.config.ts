import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.d4tech.d4ai",
  appName: "D4AI",
  webDir: "dist",
  server: {
    // Bundled assets only — local app, not a remote website
    androidScheme: "https",
    cleartext: true,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 2200,
      launchAutoHide: true,
      backgroundColor: "#050b18",
      androidSplashResourceName: "splash",
      androidScaleType: "CENTER_CROP",
      showSpinner: false,
      splashFullScreen: true,
      splashImmersive: true,
    },
    StatusBar: {
      style: "DARK",
      backgroundColor: "#0b1a3a",
    },
  },
  android: {
    allowMixedContent: true,
    backgroundColor: "#050b18",
  },
};

export default config;
