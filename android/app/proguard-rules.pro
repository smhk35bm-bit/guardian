# Guardian does not obfuscate or hide anything; the default Android rules are
# kept so that stack traces remain readable for the parents when debugging.
#
# Keep OkHttp's optional platform integrations optional (they are not used).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
