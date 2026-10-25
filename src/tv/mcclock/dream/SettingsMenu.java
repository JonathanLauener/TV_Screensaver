package tv.mcclock.dream;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The settings menu ("Einstellungen"): shortcuts to pages of the TV's own settings, as a server
 * list. Pages this TV doesn't have are left out. The network entry shows the Wi-Fi signal as
 * the signal bars.
 */
final class SettingsMenu implements McMenu.Content {

    /** Opens a settings page (they're side panels over the home screen on this TV). */
    interface Opener {
        void openPage(Intent page);
    }

    // Sony's pages that have no standard action. Sony answers the standard sound action with a
    // page holding just the system sounds; its speaker choice (TV or audio system) is more use.
    private static final String SONY = "com.android.tv.settings/com.sony.dtv.settings.";
    private static final String SONY_SPEAKERS = "com.sony.dtv.settings.intent.action.SPEAKERS_SETTINGS";
    private static final String[] POWER = {SONY + "power.EcoActivity"};
    private static final String[] INPUTS = {SONY + "externalinputs.ExternalInputsActivity"};
    private static final String[] ABOUT = {SONY + "about.AboutActivity"};

    private final Context context;
    private final Opener opener;

    SettingsMenu(Context context, Opener opener) {
        this.context = context;
        this.opener = opener;
    }

    @Override
    public String title() {
        return context.getString(R.string.settings);
    }

    @Override
    public List<McMenu.Entry> entries() {
        List<McMenu.Entry> out = new ArrayList<>();
        int[] signal = new int[1];
        String network = networkState(signal);
        add(out, Pages.action(context, Settings.ACTION_WIFI_SETTINGS), Icons.NETWORK,
                R.string.settings_network, network, signal[0]);
        Intent speakers = Pages.action(context, SONY_SPEAKERS);
        if (speakers != null) {
            add(out, speakers, Icons.SOUND, R.string.settings_sound,
                    context.getString(R.string.settings_sound_speakers), McMenu.NO_SIGNAL);
        } else {
            add(out, Pages.action(context, Settings.ACTION_SOUND_SETTINGS), Icons.SOUND,
                    R.string.settings_sound, context.getString(R.string.settings_sound_info),
                    McMenu.NO_SIGNAL);
        }
        add(out, Pages.action(context, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), Icons.APPS,
                R.string.settings_apps, context.getString(R.string.settings_apps_info),
                McMenu.NO_SIGNAL);
        add(out, Pages.action(context, Settings.ACTION_DATE_SETTINGS), Icons.CLOCK,
                R.string.settings_date, today(), McMenu.NO_SIGNAL);
        add(out, Pages.first(context, POWER), Icons.POWER, R.string.settings_power,
                context.getString(R.string.settings_power_info), McMenu.NO_SIGNAL);
        add(out, Pages.first(context, INPUTS), Icons.INPUTS, R.string.settings_inputs,
                context.getString(R.string.settings_inputs_info), McMenu.NO_SIGNAL);
        Intent about = Pages.first(context, ABOUT);
        if (about == null) about = Pages.action(context, Settings.ACTION_DEVICE_INFO_SETTINGS);
        add(out, about, Icons.INFO, R.string.settings_about,
                context.getString(R.string.settings_about_info, Build.MODEL, Build.VERSION.RELEASE),
                McMenu.NO_SIGNAL);
        add(out, allSettings(), Icons.SETTINGS, R.string.settings_all,
                context.getString(R.string.settings_all_info), McMenu.NO_SIGNAL);
        return out;
    }

    private void add(List<McMenu.Entry> out, Intent page, String icon, int name, String line,
            int signal) {
        if (page == null) return;
        out.add(new McMenu.Entry(icon, icon, context.getString(name), line, null, signal,
                () -> opener.openPage(page)));
    }

    private Intent allSettings() {
        return Pages.action(context, Settings.ACTION_SETTINGS);
    }

    /** "WLAN: verbunden" and its signal in bars, Ethernet as full bars, or not connected. */
    @SuppressWarnings("deprecation")  // NetworkInfo: the API 24-28 way, and still works
    private String networkState(int[] signal) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
        if (active == null || !active.isConnected()) {
            signal[0] = McMenu.UNREACHABLE;
            return context.getString(R.string.settings_network_none);
        }
        if (active.getType() == ConnectivityManager.TYPE_WIFI) {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(
                    Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            signal[0] = info == null ? 5 : WifiManager.calculateSignalLevel(info.getRssi(), 5) + 1;
            return context.getString(R.string.settings_network_wifi);
        }
        signal[0] = 5;
        return context.getString(active.getType() == ConnectivityManager.TYPE_ETHERNET
                ? R.string.settings_network_ethernet : R.string.settings_network_other);
    }

    /**
     * "Sonntag, 25. Oktober 2026", in the TV's language if the menu has it (German or English),
     * else in English like the rest of the menu (the pixel font has no other scripts).
     */
    private String today() {
        Locale locale = Locale.getDefault();
        String lang = locale.getLanguage();
        if (!"de".equals(lang) && !"en".equals(lang)) locale = Locale.US;
        String pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy");
        return new SimpleDateFormat(pattern, locale).format(new Date());
    }

    @Override
    public String emptyText() {
        return context.getString(R.string.settings_none);
    }

    @Override
    public String openLabel() {
        return context.getString(R.string.settings_open);
    }

    @Override
    public String extraLabel() {
        return context.getString(R.string.settings_all);
    }

    @Override
    public Intent extra() {
        return allSettings();
    }

    @Override
    public void opened(McMenu menu) {
    }

    @Override
    public void closed() {
    }
}
