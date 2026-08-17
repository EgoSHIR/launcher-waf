package me.nillerusr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.valvesoftware.portal2.R;

public class SteamLoginActivity extends Activity {
    private static final String TAG = "SteamLogin";
    private SteamSessionManager sessionManager;
    private View loginScroll, profileScroll, loadingOverlay, loginContainer, profileContainer;
    private TextView statusText, profilePersonaName, profileSteamId;
    private ImageView loadingIcon;
    private EditText inputAccount, inputPassword;
    private Button btnSignIn, btnApprovePhone;
    private boolean isProcessing;
    private String savedUsername, savedPassword;
    private RotateAnimation loadingAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_steam_login);
        sessionManager = new SteamSessionManager(this);

        loginScroll = findViewById(R.id.login_scroll);
        profileScroll = findViewById(R.id.profile_scroll);
        loginContainer = findViewById(R.id.login_container);
        profileContainer = findViewById(R.id.profile_container);
        loadingOverlay = findViewById(R.id.loading_overlay);
        statusText = findViewById(R.id.status_text);
        loadingIcon = findViewById(R.id.loading_icon);
        profilePersonaName = findViewById(R.id.profile_persona_name);
        profileSteamId = findViewById(R.id.profile_steam_id);
        inputAccount = findViewById(R.id.input_account);
        inputPassword = findViewById(R.id.input_password);
        btnSignIn = findViewById(R.id.btn_sign_in);
        btnApprovePhone = findViewById(R.id.btn_approve_phone);

        loadingAnim = new RotateAnimation(0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        loadingAnim.setInterpolator(new LinearInterpolator());
        loadingAnim.setDuration(1200);
        loadingAnim.setRepeatCount(Animation.INFINITE);

        String savedRefresh = sessionManager.getRequestToken();
        String savedPass = sessionManager.getPassword();
        String savedUser = sessionManager.getUsername();

        if (SteamDirectAuth.isLikelyJwt(savedRefresh)) {
            SteamProfile p = sessionManager.loadSession();
            if (p != null) showProfile(p);
            else showLoginForm();
        } else if (sessionManager.isLoggedIn()) {
            showProfile(sessionManager.loadSession());
        } else {
            showLoginForm();
        }

        btnSignIn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (isProcessing) return;
                String user = inputAccount.getText().toString().trim();
                String pass = inputPassword.getText().toString();
                if (user.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(SteamLoginActivity.this, "Enter account and password", Toast.LENGTH_LONG).show();
                    return;
                }
                savedUsername = user;
                savedPassword = pass;
                showGuardDialog();
            }
        });

        btnApprovePhone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (isProcessing) return;
                String user = inputAccount.getText().toString().trim();
                String pass = inputPassword.getText().toString();
                if (user.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(SteamLoginActivity.this, "Enter account and password", Toast.LENGTH_LONG).show();
                    return;
                }
                savedUsername = user;
                savedPassword = pass;
                setProcessing(true);
                showLoading("Requesting approval...\nCheck Steam App on your phone");
                sessionManager.savePassword(savedPassword);
                exchangeJwt(null, null);
            }
        });

        findViewById(R.id.btn_logout).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sessionManager.clearSession();
                savedUsername = savedPassword = null;
                showLoginForm();
                Toast.makeText(SteamLoginActivity.this, "Logged out", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_view_profile).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SteamProfile p = sessionManager.loadSession();
                if (p != null && p.profileUrl != null)
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(p.profileUrl)));
            }
        });
    }

    private void showGuardDialog() {
        hideLoading();
        final EditText input = new EditText(this);
        input.setHint("2FA code (or leave empty for phone approval)");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);

        new AlertDialog.Builder(this)
                .setTitle("Steam Guard")
                .setMessage("Enter code from Steam App,\nor leave empty to send push")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("SIGN IN", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String code = input.getText().toString().trim();
                        setProcessing(true);
                        showLoading(code.isEmpty() ? "Waiting for approval..." : "Verifying...");
                        sessionManager.savePassword(savedPassword);
                        exchangeJwt(code.isEmpty() ? null : code, null);
                    }
                })
                .setNegativeButton("CANCEL", null).show();
    }

    private void exchangeJwt(final String guardCode, final String guardData) {
        if (savedUsername == null || savedPassword == null) {
            handleError("Session expired");
            return;
        }
        SteamDirectAuth.exchangeForJwt(null, savedPassword, savedUsername, guardCode, guardData,
            new SteamDirectAuth.Callback<SteamDirectAuth.JwtResult>() {
            public void onSuccess(final SteamDirectAuth.JwtResult jwt) {
                sessionManager.saveAuthData(savedUsername, jwt.requestToken);
                final String steamId = SteamDirectAuth.jwtSub(jwt.refreshToken);
                if (steamId != null) {
                    SteamApiClient.fetchProfile(steamId,
                        new SteamApiClient.Callback<SteamProfile>() {
                        public void onSuccess(SteamProfile profile) {
                            sessionManager.saveSession(profile);
                            runOnUiThread(new Runnable() {
                                public void run() { finishOk(profile); }
                            });
                        }
                        public void onError(String error) {
                            runOnUiThread(new Runnable() {
                                public void run() { finishOk(null); }
                            });
                        }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        public void run() { finishOk(null); }
                    });
                }
            }
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        android.util.Log.i(TAG, "exchangeJwt error: " + error);
                        if ("needs 2fa".equals(error) || "poll timeout".equals(error) || "auth session fail".equals(error)) {
                            sessionManager.savePassword(savedPassword);
                            showGuardDialog();
                        } else {
                            handleError(error != null ? error : "Login failed");
                        }
                    }
                });
            }
        });
    }

    private void finishOk(SteamProfile profile) {
        hideLoading();
        setProcessing(false);
        if (profile != null) showProfile(profile);
        Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void handleError(String msg) {
        setProcessing(false);
        hideLoading();
        showLoginForm();
        Toast.makeText(this, msg != null ? msg : "Login failed", Toast.LENGTH_LONG).show();
    }

    private void setProcessing(boolean v) {
        isProcessing = v;
        btnSignIn.setEnabled(!v);
        btnApprovePhone.setEnabled(!v);
    }

    private void showProfile(SteamProfile profile) {
        hideLoading();
        loginScroll.setVisibility(View.GONE);
        loginContainer.setVisibility(View.GONE);
        profilePersonaName.setText(profile != null && profile.personaName != null
                ? profile.personaName : "Unknown");
        profileSteamId.setText(profile != null && profile.steamId64 != null
                ? profile.steamId64 : "Unknown");
        profileContainer.setVisibility(View.VISIBLE);
        profileScroll.setVisibility(View.VISIBLE);
    }

    private void showLoginForm() {
        hideLoading();
        profileScroll.setVisibility(View.GONE);
        profileContainer.setVisibility(View.GONE);
        loginContainer.setVisibility(View.VISIBLE);
        loginScroll.setVisibility(View.VISIBLE);
    }

    private void showLoading(String msg) {
        loadingOverlay.setVisibility(View.VISIBLE);
        statusText.setText(msg);
        loadingIcon.startAnimation(loadingAnim);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
        loadingIcon.clearAnimation();
    }
}
