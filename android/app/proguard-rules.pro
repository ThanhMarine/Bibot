# Keep Go Mobile generated classes - chỉ giữ public API, cho phép R8 optimize internal
-keep class go.bibot.** { *; }
-keep class go.mobile.** { *; }
-keep class bibot.** { public *; }

# 11.4 release diet — Room: chỉ giữ Entity/DAO (annotation-based), KHÔNG giữ toàn bộ Room library
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.bibot.app.data.db.** { *; }

# Vosk: chỉ giữ các class load qua reflection (Class.forName)
-keep class org.vosk.VoskApi { *; }
-keep class org.vosk.Recognizer { *; }
-keep class org.vosk.Model { *; }
-keep class org.vosk.SpkModel { *; }

# Google Ads / Play Services - missing LoudnessCodecController (API 33+)
-dontwarn android.media.LoudnessCodecController
-dontwarn android.media.LoudnessCodecController$OnLoudnessCodecUpdateListener

# gRPC (Google Drive/API client) - missing InternalGlobalInterceptors
-dontwarn io.grpc.InternalGlobalInterceptors

# Cho phép R8 optimize aggressive hơn
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-allowaccessmodification

