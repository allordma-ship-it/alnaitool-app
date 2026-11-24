package app.alnai.notes;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Fingerprint / face unlock for locked documents, through the system's own prompt (androidx.biometric).
 * The app only learns "yes" or "no"; it never sees the fingerprint.
 */
@CapacitorPlugin(name = "Biometric")
public class BiometricPlugin extends Plugin {

    private static final int AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK;

    @PluginMethod
    public void isAvailable(PluginCall call) {
        int r = BiometricManager.from(getContext()).canAuthenticate(AUTH);
        JSObject out = new JSObject();
        out.put("available", r == BiometricManager.BIOMETRIC_SUCCESS);
        out.put("code", r);
        call.resolve(out);
    }

    @PluginMethod
    public void authenticate(final PluginCall call) {
        final String title = call.getString("title", "Alnai Notes");
        final String subtitle = call.getString("subtitle", "");
        final String cancel = call.getString("cancel", "Cancel");
        final FragmentActivity activity = getActivity();
        activity.runOnUiThread(() -> {
            try {
                BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            JSObject out = new JSObject();
                            out.put("ok", true);
                            call.resolve(out);
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence message) {
                            JSObject out = new JSObject();
                            out.put("ok", false);
                            out.put("code", code);
                            out.put("message", message == null ? "" : message.toString());
                            call.resolve(out);
                        }
                        // onAuthenticationFailed: a finger that did not match; the system prompt stays open
                    });
                BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setNegativeButtonText(cancel)
                    .setAllowedAuthenticators(AUTH);
                if (subtitle != null && !subtitle.isEmpty()) info.setSubtitle(subtitle);
                prompt.authenticate(info.build());
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? "biometric failed" : e.getMessage());
            }
        });
    }
}
