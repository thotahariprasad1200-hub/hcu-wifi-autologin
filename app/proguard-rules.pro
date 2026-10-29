# ProGuard rules for ultra-lightweight HCU Wi-Fi app
-keepattributes SourceFile,LineNumberTable
-dontwarn android.**
-keep public class edu.uoh.hcuwifi.MainActivity
-keep public class edu.uoh.hcuwifi.HcuTileService
-keep public class edu.uoh.hcuwifi.HcuWidgetProvider
-keep public class edu.uoh.hcuwifi.NetworkReceiver
