# StudyNotes R8 rules.

# Keep line numbers so crash stack traces from the release APK are readable.
-keepattributes SourceFile,LineNumberTable

# PdfBox-Android: optional JPEG2000 / crypto backends are not bundled.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.bind.**

# PdfBox creates security handlers reflectively (SecurityHandlerFactory -> getDeclaredConstructor),
# which R8 full mode cannot trace. Needed to open/export/unlock encrypted PDFs in release builds.
-keep class com.tom_roush.pdfbox.pdmodel.encryption.StandardSecurityHandler { <init>(...); }
-keep class com.tom_roush.pdfbox.pdmodel.encryption.PublicKeySecurityHandler { <init>(...); }
