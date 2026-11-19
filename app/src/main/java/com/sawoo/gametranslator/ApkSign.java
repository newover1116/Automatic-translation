package com.sawoo.gametranslator;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/** 번역된 APK를 이 기기 전용 키로 서명한다. 같은 키를 계속 써서 다시 번역해도 덮어 설치된다. */
final class ApkSign {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "game_translator_signing";

    private ApkSign() {}

    static void sign(File unsigned, File signed) throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) createKey();
        PrivateKey key = (PrivateKey) ks.getKey(ALIAS, null);
        X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                "GAMETR", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("Game Translator")
                .build()
                .sign();
    }

    private static void createKey() throws Exception {
        long now = System.currentTimeMillis();
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, STORE);
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(new X500Principal("CN=Game Translator"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(new Date(now - 86400000L))
                .setCertificateNotAfter(new Date(now + 30L * 365 * 86400000L))
                .build());
        g.generateKeyPair();
    }
}
