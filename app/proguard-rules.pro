# QuietSemantics reaches Compose's list of enabled accessibility services by name.
-keepclassmembernames class androidx.compose.ui.platform.AndroidComposeView {
    androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat composeAccessibilityDelegate;
}
-keepclassmembernames class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat {
    java.util.List _enabledServices;
}

# Started as the adb shell by the VIDAA locale setter.
-keep class dev.lumbina.tv.platform.system.ViddaLocaleTool {
    public static void main(java.lang.String[]);
}
