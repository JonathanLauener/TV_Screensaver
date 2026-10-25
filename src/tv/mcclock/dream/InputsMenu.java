package tv.mcclock.dream;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The input menu ("Quelle"): the TV's HDMI inputs as a server list. A device found behind a
 * port over HDMI-CEC is its second line, and the port's state shows as the signal bars: green
 * when it's ready, grey for no signal, a red cross when nothing is plugged in.
 *
 * Android reports an HDMI port without a hotplug signal as standby ("unknown": the device may
 * be off or absent), so that's "no signal" here rather than a claim about what's plugged in.
 */
final class InputsMenu implements McMenu.Content {

    /** Opens one input (the home screen frees the video decoder first). */
    interface Opener {
        void openInput(String id);
    }

    // Sony's page for renaming and hiding inputs (Settings > Externe Eingänge).
    private static final String[] EDIT_PAGES = {
            "com.android.tv.settings/com.sony.dtv.settings.externalinputs.ExternalInputsActivity"};

    private final Context context;
    private final Opener opener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private McMenu menu;
    private final TvInputManager.TvInputCallback changes = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            refresh();
        }

        @Override
        public void onInputAdded(String inputId) {
            refresh();
        }

        @Override
        public void onInputRemoved(String inputId) {
            refresh();
        }

        @Override
        public void onInputUpdated(String inputId) {
            refresh();
        }
    };

    InputsMenu(Context context, Opener opener) {
        this.context = context;
        this.opener = opener;
    }

    private TvInputManager tim() {
        return (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
    }

    private void refresh() {
        if (menu != null) menu.refresh();
    }

    @Override
    public String title() {
        return context.getString(R.string.inputs);
    }

    @Override
    public List<McMenu.Entry> entries() {
        List<McMenu.Entry> out = new ArrayList<>();
        TvInputManager tim = tim();
        if (tim == null) return out;
        List<TvInputInfo> all = tim.getTvInputList();
        List<TvInputInfo> ports = new ArrayList<>();
        for (TvInputInfo in : all) {
            if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null
                    && !in.isHidden(context)) {
                ports.add(in);
            }
        }
        Collections.sort(ports, InputsMenu::byPort);
        PackageManager pm = context.getPackageManager();
        for (TvInputInfo port : ports) {
            String id = port.getId();
            // The devices behind it (HDMI-CEC), by name.
            StringBuilder devices = new StringBuilder();
            for (TvInputInfo in : all) {
                if (!id.equals(in.getParentId()) || in.isHidden(context)) continue;
                CharSequence name = in.loadLabel(context);
                // Without a name of its own a device gets its input service's: not a name.
                CharSequence service = in.getServiceInfo() == null ? null
                        : in.getServiceInfo().loadLabel(pm);
                if (name == null || name.length() == 0 || name.toString().equals(
                        service == null ? null : service.toString())) {
                    continue;
                }
                if (devices.length() > 0) devices.append(", ");
                devices.append(name);
            }
            int state = tim.getInputState(id);
            boolean ready = state == TvInputManager.INPUT_STATE_CONNECTED;
            String stateText;
            int signal;
            if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
                stateText = context.getString(R.string.input_disconnected);
                signal = McMenu.UNREACHABLE;
            } else if (state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) {
                stateText = context.getString(R.string.input_no_signal);
                signal = 0;
            } else {
                stateText = context.getString(R.string.input_ready);
                signal = 5;
            }
            String defaultName = label(port.loadLabel(context), "HDMI");
            String name = label(port.loadCustomLabel(context), defaultName);
            // Renamed in the TV's settings: say which port it is too.
            String line1 = devices.length() > 0 ? devices.toString()
                    : name.equals(defaultName) ? stateText : defaultName;
            String line2 = devices.length() > 0 || !name.equals(defaultName) ? stateText : null;
            out.add(new McMenu.Entry(id, ready ? Icons.TV : Icons.TV_OFF, name, line1, line2,
                    signal, () -> opener.openInput(id)));
        }
        return out;
    }

    /** By id, but with the port numbers at the end in numeric order (".../HW2" before "HW10"). */
    private static int byPort(TvInputInfo a, TvInputInfo b) {
        String x = a.getId(), y = b.getId();
        int i = digitsFrom(x), j = digitsFrom(y);
        int c = x.substring(0, i).compareTo(y.substring(0, j));
        if (c == 0 && i < x.length() && j < y.length()) {
            c = Long.compare(Long.parseLong(x.substring(i)), Long.parseLong(y.substring(j)));
        }
        return c != 0 ? c : x.compareTo(y);
    }

    /** Where the run of digits at the end of s starts (s.length() if there is none). */
    private static int digitsFrom(String s) {
        int i = s.length();
        while (i > 0 && Character.isDigit(s.charAt(i - 1)) && s.length() - i < 18) i--;
        return i;
    }

    private static String label(CharSequence s, String otherwise) {
        return s != null && s.length() > 0 ? s.toString() : otherwise;
    }

    @Override
    public String emptyText() {
        return context.getString(R.string.inputs_none);
    }

    @Override
    public String openLabel() {
        return context.getString(R.string.inputs_open);
    }

    @Override
    public String extraLabel() {
        return context.getString(R.string.inputs_edit);
    }

    @Override
    public Intent extra() {
        return Pages.first(context, EDIT_PAGES);
    }

    @Override
    public void opened(McMenu m) {
        menu = m;
        TvInputManager tim = tim();
        if (tim != null) tim.registerCallback(changes, handler);
    }

    @Override
    public void closed() {
        menu = null;
        TvInputManager tim = tim();
        if (tim != null) tim.unregisterCallback(changes);
    }
}
