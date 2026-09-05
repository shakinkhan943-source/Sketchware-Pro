package pro.sketchware.dialogs;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.LinkedList;

import dev.pranav.filepicker.FilePickerCallback;
import dev.pranav.filepicker.FilePickerDialogFragment;
import dev.pranav.filepicker.FilePickerOptions;
import dev.pranav.filepicker.SelectionMode;
import pro.sketchware.R;
import pro.sketchware.core.build.KeystoreConfig;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.databinding.DialogKeystoreCredentialsBinding;
import pro.sketchware.util.Helper;
import pro.sketchware.util.SketchwareUtil;

public class GetKeyStoreCredentialsDialog {

    private final AlertDialog dialog;
    private final DialogKeystoreCredentialsBinding binding;
    private final Activity activity;
    private CredentialsReceiver receiver;
    private SigningMode mode = SigningMode.OWN_KEY_STORE;

    public GetKeyStoreCredentialsDialog(Activity activity, int iconResourceId, String title, String noticeText) {
        this.activity = activity;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        builder.setIcon(iconResourceId);
        builder.setTitle(title);
        builder.setMessage(noticeText);
        builder.setNegativeButton(Helper.getResString(R.string.common_word_cancel), null);
        builder.setPositiveButton(Helper.getResString(R.string.common_word_next), null);

        binding = DialogKeystoreCredentialsBinding.inflate(LayoutInflater.from(activity));
        builder.setView(binding.getRoot());

        dialog = builder.create();

        setupSpinner(activity);
        setupFilePicker();

        // Default keystore path if default exists
        String defaultPath = SketchwarePaths.getKeystoreFilePath();
        if (new File(defaultPath).exists()) {
            binding.etPath.setText(defaultPath);
        }

        updateInputFieldsState();
    }

