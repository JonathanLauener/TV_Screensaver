package tv.mcclock.dream;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/** Finds the TV's settings pages, so the menus only offer the ones it has. */
final class Pages {

    /**
     * The intent if it opens exactly one page we may start: not missing, not Android's "open
     * with" chooser (two apps answer it), exported, and needing no permission we lack.
     * Otherwise null.
     */
    static Intent resolve(Context context, Intent intent) {
        ResolveInfo ri = context.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        ActivityInfo ai = ri == null ? null : ri.activityInfo;
        if (ai == null || !ai.exported || "android".equals(ai.packageName)) return null;
        if (ai.permission != null && context.checkSelfPermission(ai.permission)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        return intent;
    }

    static Intent action(Context context, String action) {
        return resolve(context, new Intent(action));
    }

    /** The first of these pages ("package/class") the TV has, or null. */
    static Intent first(Context context, String... components) {
        for (String c : components) {
            ComponentName cn = ComponentName.unflattenFromString(c);
            if (cn == null) continue;
            Intent page = resolve(context, new Intent(Intent.ACTION_MAIN).setComponent(cn));
            if (page != null) return page;
        }
        return null;
    }

    private Pages() {
    }
}
