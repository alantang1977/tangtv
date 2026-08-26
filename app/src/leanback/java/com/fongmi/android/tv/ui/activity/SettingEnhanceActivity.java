package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.ImportedAdRuleCandidateStore;
import com.fongmi.android.tv.api.config.RuleConfig;
import com.fongmi.android.tv.api.config.UserAdRuleStore;
import com.fongmi.android.tv.ad.audio.AdAudioRuleStore;
import com.fongmi.android.tv.ad.audio.AdAudioRuleSnapshot;
import com.fongmi.android.tv.ad.audio.AdAudioSetting;
import com.fongmi.android.tv.ad.audio.AdSkipPolicyController;
import com.fongmi.android.tv.ad.audio.SpeechAdConfig;
import com.fongmi.android.tv.ad.audio.SpeechAdSetting;
import com.fongmi.android.tv.subtitle.RealtimeSubtitleSpeechRecognitionFactory;
import com.fongmi.android.tv.bean.AudioConfig;
import com.fongmi.android.tv.bean.ShortDramaConfig;
import com.fongmi.android.tv.gitcloud.GitCloudAccountStore;
import com.fongmi.android.tv.playback.ViewingRecordSyncStore;
import com.fongmi.android.tv.remote.RemoteStore;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.databinding.ActivitySettingEnhanceBinding;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.setting.SiteNameStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.AdRuleManageDialog;
import com.fongmi.android.tv.ui.dialog.AudioSourceDialog;
import com.fongmi.android.tv.ui.dialog.ShortDramaSourceDialog;
import com.fongmi.android.tv.ui.dialog.CspWarmupDialog;
import com.fongmi.android.tv.ui.dialog.CustomCspDialog;
import com.fongmi.android.tv.ui.dialog.LightDialog;
import com.fongmi.android.tv.ui.dialog.DebugLogDialog;
import com.fongmi.android.tv.ui.dialog.GitCloudDialog;
import com.fongmi.android.tv.ui.dialog.LoginStateLearnDialog;
import com.fongmi.android.tv.ui.dialog.ManagePageDialog;
import com.fongmi.android.tv.ui.dialog.OneKeySyncDialog;
import com.fongmi.android.tv.ui.dialog.RemoteTrustDialog;
import com.fongmi.android.tv.ui.dialog.ShellProxyDialog;
import com.fongmi.android.tv.ui.dialog.SiteHealthDialog;
import com.fongmi.android.tv.ui.dialog.SiteNameDialog;
import com.fongmi.android.tv.ui.dialog.ViewingRecordSyncDialog;
import com.fongmi.android.tv.ui.dialog.WebHomeExtensionDialog;
import com.fongmi.android.tv.ui.dialog.WebHomeThemeDialog;
import com.fongmi.android.tv.utils.LoginStateSync;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SettingEnhanceActivity extends BaseActivity {

    private static final String URL_GITHUB = "https://github.com/Silent1566/webhtv";
    private static final String URL_CNB = "https://cnb.cool/fish2018/ext";

    private ActivitySettingEnhanceBinding mBinding;
    private volatile AdAudioRuleSnapshot adAudioSnapshot = AdAudioRuleStore.get().current();
    private final ActivityResultLauncher<String[]> adAudioRulePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importAdAudioRules);

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingEnhanceActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_enable : R.string.setting_disable);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingEnhanceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        reorderItems();
        mBinding.customCsp.requestFocus();
        setText();
        Task.execute(() -> {
            adAudioSnapshot = AdAudioRuleStore.get().load();
            runOnUiThread(this::setText);
        });
    }

    @Override
    protected void initEvent() {
        mBinding.githubRepo.setOnClickListener(view -> openRepo(URL_GITHUB));
        mBinding.cnbRepo.setOnClickListener(view -> openRepo(URL_CNB));
        mBinding.driveCheck.setOnClickListener(this::setDriveCheck);
        mBinding.siteName.setOnClickListener(this::setSiteName);
        mBinding.audioSource.setOnClickListener(this::setAudioSource);
        mBinding.shortDramaSource.setOnClickListener(this::setShortDramaSource);
        mBinding.adRuleManage.setOnClickListener(view -> AdRuleManageDialog.create().show(this, this::setText));
        mBinding.speechAdEnabled.setOnClickListener(this::toggleSpeechAdEnabled);
        mBinding.speechAdKeywords.setOnClickListener(this::editSpeechAdKeywords);
        mBinding.speechAdSkipSeconds.setOnClickListener(this::editSpeechAdSkipSeconds);
        mBinding.speechAdSkipMode.setOnClickListener(this::selectSpeechAdSkipMode);
        mBinding.adAudioFingerprint.setOnClickListener(this::toggleAdAudioFingerprint);
        mBinding.adAudioFingerprint.setOnLongClickListener(this::manageAdAudioRules);
        mBinding.adRuleManage.setOnClickListener(view -> AdRuleManageDialog.create().show(this, this::setText));
        mBinding.debugLog.setOnClickListener(this::setDebugLog);
        mBinding.siteHealthSort.setOnClickListener(view -> SiteHealthDialog.show(this, this::setText));
        mBinding.siteHealthSort.setOnLongClickListener(this::clearSiteHealth);
        mBinding.webHomeExtension.setOnClickListener(view -> WebHomeExtensionDialog.show(this, this::setText));
        mBinding.webHomeExtension.setOnLongClickListener(this::clearWebHomeExtension);
        mBinding.webHomeTheme.setOnClickListener(view -> WebHomeThemeDialog.show(this, this::setText));
        mBinding.webHomeFullscreen.setOnClickListener(this::setWebHomeFullscreen);
        mBinding.cspWarmup.setOnClickListener(this::setCspWarmup);
        mBinding.playbackArtworkWall.setOnClickListener(this::setPlaybackArtworkWall);
        mBinding.playbackWebhook.setOnClickListener(view -> ViewingRecordSyncDialog.show(this, this::setText));
        mBinding.managePage.setOnClickListener(view -> ManagePageDialog.show(this));
        mBinding.remoteTrust.setOnClickListener(view -> RemoteTrustDialog.show(this, this::setText));
        mBinding.gitCloud.setOnClickListener(view -> GitCloudDialog.show(this, this::setText));
        mBinding.shellProxy.setOnClickListener(view -> ShellProxyDialog.show(this, this::setText));
        mBinding.shellProxy.setOnLongClickListener(v -> false);
        mBinding.shellProxyConfig.setVisibility(View.GONE);
        mBinding.customCsp.setOnClickListener(view -> PermissionUtil.requestFile(this, granted -> {
            if (isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) return;
            if (granted) CustomCspDialog.show(this, this::setText);
            else Notify.show(R.string.setting_custom_csp_permission_required);
        }));
        mBinding.loginState.setOnClickListener(view -> LoginStateLearnDialog.show(this, this::setText));
        mBinding.oneKeySync.setOnClickListener(v -> OneKeySyncDialog.create().show(this));
    }

    private void reorderItems() {
        ViewGroup parent = (ViewGroup) mBinding.customCsp.getParent();
        View[] order = {
                mBinding.customCsp,
                mBinding.webHomeExtension,
                mBinding.gitCloud,
                mBinding.remoteTrust,
                mBinding.oneKeySync,
                mBinding.loginState,
                mBinding.shellProxy,
                mBinding.shellProxyConfig,
                mBinding.managePage,
                mBinding.webHomeTheme,
                mBinding.webHomeFullscreen,
                mBinding.cspWarmup,
                mBinding.playbackArtworkWall,
                mBinding.driveCheck,
                mBinding.siteName,
                mBinding.audioSource,
                mBinding.shortDramaSource,
                mBinding.speechAdEnabled,
                mBinding.speechAdKeywords,
                mBinding.speechAdSkipSeconds,
                mBinding.speechAdSkipMode,
                mBinding.adAudioFingerprint,
                mBinding.adRuleManage,
                mBinding.siteHealthSort,
                mBinding.debugLog,
                mBinding.playbackWebhook
        };
        for (View view : order) parent.removeView(view);
        for (View view : order) parent.addView(view);
    }

    private void setText() {
        if (!canSetText()) return;
        safeSet("driveCheck", mBinding.driveCheckText, () -> getSwitch(Setting.isDriveCheck()));
        safeSet("siteName", mBinding.siteNameText, () -> getString(R.string.setting_site_name_summary, SiteNameStore.count()));
        safeSet("audioSource", mBinding.audioSourceText, () -> getSwitch(!AudioConfig.objectFrom(Setting.getAudioConfig()).getDisplayRules().isEmpty()));
        safeSet("shortDramaSource", mBinding.shortDramaSourceText, () -> getSwitch(!ShortDramaConfig.objectFrom(Setting.getShortDramaConfig()).getDisplayRules().isEmpty()));
        SpeechAdConfig speech = SpeechAdSetting.snapshot();
        safeSet("speechAdEnabled", mBinding.speechAdEnabledText, () -> getSpeechAdEnabledText(speech));
        safeSet("speechAdKeywords", mBinding.speechAdKeywordsText, () -> getString(R.string.speech_ad_keyword_count, speech.keywords().values().size()));
        safeSet("speechAdSkipSeconds", mBinding.speechAdSkipSecondsText, () -> getString(R.string.speech_ad_skip_seconds_value, speech.skipSeconds()));
        safeSet("speechAdSkipMode", mBinding.speechAdSkipModeText, () -> speech.mode() == AdSkipPolicyController.Mode.AUTO
                ? getString(R.string.speech_ad_skip_mode_auto) : getString(R.string.speech_ad_skip_mode_prompt));
        safeSet("adAudioFingerprint", mBinding.adAudioFingerprintText, this::getAdAudioFingerprintText);
        safeSet("adRuleManage", mBinding.adRuleManageText, () -> getString(R.string.ad_rule_count_with_pending,
                UserAdRuleStore.load().size() + RuleConfig.get().getDefaultRules().size(),
                ImportedAdRuleCandidateStore.pending().size()));
        safeSet("debugLog", mBinding.debugLogText, () -> getSwitch(Setting.isDebugLog()));
        safeSet("siteHealthSort", mBinding.siteHealthSortText, this::getSiteHealthText);
        safeSet("webHomeExtension", mBinding.webHomeExtensionText, () -> {
            WebHomeExtensionRegistry.Snapshot webHomeExtension = WebHomeExtensionRegistry.get().snapshot();
            return getSwitch(Setting.isWebHomeExtension()) + " · " + webHomeExtension.readyCount + "/" + webHomeExtension.installedCount;
        });
        safeSet("webHomeTheme", mBinding.webHomeThemeText, () -> WebHomeThemeDialog.summary(this));
        safeSet("webHomeFullscreen", mBinding.webHomeFullscreenText, () -> getSwitch(Setting.isWebHomeFullscreen()));
        safeSet("cspWarmup", mBinding.cspWarmupText, this::getCspWarmupText);
        safeSet("playbackArtworkWall", mBinding.playbackArtworkWallText, () -> getSwitch(Setting.isPlaybackArtworkWall()));
        safeSet("playbackWebhook", mBinding.playbackWebhookText, () -> ViewingRecordSyncStore.summary(this));
        safeSet("managePage", mBinding.managePageText, () -> getString(R.string.manage_page_web));
        safeSet("remoteTrust", mBinding.remoteTrustText, () -> RemoteStore.summary(this));
        safeSet("gitCloud", mBinding.gitCloudText, () -> getString(R.string.git_cloud_account_count, GitCloudAccountStore.list().size()));
        setShellProxyText();
        setCustomCspText();
        safeSet("loginState", mBinding.loginStateText, () -> {
            int learned = LoginStateSync.learnedCount();
            int pending = LoginStateSync.pendingPaths().size();
            return getString(LoginStateSync.hasLearningSnapshot() ? R.string.login_state_learning_count : R.string.login_state_count, learned, pending);
        });
    }

    private boolean canSetText() {
        return mBinding != null && !isFinishing() && !isDestroyed();
    }

    private void setShellProxyText() {
        safeRun("shellProxy", () -> {
            int count = ProxySetting.count();
            mBinding.shellProxyText.setText(getSwitch(Setting.isShellProxy()) + " · " + getString(R.string.setting_proxy_rule_count, count));
            mBinding.shellProxyConfigText.setText(getString(R.string.setting_proxy_rule_count, count));
        }, () -> {
            setError(mBinding.shellProxyText);
            setError(mBinding.shellProxyConfigText);
        });
    }

    private void setCustomCspText() {
        safeRun("customCsp", () -> {
            CustomCspSetting.Status status = CustomCspSetting.status();
            if (!status.available()) {
                setError(mBinding.customCspText);
                return;
            }
            CustomCspSetting.Count count = status.count();
            mBinding.customCspText.setText(getSwitch(status.enabled()) + " · " + getString(R.string.setting_custom_csp_count, count.active(), count.enabled()));
        }, () -> setError(mBinding.customCspText));
    }

    private void safeSet(String name, TextView view, TextSupplier supplier) {
        safeRun(name, () -> view.setText(supplier.get()), () -> setError(view));
    }

    private void safeRun(String name, Runnable action, Runnable fallback) {
        try {
            action.run();
        } catch (Throwable e) {
            SpiderDebug.log("enhance", "summary failed item=%s error=%s", name, e.toString());
            if (fallback == null) return;
            try {
                fallback.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private void setError(TextView view) {
        if (view != null) view.setText(R.string.error_config_get);
    }

    private interface TextSupplier {
        CharSequence get();
    }

    private void setDriveCheck(View view) {
        Setting.putDriveCheck(!Setting.isDriveCheck());
        mBinding.driveCheckText.setText(getSwitch(Setting.isDriveCheck()));
    }

    private void setDebugLog(View view) {
        Setting.putDebugLog(!Setting.isDebugLog());
        mBinding.debugLogText.setText(getSwitch(Setting.isDebugLog()));
        if (!Setting.isDebugLog()) return;
        DebugLogDialog.show(this);
    }

    private void setSiteName(View view) {
        SiteNameDialog.create(this).onChanged(this::setText).show();
    }

    private void setAudioSource(View view) {
        AudioSourceDialog.create(this).onDismiss(this::setText).show();
    }

    private void setShortDramaSource(View view) {
        ShortDramaSourceDialog.create(this).onDismiss(this::setText).show();
    }

    private String getSiteHealthText() {
        SiteHealthStore.Summary summary = SiteHealthStore.summary();
        String state = getSwitch(Setting.isSiteHealthSort());
        if (summary.siteCount <= 0) return state;
        return state + " · " + getString(R.string.site_health_report_setting_summary, summary.siteCount, summary.sampleCount);
    }

    private void setPlaybackArtworkWall(View view) {
        Setting.putPlaybackArtworkWall(!Setting.isPlaybackArtworkWall());
        mBinding.playbackArtworkWallText.setText(getSwitch(Setting.isPlaybackArtworkWall()));
    }

    private void setWebHomeFullscreen(View view) {
        Setting.putWebHomeFullscreen(!Setting.isWebHomeFullscreen());
        mBinding.webHomeFullscreenText.setText(getSwitch(Setting.isWebHomeFullscreen()));
    }

    private void setCspWarmup(View view) {
        CspWarmupDialog.show(this, this::setText);
    }

    private String getCspWarmupText() {
        int mode = Setting.getCspWarmupMode();
        if (mode == Setting.CSP_WARMUP_CUSTOM) return getString(R.string.setting_csp_warmup_custom_count, Setting.getCspWarmupSites().size());
        return getString(mode == Setting.CSP_WARMUP_DEFAULT ? R.string.setting_csp_warmup_default : R.string.setting_disable);
    }

    private boolean clearSiteHealth(View view) {
        SiteHealthStore.clear();
        Notify.show(R.string.site_health_clear_done);
        return true;
    }

    private String getSpeechAdEnabledText(SpeechAdConfig speech) {
        String text = getSwitch(speech.enabled());
        if (speech.enabled() && !RealtimeSubtitleSpeechRecognitionFactory.isSelectedModelReady()) {
            text += " · " + getString(R.string.speech_ad_model_not_ready);
        }
        return text;
    }

    private void toggleSpeechAdEnabled(View view) {
        SpeechAdSetting.setEnabled(!SpeechAdSetting.snapshot().enabled());
        notifyAdAudioRuntime();
        setText();
    }

    private void editSpeechAdKeywords(View view) {
        SpeechAdConfig speech = SpeechAdSetting.snapshot();
        EditText input = new EditText(this);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setSingleLine(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(String.join("\n", speech.keywords().values()));
        input.setSelectAllOnFocus(false);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.Theme_WebHTV_LightDialog)
                .setTitle(R.string.speech_ad_keywords)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            SpeechAdSetting.setKeywords(input.getText().toString());
            notifyAdAudioRuntime();
            setText();
            dialog.dismiss();
        }));
        dialog.show();
        LightDialog.apply(dialog);
    }

    private void editSpeechAdSkipSeconds(View view) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);
        input.setText(String.valueOf(SpeechAdSetting.snapshot().skipSeconds()));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.Theme_WebHTV_LightDialog)
                .setTitle(R.string.speech_ad_skip_seconds)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = input.getText().toString().trim();
            try {
                int seconds = Integer.parseInt(value);
                if (seconds < 1 || seconds > 120) throw new NumberFormatException();
                SpeechAdSetting.setSkipSeconds(seconds);
                notifyAdAudioRuntime();
                setText();
                dialog.dismiss();
            } catch (NumberFormatException error) {
                input.setError(getString(R.string.speech_ad_skip_seconds_invalid));
                input.requestFocus();
            }
        }));
        dialog.show();
        LightDialog.apply(dialog);
    }

    private void selectSpeechAdSkipMode(View view) {
        SpeechAdConfig speech = SpeechAdSetting.snapshot();
        String[] modes = {
                getString(R.string.speech_ad_skip_mode_prompt),
                getString(R.string.speech_ad_skip_mode_auto)
        };
        int checked = speech.mode() == AdSkipPolicyController.Mode.AUTO ? 1 : 0;
        AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.Theme_WebHTV_LightDialog)
                .setTitle(R.string.speech_ad_skip_mode)
                .setNegativeButton(R.string.dialog_negative, null)
                .setSingleChoiceItems(modes, checked, (shown, which) -> {
                    SpeechAdSetting.setMode(which == 1
                            ? AdSkipPolicyController.Mode.AUTO
                            : AdSkipPolicyController.Mode.PROMPT);
                    notifyAdAudioRuntime();
                    setText();
                    shown.dismiss();
                })
                .create();
        dialog.show();
        LightDialog.apply(dialog);
    }
    private String getAdAudioFingerprintText() {
        String enabled = getSwitch(AdAudioSetting.isEnabled());
        AdAudioRuleSnapshot snapshot = adAudioSnapshot;
        if (snapshot == null || snapshot.hasError()) {
            return enabled + " · " + getString(R.string.setting_ad_audio_rule_error);
        }
        int count = snapshot.ruleSet().rules().size();
        if (count == 0) return enabled + " · " + getString(R.string.setting_ad_audio_no_rules);
        return enabled + " · " + getString(R.string.setting_ad_audio_rule_count, count, snapshot.version());
    }

    private void toggleAdAudioFingerprint(View view) {
        AdAudioSetting.setEnabled(!AdAudioSetting.isEnabled());
        notifyAdAudioRuntime();
        setText();
    }

    private boolean manageAdAudioRules(View view) {
        new MaterialAlertDialogBuilder(this, R.style.Theme_WebHTV_LightDialog)
                .setTitle(R.string.setting_ad_audio_title)
                .setItems(new String[]{
                        getString(R.string.setting_ad_audio_import),
                        getString(R.string.setting_ad_audio_clear)
                }, (dialog, which) -> {
                    if (which == 0) {
                        adAudioRulePicker.launch(new String[]{"application/json", "text/json"});
                    } else {
                        confirmClearAdAudioRules();
                    }
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
        return true;
    }

    private void importAdAudioRules(Uri uri) {
        if (uri == null) return;
        Task.execute(() -> {
            String message;
            try {
                adAudioSnapshot = AdAudioRuleStore.get().importUri(getContentResolver(), uri);
                message = getString(R.string.setting_ad_audio_imported,
                        adAudioSnapshot.ruleSet().rules().size(), adAudioSnapshot.version());
            } catch (Exception e) {
                message = getString(R.string.setting_ad_audio_import_failed);
            }
            String result = message;
            runOnUiThread(() -> {
                if (!canSetText()) return;
                Notify.show(result);
                notifyAdAudioRuntime();
                setText();
            });
        });
    }

    private void confirmClearAdAudioRules() {
        new MaterialAlertDialogBuilder(this, R.style.Theme_WebHTV_LightDialog)
                .setTitle(R.string.setting_ad_audio_clear)
                .setMessage(R.string.setting_ad_audio_clear_confirm)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> Task.execute(() -> {
                    try {
                        adAudioSnapshot = AdAudioRuleStore.get().clear();
                        runOnUiThread(() -> {
                            if (!canSetText()) return;
                            Notify.show(R.string.setting_ad_audio_clear_done);
                            notifyAdAudioRuntime();
                            setText();
                        });
                    } catch (RuntimeException e) {
                        runOnUiThread(() -> Notify.show(R.string.setting_ad_audio_import_failed));
                    }
                }))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void notifyAdAudioRuntime() {
        PlaybackService service = Server.get().getService();
        if (service == null || service.player() == null || service.player().isReleased()) return;
        service.player().reloadAdAudioSettings();
    }

    private boolean clearWebHomeExtension(View view) {
        WebHomeExtensionRegistry.get().clear();
        Notify.show(R.string.web_home_extension_clear_done);
        return true;
    }

    private void openRepo(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Notify.show(R.string.manage_page_no_browser);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setText();
    }
}
