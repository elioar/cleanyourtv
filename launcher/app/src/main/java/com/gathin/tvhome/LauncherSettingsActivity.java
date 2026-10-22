package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Remote-friendly settings owned by the launcher, as a side panel over Home: OK changes the focused setting,
 * Back closes. Every choice is stored in the same preferences (and under the same keys) the full-screen version
 * used, so nothing set before is lost; new rows are marked NEW.
 */
public final class LauncherSettingsActivity extends Activity {
    private static final int PHOTO_PERMISSION_REQUEST = 7;

    private SharedPreferences choices;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;
    private LinearLayout rows;
    private final List<Row> all = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** What to do once the photo permission is answered: the picker, or building the slideshow. */
    private Runnable afterPermission;

    /** One setting: its name, its current value, and what OK does. */
    private static final class Row {
        final String title;
        final boolean isNew;
        final Supplier<String> value;
        final Runnable action;
        final BooleanSupplier enabled;
        final int whyDisabled;
        TextView valueView;
        View view;

        Row(String title, boolean isNew, Supplier<String> value, Runnable action, BooleanSupplier enabled, int whyDisabled) {
            this.title = title;
            this.isNew = isNew;
            this.value = value;
            this.action = action;
            this.enabled = enabled;
            this.whyDisabled = whyDisabled;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        rows = findViewById(R.id.sideRows);
        if (animations) {
            View panel = findViewById(R.id.sidePanel);
            panel.setTranslationX(360 * getResources().getDisplayMetrics().density);
            panel.animate().translationX(0f).setDuration(config.reducedMotion ? 120 : 320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        }
        build();
        refresh();
        if (!all.isEmpty()) all.get(0).view.requestFocus();
    }

    private void build() {
        group(R.string.set_group_layout);
        add(getString(R.string.columns_label), false, () -> String.valueOf(columns), () -> {
            columns = columns >= 7 ? 4 : columns + 1;
            choices.edit().putInt("columns", columns).apply();
            changed();
        });
        add(getString(R.string.rows_below_dock_label), false, () -> String.valueOf(rowsBelowDock), () -> {
            rowsBelowDock = (rowsBelowDock + 1) % 4;
            choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, rowsBelowDock).apply();
            changed();
        });
        add(getString(R.string.set_animations), false, () -> onOff(animations), () -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations).putBoolean("advanced_effects", advancedEffects).apply();
            changed();
        });
        add(getString(R.string.advanced_effects), false, () -> onOff(advancedEffects), () -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (d, w) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        }, () -> animations, 0);

        group(R.string.set_group_rows);
        toggle(R.string.set_stremio_row, HomeFeeds.ROW_STREMIO);
        add(getString(R.string.set_stremio_account), true, () -> getString(StremioKey.get(this) != null
                ? R.string.set_connected : R.string.set_not_connected),
                () -> Toast.makeText(this, R.string.set_stremio_help, Toast.LENGTH_LONG).show(),
                () -> HomeFeeds.on(choices, HomeFeeds.ROW_STREMIO), 0);
        toggle(R.string.set_movie_hero, HomeFeeds.ROW_HERO);
        toggle(R.string.set_tv_row, HomeFeeds.ROW_TV);
        toggle(R.string.set_news_row, HomeFeeds.ROW_NEWS);
        add(getString(R.string.set_news_country), true, () -> HomeFeeds.countryName(HomeFeeds.country(choices)), () -> {
            String cur = HomeFeeds.country(choices);
            String next = HomeFeeds.COUNTRIES[0][0];
            for (int i = 0; i < HomeFeeds.COUNTRIES.length; i++) {
                if (HomeFeeds.COUNTRIES[i][0].equals(cur)) next = HomeFeeds.COUNTRIES[(i + 1) % HomeFeeds.COUNTRIES.length][0];
            }
            choices.edit().putString(HomeFeeds.NEWS_COUNTRY, next).apply();
            if (HomeFeeds.SOURCE_YOUTUBE.equals(HomeFeeds.source(choices)) && !HomeFeeds.hasChannels(next)) {
                Toast.makeText(this, R.string.news_no_youtube, Toast.LENGTH_SHORT).show();
            }
            changed();
        }, () -> HomeFeeds.on(choices, HomeFeeds.ROW_NEWS), R.string.set_needs_news);
        add(getString(R.string.set_news_from), true, () -> getString(HomeFeeds.SOURCE_YOUTUBE.equals(HomeFeeds.source(choices))
                ? R.string.news_youtube : R.string.news_web), () -> {
            String next = HomeFeeds.SOURCE_YOUTUBE.equals(HomeFeeds.source(choices)) ? HomeFeeds.SOURCE_WEB : HomeFeeds.SOURCE_YOUTUBE;
            choices.edit().putString(HomeFeeds.NEWS_SOURCE, next).apply();
            if (next.equals(HomeFeeds.SOURCE_YOUTUBE) && !HomeFeeds.hasChannels(HomeFeeds.country(choices))) {
                Toast.makeText(this, R.string.news_no_youtube, Toast.LENGTH_SHORT).show();
            }
            changed();
        }, () -> HomeFeeds.on(choices, HomeFeeds.ROW_NEWS), R.string.set_needs_news);

        group(R.string.set_group_wallpaper);
        add(getString(R.string.set_wallpaper_mode), true, this::modeName, this::cycleMode);
        add(getString(R.string.set_photo), true, () -> {
            String name = choices.getString(HomeWallpaper.PHOTO_NAME_KEY, null);
            return name != null && HomeWallpaper.photoFile(this).isFile() ? shorten(name) : getString(R.string.set_choose);
        }, () -> withPhotoPermission(this::pickPhoto));
        add(getString(R.string.set_slideshow_from), true, this::slidesName, this::cycleSlidesSource,
                () -> isMode(HomeWallpaper.MODE_SLIDESHOW), R.string.set_needs_slideshow);
        add(getString(R.string.set_change_every), true, () -> intervalName(HomeWallpaper.intervalMinutes(choices)), () -> {
            int cur = HomeWallpaper.intervalMinutes(choices), next = HomeWallpaper.INTERVALS[0];
            for (int i = 0; i < HomeWallpaper.INTERVALS.length; i++) {
                if (HomeWallpaper.INTERVALS[i] == cur) next = HomeWallpaper.INTERVALS[(i + 1) % HomeWallpaper.INTERVALS.length];
            }
            choices.edit().putInt(HomeWallpaper.INTERVAL_KEY, next).apply();
            changed();
        }, () -> isMode(HomeWallpaper.MODE_SLIDESHOW), R.string.set_needs_slideshow);
        add(getString(R.string.set_darken), true, () -> HomeWallpaper.dim(choices) + "%", () -> {
            int cur = HomeWallpaper.dim(choices), next = HomeWallpaper.DIMS[0];
            for (int i = 0; i < HomeWallpaper.DIMS.length; i++) {
                if (HomeWallpaper.DIMS[i] == cur) next = HomeWallpaper.DIMS[(i + 1) % HomeWallpaper.DIMS.length];
            }
            choices.edit().putInt(HomeWallpaper.DIM_KEY, next).apply();
            changed();
        }, () -> !isMode(HomeWallpaper.MODE_GLOW), R.string.set_needs_wallpaper);
        add(getString(R.string.set_theme_wallpaper), false, this::themeWallpaperName, this::showThemeWallpaperPicker);

        group(R.string.set_group_home);
        toggle(R.string.set_weather, HomeFeeds.WEATHER_ON);
        add(getString(R.string.set_weather_city), true, () -> {
            String[] city = HomeFeeds.city(choices);
            return city != null ? city[1] : getString(R.string.set_weather_choose);
        }, this::searchWeatherPlace, () -> HomeFeeds.on(choices, HomeFeeds.WEATHER_ON), R.string.set_needs_weather);
        add(getString(R.string.set_follow_theme), false, () -> getString(R.string.set_reset), () -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            Toast.makeText(this, R.string.set_reset_done, Toast.LENGTH_SHORT).show();
            changed();
        });
        add(getString(R.string.set_default_home), false, () -> getString(R.string.set_choose), () -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        add(getString(R.string.set_tv_settings), false, () -> getString(R.string.cc_open),
                () -> open(new Intent(Settings.ACTION_SETTINGS)));
    }

    /** An On/Off row for a home feature stored under key (on unless switched off). */
    /** Weather city: type a name, pick one of the places Open-Meteo finds. */
    private void searchWeatherPlace() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.set_weather_hint);
        String[] cur = HomeFeeds.city(choices);
        if (cur != null) input.setText(cur[1]);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_weather_city)
                .setView(input)
                .setPositiveButton(R.string.set_weather_search, (d, w) -> {
                    String q = input.getText().toString().trim();
                    if (q.length() < 2) return;
                    Toast.makeText(this, R.string.set_weather_searching, Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        List<String[]> found;
                        try {
                            found = HomeFeeds.searchPlaces(q);
                        } catch (Exception e) {
                            found = null;
                        }
                        final List<String[]> places = found;
                        runOnUiThread(() -> showPlaces(places));
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPlaces(List<String[]> places) {
        if (isFinishing()) return;
        if (places == null || places.isEmpty()) {
            Toast.makeText(this, places == null ? R.string.set_weather_failed : R.string.set_weather_none, Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[places.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = places.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_weather_city)
                .setItems(labels, (d, which) -> {
                    choices.edit().putString(HomeFeeds.WEATHER_PLACE, places.get(which)[1]).apply();
                    changed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggle(int title, String key) {
        add(getString(title), true, () -> onOff(HomeFeeds.on(choices, key)), () -> {
            choices.edit().putBoolean(key, !HomeFeeds.on(choices, key)).apply();
            changed();
        });
    }

    private String slidesName() {
        String src = choices.getString(HomeWallpaper.SOURCE_KEY, HomeFeeds.SLIDES_BOTH);
        if (HomeFeeds.SLIDES_BING.equals(src)) return getString(R.string.set_slides_bing);
        if (HomeFeeds.SLIDES_NASA.equals(src)) return getString(R.string.set_slides_nasa);
        if (HomeFeeds.SLIDES_BOTH.equals(src)) return getString(R.string.set_slides_both);
        return getString(R.string.set_slides_photos);
    }

    /** Your photos, Bing, NASA, Bing + NASA: the daily pictures are fetched (in the background) before switching. */
    private void cycleSlidesSource() {
        String src = choices.getString(HomeWallpaper.SOURCE_KEY, HomeFeeds.SLIDES_BOTH);
        String next = HomeWallpaper.SOURCE_PHOTOS.equals(src) ? HomeFeeds.SLIDES_BING
                : HomeFeeds.SLIDES_BING.equals(src) ? HomeFeeds.SLIDES_NASA
                : HomeFeeds.SLIDES_NASA.equals(src) ? HomeFeeds.SLIDES_BOTH : HomeWallpaper.SOURCE_PHOTOS;
        if (HomeWallpaper.SOURCE_PHOTOS.equals(next)) {
            withPhotoPermission(this::buildSlides);
            return;
        }
        buildWebSlides(next);
    }

    private void buildWebSlides(String source) {
        Toast.makeText(this, R.string.set_slides_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = HomeFeeds.buildWebSlides(this, source);
            main.post(() -> {
                if (isFinishing()) return;
                if (!ok) {
                    Toast.makeText(this, R.string.set_slides_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                choices.edit().putString(HomeWallpaper.SOURCE_KEY, source)
                        .putString(HomeWallpaper.MODE_KEY, HomeWallpaper.MODE_SLIDESHOW).apply();
                changed();
            });
        }, "daily-pictures").start();
    }

    // ---- rows ------------------------------------------------------------------------------------

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void group(int title) {
        TextView g = new TextView(this);
        g.setText(title);
        g.setTextColor(0x73FFFFFF);
        g.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        g.setLetterSpacing(0.22f);
        g.setTypeface(getResources().getFont(R.font.outfit_medium));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) dp(10), rows.getChildCount() == 0 ? 0 : (int) dp(14), 0, (int) dp(4));
        rows.addView(g, lp);
    }

    private void add(String title, boolean isNew, Supplier<String> value, Runnable action) {
        add(title, isNew, value, action, () -> true, 0);
    }

    private void add(String title, boolean isNew, Supplier<String> value, Runnable action,
                     BooleanSupplier enabled, int whyDisabled) {
        Row row = new Row(title, isNew, value, action, enabled, whyDisabled);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setBackgroundResource(R.drawable.side_row_bg);
        v.setFocusable(true);
        v.setClickable(true);
        v.setPadding((int) dp(10), 0, (int) dp(8), 0);
        Typeface regular = getResources().getFont(R.font.outfit_regular);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTypeface(regular);
        t.setSingleLine(true);
        t.setDuplicateParentStateEnabled(true);
        t.setTextColor(getColorStateList(R.drawable.side_text));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        v.addView(t, tlp);
        if (isNew) {
            TextView n = new TextView(this);
            n.setText(R.string.set_new);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
            n.setLetterSpacing(0.12f);
            n.setTypeface(getResources().getFont(R.font.outfit_medium));
            n.setDuplicateParentStateEnabled(true);
            n.setTextColor(getColorStateList(R.drawable.side_new));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.setMarginEnd((int) dp(8));
            v.addView(n, nlp);
        }
        TextView val = new TextView(this);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        val.setTypeface(regular);
        val.setSingleLine(true);
        val.setDuplicateParentStateEnabled(true);
        val.setTextColor(getColorStateList(R.drawable.side_text_dim));
        val.setBackgroundResource(R.drawable.side_value_bg);
        val.setPadding((int) dp(9), (int) dp(2), (int) dp(9), (int) dp(3));
        val.setMaxWidth((int) dp(150));
        v.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        v.setOnClickListener(x -> {
            if (!row.enabled.getAsBoolean()) {
                if (row.whyDisabled != 0) Toast.makeText(this, row.whyDisabled, Toast.LENGTH_SHORT).show();
                return;
            }
            row.action.run();
        });
        // Right also changes the setting, Left closes, like the design preview.
        v.setOnKeyListener((x, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                x.performClick();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                finish();
                return true;
            }
            return false;
        });
        row.valueView = val;
        row.view = v;
        all.add(row);
        rows.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(38)));
    }

    private void refresh() {
        for (Row r : all) {
            r.valueView.setText(r.value.get());
            boolean on = r.enabled.getAsBoolean();
            r.view.setAlpha(on ? 1f : 0.38f);
        }
    }

    /** A choice was saved: Home rebuilds when the panel closes. */
    private void changed() {
        setResult(RESULT_OK);
        refresh();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    // ---- values ----------------------------------------------------------------------------------

    private String onOff(boolean on) {
        return getString(on ? R.string.on : R.string.off);
    }

    private static String shorten(String name) {
        return name.length() > 18 ? name.substring(0, 16) + "…" : name;
    }

    private String intervalName(int minutes) {
        return minutes >= 60 ? getString(R.string.set_hour) : getString(R.string.set_minutes, minutes);
    }

    private boolean isMode(String mode) {
        return mode.equals(choices.getString(HomeWallpaper.MODE_KEY, HomeWallpaper.MODE_GLOW));
    }

    private String modeName() {
        if (isMode(HomeWallpaper.MODE_PHOTO)) return getString(R.string.set_wp_photo);
        if (isMode(HomeWallpaper.MODE_SLIDESHOW)) return getString(R.string.set_wp_slideshow);
        return getString(R.string.set_wp_glow);
    }

    /** Glow, Photo (once one was chosen), Slideshow; the slideshow's pictures are fetched or listed on the way. */
    private void cycleMode() {
        if (isMode(HomeWallpaper.MODE_GLOW) && !HomeWallpaper.photo(this).isEmpty()) {
            setMode(HomeWallpaper.MODE_PHOTO);
        } else if (!isMode(HomeWallpaper.MODE_SLIDESHOW)) {
            // From the glow (no photo chosen yet: the Photo row picks one) or from Photo, on to the slideshow.
            String src = choices.getString(HomeWallpaper.SOURCE_KEY, HomeFeeds.SLIDES_BOTH);
            if (HomeWallpaper.SOURCE_PHOTOS.equals(src)) withPhotoPermission(this::buildSlides);
            else buildWebSlides(src);
        } else {
            setMode(HomeWallpaper.MODE_GLOW);
        }
    }

    private void setMode(String mode) {
        choices.edit().putString(HomeWallpaper.MODE_KEY, mode).apply();
        changed();
    }

    private String themeWallpaperName() {
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        return "none".equals(current) ? getString(R.string.wallpaper_none)
                : current != null ? shorten(current) : getString(R.string.wallpaper_theme_default);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY);
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
    }

    // ---- photos ----------------------------------------------------------------------------------

    private String photoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES" : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void withPhotoPermission(Runnable then) {
        if (checkSelfPermission(photoPermission()) == PackageManager.PERMISSION_GRANTED) {
            then.run();
            return;
        }
        afterPermission = then;
        requestPermissions(new String[]{photoPermission()}, PHOTO_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PHOTO_PERMISSION_REQUEST) return;
        Runnable then = afterPermission;
        afterPermission = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && then != null) then.run();
        else Toast.makeText(this, R.string.set_photo_storage, Toast.LENGTH_LONG).show();
    }

    /** Lists the TV's and USB drive's pictures (newest first) and copies the chosen one as the wallpaper. */
    private void pickPhoto() {
        new Thread(() -> {
            List<HomeWallpaper.Picture> pics = HomeWallpaper.listPictures(this, 200);
            main.post(() -> {
                if (isFinishing()) return;
                if (pics.isEmpty()) {
                    Toast.makeText(this, R.string.set_no_photos, Toast.LENGTH_LONG).show();
                    return;
                }
                String[] names = new String[pics.size()];
                for (int i = 0; i < names.length; i++) names[i] = pics.get(i).name;
                new AlertDialog.Builder(this)
                        .setTitle(R.string.set_pick_photo)
                        .setItems(names, (d, which) -> importPhoto(pics.get(which)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "photos").start();
    }

    private void importPhoto(HomeWallpaper.Picture pic) {
        new Thread(() -> {
            boolean ok = HomeWallpaper.importPhoto(this, pic.path);
            main.post(() -> {
                if (isFinishing()) return;
                if (!ok) {
                    Toast.makeText(this, R.string.set_photo_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                choices.edit().putString(HomeWallpaper.PHOTO_NAME_KEY, pic.name)
                        .putString(HomeWallpaper.MODE_KEY, HomeWallpaper.MODE_PHOTO).apply();
                Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                changed();
            });
        }, "photo-import").start();
    }

    private void buildSlides() {
        new Thread(() -> {
            boolean ok = HomeWallpaper.buildPhotoSlides(this);
            main.post(() -> {
                if (isFinishing()) return;
                if (!ok) {
                    Toast.makeText(this, R.string.set_no_photos, Toast.LENGTH_LONG).show();
                    return;
                }
                choices.edit().putString(HomeWallpaper.SOURCE_KEY, HomeWallpaper.SOURCE_PHOTOS)
                        .putString(HomeWallpaper.MODE_KEY, HomeWallpaper.MODE_SLIDESHOW).apply();
                changed();
            });
        }, "slides").start();
    }

    // ---- theme wallpaper (the existing picker) ---------------------------------------------------

    private void showThemeWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size() : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                            which == images.size() ? "none" : images.get(which).getName()).apply();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    changed();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
