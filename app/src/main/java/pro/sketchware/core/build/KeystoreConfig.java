package pro.sketchware.core.build;

import android.text.TextUtils;

import java.io.File;
import java.io.Serializable;

import pro.sketchware.core.project.ProjectSettings;
import pro.sketchware.core.project.SketchwarePaths;

public class KeystoreConfig implements Serializable {

    public static final String SETTING_KEYSTORE_PATH = "release_keystore_path";
    public static final String SETTING_KEYSTORE_PASSWORD = "release_keystore_password";
    public static final String SETTING_KEYSTORE_ALIAS = "release_keystore_alias";
    public static final String SETTING_KEYSTORE_KEY_PASSWORD = "release_keystore_key_password";
    public static final String SETTING_SIGNING_ALGORITHM = "release_signing_algorithm";

    private String keyStorePath;
    private String keyStorePassword;
    private String keyAlias;
    private String keyPassword;
    private String signingAlgorithm;

    public KeystoreConfig() {
        this("", "", "", "", "SHA256withRSA");
    }

    public KeystoreConfig(String keyStorePath, String keyAlias, String keyStorePassword, String keyPassword) {
        this(keyStorePath, keyStorePassword, keyAlias, keyPassword, "SHA256withRSA");
    }

    public KeystoreConfig(String keyStorePath, String keyAlias, String keyStorePassword, String keyPassword, boolean save) {
        this(keyStorePath, keyStorePassword, keyAlias, keyPassword, "SHA256withRSA");
    }

    public KeystoreConfig(String keyStorePath, String keyStorePassword, String keyAlias, String keyPassword, String signingAlgorithm) {
        this.keyStorePath = keyStorePath != null ? keyStorePath : "";
        this.keyStorePassword = keyStorePassword != null ? keyStorePassword : "";
        this.keyAlias = keyAlias != null ? keyAlias : "";
        this.keyPassword = keyPassword != null ? keyPassword : "";
        this.signingAlgorithm = signingAlgorithm != null && !signingAlgorithm.isEmpty() ? signingAlgorithm : "SHA256withRSA";
    }

    public static KeystoreConfig load(String scId) {
        return loadFromProject(scId);
    }

    public static KeystoreConfig loadFromProject(String scId) {
        if (scId == null || scId.isEmpty()) {
            return new KeystoreConfig();
        }
        ProjectSettings settings = new ProjectSettings(scId);
        String path = settings.getValue(SETTING_KEYSTORE_PATH, "");
        String storePass = settings.getValue(SETTING_KEYSTORE_PASSWORD, "");
        String alias = settings.getValue(SETTING_KEYSTORE_ALIAS, "");
        String keyPass = settings.getValue(SETTING_KEYSTORE_KEY_PASSWORD, "");
        String algorithm = settings.getValue(SETTING_SIGNING_ALGORITHM, "SHA256withRSA");

        // If no custom path was previously saved, fallback to default keystore path if it exists
        if (TextUtils.isEmpty(path)) {
            String defaultPath = SketchwarePaths.getKeystoreFilePath();
            if (new File(defaultPath).exists()) {
                path = defaultPath;
            }
        }

        return new KeystoreConfig(path, storePass, alias, keyPass, algorithm);
    }

    public static void save(String scId, String path, String alias, String storePass, String keyPass, boolean save) {
        if (!save || scId == null || scId.isEmpty()) {
            return;
        }
        KeystoreConfig config = new KeystoreConfig(path, storePass, alias, keyPass);
        config.saveToProject(scId);
    }

    public void save(String scId) {
        saveToProject(scId);
    }

    public void saveToProject(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        ProjectSettings settings = new ProjectSettings(scId);
        settings.setValue(SETTING_KEYSTORE_PATH, keyStorePath != null ? keyStorePath : "");
        settings.setValue(SETTING_KEYSTORE_PASSWORD, keyStorePassword != null ? keyStorePassword : "");
        settings.setValue(SETTING_KEYSTORE_ALIAS, keyAlias != null ? keyAlias : "");
        settings.setValue(SETTING_KEYSTORE_KEY_PASSWORD, keyPassword != null ? keyPassword : "");
        settings.setValue(SETTING_SIGNING_ALGORITHM, signingAlgorithm != null ? signingAlgorithm : "SHA256withRSA");
    }

    public boolean hasConfig() {
        return !TextUtils.isEmpty(keyStorePath) && !TextUtils.isEmpty(keyAlias);
    }

    public boolean isConfigured() {
        return !TextUtils.isEmpty(keyStorePath)
                && new File(keyStorePath).exists()
                && !TextUtils.isEmpty(keyAlias)
                && !TextUtils.isEmpty(keyStorePassword);
    }

    public boolean isRememberPassword() {
        return !TextUtils.isEmpty(keyStorePassword);
    }

    public String getPath() {
        return getKeyStorePath();
    }

    public String getKeyStorePath() {
        return keyStorePath != null ? keyStorePath : "";
    }

    public void setKeyStorePath(String keyStorePath) {
        this.keyStorePath = keyStorePath;
    }

    public String getPassword() {
        return getKeyStorePassword();
    }

    public String getKeyStorePassword() {
        return keyStorePassword != null ? keyStorePassword : "";
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getAlias() {
        return getKeyAlias();
    }

    public String getKeyAlias() {
        return keyAlias != null ? keyAlias : "";
    }

    public void setKeyAlias(String keyAlias) {
        this.keyAlias = keyAlias;
    }

    public String getKeyPassword() {
        return keyPassword != null ? keyPassword : "";
    }

    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    public String getEffectiveKeyPassword() {
        if (!TextUtils.isEmpty(keyPassword)) {
            return keyPassword;
        }
        return getKeyStorePassword();
    }

    public String getSigningAlgorithm() {
        return signingAlgorithm != null && !signingAlgorithm.isEmpty() ? signingAlgorithm : "SHA256withRSA";
    }

    public void setSigningAlgorithm(String signingAlgorithm) {
        this.signingAlgorithm = signingAlgorithm;
    }
}
