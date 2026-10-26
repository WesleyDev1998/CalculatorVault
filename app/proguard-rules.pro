# ==============================================================================
# REGRAS DE OFUSCAÇÃO AGRESSIVA E SEGURANÇA ANTI-FORENSE (R8 / ProGuard)
# ==============================================================================

-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'o'
-overloadaggressively
-renamesourcefileattribute SourceFile

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

-keep public class * extends androidx.appcompat.app.AppCompatActivity
-keep public class * extends android.app.Activity

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

-keepclassmembers class com.secure.calcvault.MediaItem {
    *;
}
