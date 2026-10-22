package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal home's All apps page: every installed app A–Z on the same filled tiles as the dock, full screen over Home.
 * The list is the app library's (TV entry first, else the phone one); Hide from Home uses the library's choices
 * (LibraryApps). Art loads off the main thread while the page is open and is dropped when it closes.
 */
final class AllAppsPage {
    /** What the page asks of Home. */
    interface Host {
        boolean inDock(String pkg);

        boolean onHome(String pkg);

        void addToDock(String pkg);

        void removeFromDock(String pkg);

        void setOnHome(String pkg, boolean on);

        void closed();
    }

    private static final class App {
        final ResolveInfo info;
        final String pkg, label;
        final boolean tv;
        volatile boolean loaded;
        volatile Drawable banner, icon;
        volatile int fill;

        App(ResolveInfo info, String label, boolean tv) {
            this.info = info;
            this.pkg = info.activityInfo.packageName;
            this.label = label;
            this.tv = tv;
        }
    }

    private final Activity activity;
    private final Host host;
    private final int columns;
    private final boolean animations;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout page;
    private final RecyclerView list;
    private final TextView count;
    private final List<App> apps = new ArrayList<>();
    private PopupWindow actions;
    private Thread loader;
    private int tileW, tileH;

    AllAppsPage(Activity activity, Host host, int columns, boolean animations) {
        this.activity = activity;
        this.host = host;
        this.columns = Math.max(3, Math.min(8, columns));
        this.animations = animations;
        density = activity.getResources().getDisplayMetrics().density;
        page = new FrameLayout(activity);
        // Opaque, in the deep blue of Home's glow: nothing of Home shows or draws through it.
        page.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF141B2E, 0xFF06080F}));
        page.setClickable(true);   // nothing under the page takes a touch or a click
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(56), dp(34), dp(56), 0);
        page.addView(column, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(dp(12), 0, dp(12), 0);
        TextView title = text(activity.getString(R.string.all_apps), 34, Color.WHITE, R.font.outfit_light);
        header.addView(title, new LinearLayout.LayoutParams(-2, -2));
        count = text("", 14, 0x8CFFFFFF, R.font.outfit_medium);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(0, -2, 1);
        countLp.setMarginStart(dp(16));
        countLp.bottomMargin = dp(5);
        header.addView(count, countLp);
        TextView hint = text(activity.getString(R.string.all_apps_hint), 13, 0x8CFFFFFF, R.font.outfit_medium);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-2, -2);
        hintLp.bottomMargin = dp(5);
        header.addView(hint, hintLp);
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));

        list = new RecyclerView(activity);
        list.setLayoutManager(new GridLayoutManager(activity, this.columns));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setPadding(0, dp(18), 0, dp(40));
        list.setAdapter(new Tiles());
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, 0, 1);
        listLp.topMargin = dp(6);
        column.addView(list, listLp);
    }

    View view() {
        return page;
    }

    boolean showing() {
        return page.getParent() != null;
    }

    /** Fills the page and shows it in parent, focus on the first app. */
    void show(ViewGroup parent) {
        load();
        int inner = parent.getWidth() - dp(112);
        tileW = (int) (inner / (float) columns - AppAdapter.MINIMAL_GAP_DP * density);
        tileH = Math.round(tileW * 0.5625f);
        list.getAdapter().notifyDataSetChanged();
        count.setText(activity.getResources().getQuantityString(R.plurals.library_app_count, apps.size(), apps.size()));
        parent.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (animations) {
            page.setAlpha(0f);
            page.animate().alpha(1f).setDuration(160).start();
        }
        list.scrollToPosition(0);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(0);
            if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
        });
        loadArt();
    }

    void close() {
        if (!showing()) return;
        dismissActions();
        if (loader != null) loader.interrupt();
        loader = null;
        ((ViewGroup) page.getParent()).removeView(page);
        // Let go of the art: it is loaded again the next time the page opens.
        apps.clear();
        list.getAdapter().notifyDataSetChanged();
        host.closed();
    }

    /** The app library's list: one entry per package, the TV entry when there is one, A–Z. */
    private void load() {
        apps.clear();
        PackageManager pm = activity.getPackageManager();
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        Set<String> tv = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                if (!pkg.equals(activity.getPackageName()) && !unique.containsKey(pkg)) unique.put(pkg, info);
            }
        }
        for (ResolveInfo info : unique.values()) {
            String pkg = info.activityInfo.packageName;
            String label;
            try {
                label = Cards.cleanLabel(String.valueOf(info.loadLabel(pm)), pkg);
            } catch (RuntimeException e) {
                label = pkg;
            }
            apps.add(new App(info, label, tv.contains(pkg)));
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> {
            int c = collator.compare(a.label, b.label);
            return c != 0 ? c : a.pkg.compareTo(b.pkg);
        });
    }

    /** Banners and icons in list order on a background thread; each tile fills in as its art arrives. */
    private void loadArt() {
        final List<App> todo = new ArrayList<>(apps);
        loader = new Thread(() -> {
            PackageManager pm = activity.getPackageManager();
            for (int i = 0; i < todo.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return;
                App a = todo.get(i);
                try {
                    Drawable banner = AppAdapter.loadBanner(activity, pm, a.info);
                    if (banner == null) {
                        a.icon = AppAdapter.loadIcon(activity, pm, a.info);
                        a.fill = AppAdapter.fillColor(a.icon);
                    }
                    a.banner = banner;
                } catch (RuntimeException ignored) {
                }
                a.loaded = true;
                final int position = i;
                main.post(() -> {
                    if (showing() && position < apps.size() && apps.get(position) == a) {
                        list.getAdapter().notifyItemChanged(position);
                    }
                });
            }
        }, "all-apps-art");
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    private void open(App a) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(a.pkg, a.info.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // TV apps launch with the leanback intent, so one already running resumes instead of restarting.
        if (a.tv) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        try {
            activity.startActivity(intent);
            LastOpened.record(activity, a.pkg);
            close();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, a.label), Toast.LENGTH_SHORT).show();
        }
    }

    /** Hold OK: Add to dock (or Remove from dock), Hide from Home (or Show on Home), App info. */
    private void showActions(App a, View card, int position) {
        dismissActions();
        View panel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, page, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow menu = new PopupWindow(panel, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actions = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setOnDismissListener(() -> {
            if (actions == menu) actions = null;
            if (showing()) card.requestFocus();
        });
        boolean docked = host.inDock(a.pkg);
        boolean onHome = host.onHome(a.pkg);
        addAction(rows, docked ? R.string.all_apps_undock : R.string.all_apps_dock, () -> {
            if (docked) host.removeFromDock(a.pkg);
            else host.addToDock(a.pkg);
            list.getAdapter().notifyItemChanged(position);
        });
        addAction(rows, onHome ? R.string.all_apps_hide : R.string.all_apps_unhide, () -> {
            host.setOnHome(a.pkg, !onHome);
            list.getAdapter().notifyItemChanged(position);
        });
        addAction(rows, R.string.library_app_info, () -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                close();
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, a.label), Toast.LENGTH_SHORT).show();
            }
        });
        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        card.getLocationOnScreen(at);
        int screenW = page.getWidth(), screenH = page.getHeight();
        int x = Math.min(at[0] + dp(12), screenW - dp(240) - dp(24));
        int y = at[1] + card.getHeight() - dp(8);
        if (y + panel.getMeasuredHeight() > screenH - dp(16)) y = Math.max(dp(16), at[1] - panel.getMeasuredHeight() + dp(8));
        menu.showAtLocation(page, Gravity.TOP | Gravity.START, x, y);
        rows.getChildAt(0).requestFocus();
    }

    private void addAction(LinearLayout rows, int title, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        // The OK release that ends the hold must not pick the first row: a row acts only on a press it saw begin.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) pressed[0] = true;
                    return true;
                }
                if (pressed[0]) {
                    pressed[0] = false;
                    dismissActions();
                    action.run();
                }
                return true;
            }
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || code == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
        row.setOnClickListener(v -> {
            dismissActions();
            action.run();
        });
        rows.addView(row);
    }

    private void dismissActions() {
        PopupWindow menu = actions;
        actions = null;
        if (menu != null) menu.dismiss();
    }

    private TextView text(String value, int sp, int color, int font) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(activity.getResources().getFont(font));
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private final class Tiles extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = activity.getLayoutInflater().inflate(R.layout.item_app, parent, false);
            int pad = dp(AppAdapter.MINIMAL_GAP_DP / 2f);
            v.setPadding(0, pad, 0, pad);
            View card = v.findViewById(R.id.card);
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = tileW;
            lp.height = tileH;
            card.setLayoutParams(lp);
            ((FrameLayout) card).setForeground(activity.getDrawable(R.drawable.minimal_tile_ring));
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 16 * density);
                }
            });
            card.setClipToOutline(true);
            ImageView icon = v.findViewById(R.id.icon);
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            iconLp.width = iconLp.height = Math.round(tileH * 0.5f);
            iconLp.bottomMargin = 0;
            icon.setLayoutParams(iconLp);
            TextView name = v.findViewById(R.id.name);
            FrameLayout.LayoutParams nameLp = (FrameLayout.LayoutParams) name.getLayoutParams();
            nameLp.width = tileW;
            nameLp.topMargin = tileH + dp(8);
            name.setLayoutParams(nameLp);
            name.setAlpha(0.6f);
            name.setTranslationY(0f);
            v.setPadding(0, pad, 0, pad + dp(26));
            final float zoom = animations ? 1.06f : 1f;
            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? zoom : 1f;
                if (animations) view.animate().scaleX(s).scaleY(s).setDuration(focused ? 200 : 150).withLayer().start();
                name.animate().cancel();
                name.setAlpha(focused ? 1f : 0.6f);
                if (focused) {
                    // Keep the focused row clear of the header and the screen's bottom edge.
                    list.post(() -> {
                        int[] at = new int[2], top = new int[2];
                        view.getLocationInWindow(at);
                        list.getLocationInWindow(top);
                        int y = at[1] - top[1];
                        int want = dp(18);
                        int bottom = list.getHeight() - dp(70) - view.getHeight();
                        if (y < want) list.smoothScrollBy(0, y - want);
                        else if (y > bottom) list.smoothScrollBy(0, y - bottom);
                    });
                }
            });
            return new RecyclerView.ViewHolder(v) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            App a = apps.get(position);
            View v = holder.itemView;
            View card = v.findViewById(R.id.card);
            ImageView banner = v.findViewById(R.id.banner);
            ImageView icon = v.findViewById(R.id.icon);
            v.findViewById(R.id.label).setVisibility(View.GONE);
            if (a.loaded) {
                AppAdapter.bindFilled(card, banner, icon, a.banner, a.icon, a.fill);
            } else {
                card.setBackground(new ColorDrawable(0xFF1D2029));
                banner.setImageDrawable(null);
                icon.setImageDrawable(null);
            }
            TextView name = v.findViewById(R.id.name);
            String label = a.label;
            if (host.inDock(a.pkg)) label = label + "  ·  " + activity.getString(R.string.all_apps_in_dock);
            name.setText(label);
            card.setContentDescription(a.label);
            card.setOnClickListener(c -> open(a));
            card.setOnLongClickListener(c -> {
                hold(holder, a, c);
                return true;
            });
            // OK held down also counts once the key repeats (some remotes, and adb's long press, send the repeat
            // before the long-click timer runs out); the release after it must not open the app.
            final boolean[] held = {false};
            card.setOnKeyListener((c, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        held[0] = false;
                        return false;
                    }
                    if (!held[0]) {
                        held[0] = true;
                        c.cancelLongPress();
                        c.setPressed(false);
                        hold(holder, a, c);
                    }
                    return true;
                }
                if (held[0]) {
                    held[0] = false;
                    return true;
                }
                return false;
            });
        }

        private void hold(RecyclerView.ViewHolder holder, App a, View card) {
            int p = holder.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION && actions == null) showActions(a, card, p);
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }
}
