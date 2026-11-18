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

# Sherpa-onnx JNI tra class/field theo TÊN viết cứng trong libsherpa-onnx-jni.so:
#   FindClass("com/k2fsa/sherpa/onnx/OfflineModelConfig")
#   GetFieldID(cls, "transducer" / "encoder" / "decoder" / "joiner" / "featConfig" ...)
# R8 đổi tên các data class config (OfflineModelConfig -> p6.j, transducer -> a) nên bản
# release TRƯỚC ĐÂY: sherpa không tạo được recognizer/TTS -> STT offline + Piper chết
# âm thầm (debug không minify nên vẫn chạy -> lỗi chỉ hiện ở bản phát hành).
# Giữ nguyên tên + toàn bộ thành viên của bindings (nhỏ, chỉ vài class).
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Google Ads / Play Services - missing LoudnessCodecController (API 33+)
-dontwarn android.media.LoudnessCodecController
-dontwarn android.media.LoudnessCodecController$OnLoudnessCodecUpdateListener

# KHONG them -dontwarn io.grpc.InternalGlobalInterceptors: class nay co trong
# grpc-api 1.57.2 nhung bi xoa o 1.68.2. Xung dot version da duoc ghim trong
# app/build.gradle.kts (configurations.all ... io.grpc -> 1.57.2); de R8 bao loi
# neu sau nay version troi lai, thay vi crash runtime "Internal error in Cloud Firestore".

# Cho phép R8 optimize aggressive hơn
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-allowaccessmodification

