# SSHJ picks algorithms by name and BouncyCastle's provider loads its
# algorithm tables reflectively, so these packages must survive shrinking.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }

# Optional integrations that SSHJ and BouncyCastle reference but Android lacks.
-dontwarn javax.naming.**
-dontwarn javax.security.auth.login.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
-dontwarn org.slf4j.impl.**
-dontwarn org.bouncycastle.pqc.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.est.**
-dontwarn org.bouncycastle.cmc.**
-dontwarn org.bouncycastle.mls.**