    private void setupSpinner(Activity activity) {
        String[] dropdownItems = getDropdownItems();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, dropdownItems);
        binding.actSigningMode.setAdapter(adapter);
        binding.actSigningMode.setText(mode.label, false);
        binding.actSigningMode.setOnItemClickListener((parent, view, position, id) -> {
            mode = SigningMode.values()[position];
            updateInputFieldsState();
        });
    }

    private void setupFilePicker() {
        binding.tilPath.setEndIconOnClickListener(v -> {
            if (activity instanceof FragmentActivity fragmentActivity) {
                FilePickerOptions options = new FilePickerOptions();
                options.setSelectionMode(SelectionMode.FILE);
                options.setTitle(Helper.getResString(R.string.keystore_hint_path));
                FilePickerCallback callback = new FilePickerCallback() {
                    @Override
                    public void onFileSelected(File file) {
                        binding.etPath.setText(file.getAbsolutePath());
                        binding.tilPath.setError(null);
                    }
                };
                new FilePickerDialogFragment(options, callback).show(fragmentActivity.getSupportFragmentManager(), "keystore_file_picker");
            }
        });
    }

    private String[] getDropdownItems() {
        LinkedList<String> labels = new LinkedList<>();
        for (SigningMode mode : SigningMode.values()) {
            labels.add(mode.label);
        }
        return labels.toArray(new String[0]);
    }

    private void updateInputFieldsState() {
        boolean signingWithKeyStore = mode == SigningMode.OWN_KEY_STORE;
        binding.tilPath.setEnabled(signingWithKeyStore);
        binding.tilAlias.setEnabled(signingWithKeyStore);
        binding.tilPassword.setEnabled(signingWithKeyStore);
        binding.tilKeyPassword.setEnabled(signingWithKeyStore);
        binding.cbSaveKeystore.setEnabled(signingWithKeyStore);
        binding.tilSigningAlgorithm.setEnabled(mode != SigningMode.DONT_SIGN);
    }

    public void setKeystoreConfig(KeystoreConfig config) {
        if (config != null) {
            if (!TextUtils.isEmpty(config.getPath())) {
                binding.etPath.setText(config.getPath());
            }
            if (!TextUtils.isEmpty(config.getAlias())) {
                binding.etAlias.setText(config.getAlias());
            }
            if (!TextUtils.isEmpty(config.getPassword())) {
                binding.etPassword.setText(config.getPassword());
            }
            if (!TextUtils.isEmpty(config.getKeyPassword())) {
                binding.etKeyPassword.setText(config.getKeyPassword());
            }
            binding.cbSaveKeystore.setChecked(config.isRememberPassword() || config.hasConfig());
        }
    }

    public void setPath(String path) {
        binding.etPath.setText(path);
    }

    public void setAlias(String alias) {
        binding.etAlias.setText(alias);
    }

    public void setPassword(String password) {
        binding.etPassword.setText(password);
    }

    public void setKeyPassword(String keyPassword) {
        binding.etKeyPassword.setText(keyPassword);
    }

    private void onNextButtonClick(DialogInterface dialogInterface) {
        if (mode == SigningMode.OWN_KEY_STORE) {
            if (validateInputs()) {
                String keyPass = Helper.getText(binding.etKeyPassword);
                if (TextUtils.isEmpty(keyPass)) {
                    keyPass = Helper.getText(binding.etPassword);
                }
                dialogInterface.dismiss();
                if (receiver != null) {
                    receiver.gotCredentials(new Credentials(
                            false,
                            Helper.getText(binding.etPath),
                            Helper.getText(binding.etPassword),
                            Helper.getText(binding.etAlias),
                            keyPass,
                            Helper.getText(binding.etSigningAlgorithm),
                            binding.cbSaveKeystore.isChecked()
                    ));
                }
            }
        } else if (mode == SigningMode.TESTKEY) {
            dialogInterface.dismiss();
            if (receiver != null) {
                receiver.gotCredentials(new Credentials(Helper.getText(binding.etSigningAlgorithm)));
            }
        } else if (mode == SigningMode.DONT_SIGN) {
            dialogInterface.dismiss();
            if (receiver != null) {
                receiver.gotCredentials(null);
            }
        }
    }

    private boolean validateInputs() {
        boolean isValid = true;

        String path = Helper.getText(binding.etPath);
        if (TextUtils.isEmpty(path)) {
            binding.tilPath.setError(Helper.getResString(R.string.error_keystore_path_empty));
            isValid = false;
        } else if (!new File(path).exists()) {
            binding.tilPath.setError(Helper.getResString(R.string.error_keystore_file_not_found));
            isValid = false;
        } else {
            binding.tilPath.setError(null);
        }

        if (TextUtils.isEmpty(binding.etAlias.getText())) {
            binding.tilAlias.setError(Helper.getResString(R.string.error_alias_empty));
            isValid = false;
        } else {
            binding.tilAlias.setError(null);
        }

        if (TextUtils.isEmpty(binding.etPassword.getText())) {
            binding.tilPassword.setError(Helper.getResString(R.string.error_password_empty));
            isValid = false;
        } else {
            binding.tilPassword.setError(null);
        }

        if (TextUtils.isEmpty(binding.etSigningAlgorithm.getText())) {
            binding.tilSigningAlgorithm.setError(Helper.getResString(R.string.error_algorithm_empty));
            isValid = false;
        } else {
            binding.tilSigningAlgorithm.setError(null);
        }

        return isValid;
    }

    public void show() {
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> onNextButtonClick(dialog));
        if (TextUtils.isEmpty(binding.etAlias.getText())) {
            binding.etAlias.requestFocus();
        } else if (TextUtils.isEmpty(binding.etPassword.getText())) {
            binding.etPassword.requestFocus();
        }
    }

    public void setListener(CredentialsReceiver receiver) {
        this.receiver = receiver;
    }

    private enum SigningMode {
        OWN_KEY_STORE("Sign using keystore"),
        TESTKEY("Sign using a test key"),
        DONT_SIGN("Don't sign");

        private final String label;

        SigningMode(String label) {
            this.label = label;
        }
    }

    public interface CredentialsReceiver {
        /**
         * @param credentials The {@link Credentials} object made from user input.
         *                    May be null. In that case, the user disabled signing the file.
         */
        void gotCredentials(Credentials credentials);
    }

    public static class Credentials {

        private final boolean signWithTestkey;
        private final String keyStorePath;
        private final String keyStorePassword;
        private final String keyAlias;
        private final String keyPassword;
        private final String signingAlgorithm;
        private final boolean saveKeystore;

        /**
         * Constructs a credentials holder configured to sign with testkey,
         * meaning that no key store, aliases, and passwords were entered.
         */
        public Credentials(String signingAlgorithm) {
            this(true, null, null, null, null, signingAlgorithm, false);
        }

        /**
         * Constructs a credentials holder configured to sign with a private key taken from a key store.
         */
        public Credentials(String signingAlgorithm, String keyPassword, String keyAlias, String keyStorePassword) {
            this(false, SketchwarePaths.getKeystoreFilePath(), keyStorePassword, keyAlias, keyPassword, signingAlgorithm, false);
        }

        public Credentials(String signingAlgorithm, String keyStorePath, String keyStorePassword, String keyAlias, String keyPassword, boolean saveKeystore) {
            this(false, keyStorePath, keyStorePassword, keyAlias, keyPassword, signingAlgorithm, saveKeystore);
        }

        public Credentials(boolean signWithTestkey, String keyStorePath, String keyStorePassword, String keyAlias, String keyPassword, String signingAlgorithm, boolean saveKeystore) {
            this.signWithTestkey = signWithTestkey;
            this.keyStorePath = keyStorePath;
            this.keyStorePassword = keyStorePassword;
            this.keyAlias = keyAlias;
            this.keyPassword = keyPassword != null && !keyPassword.isEmpty() ? keyPassword : keyStorePassword;
            this.signingAlgorithm = signingAlgorithm;
            this.saveKeystore = saveKeystore;
        }

        /**
         * @return False if this credentials holder contains credentials for signing, true if not.
         */
        public boolean isForSigningWithTestkey() {
            return signWithTestkey;
        }

        /**
         * @return {@link #keyStorePath}
         */
        public String getKeyStorePath() {
            return keyStorePath;
        }

        /**
         * @return {@link #keyStorePassword}
         */
        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        /**
         * @return {@link #keyAlias}
         */
        public String getKeyAlias() {
            return keyAlias;
        }

        /**
         * @return {@link #keyPassword}
         */
        public String getKeyPassword() {
            return keyPassword;
        }

        /**
         * @return {@link #signingAlgorithm}
         */
        public String getSigningAlgorithm() {
            return signingAlgorithm;
        }

        /**
         * @return {@link #saveKeystore}
         */
        public boolean isSaveKeystore() {
            return saveKeystore;
        }
    }
}
