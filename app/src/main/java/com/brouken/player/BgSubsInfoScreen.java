package com.brouken.player;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

/**
 * The ℹ screen: poster, IMDb rating and awards, plot, actors (OK → biography) and similar titles
 * (OK → their own Info). Each opened screen is a dialog on top of the previous one, so Back goes back.
 */
final class BgSubsInfoScreen {

    private static final int BACKGROUND = 0xF2101010;
    private static final int PRIMARY = 0xFFFFFFFF;
    private static final int SECONDARY = 0xFFB8B8B8;
    private static final int LABEL = 0xFF8AB4F8;
    private static final int GOLD = 0xFFFFCA28;
    private static final int PLACEHOLDER = 0xFF2A2A2A;
    private static final int FOCUS = 0xFF42A5F5;

    interface Opener {
        void openTitle(String kind, int tmdbId);

        void openPerson(int id);
    }

    private final PlayerActivity activity;
    private final Opener opener;
    private final OkHttpClient http;
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final List<Dialog> open = new ArrayList<>();

    BgSubsInfoScreen(PlayerActivity activity, Opener opener, OkHttpClient http) {
        this.activity = activity;
        this.opener = opener;
        this.http = http;
    }

    /** Closes every Info screen (a new video started). */
    void closeAll() {
        for (Dialog d : new ArrayList<>(open)) {
            d.dismiss();
        }
        open.clear();
    }

    void show(BgSubsInfo info) {
        LinearLayout content = column();
        content.setPadding(dp(48), dp(28), dp(48), dp(28));

        // header: poster | titles, rating, awards
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setFocusable(true);  // first focus lands here, with the top of the screen in view
        ImageView poster = image(150, 225);
        load(poster, info.poster, dp(150));
        header.addView(poster);
        LinearLayout titles = column();
        titles.setPadding(dp(24), 0, 0, 0);
        TextView heading = text(info.heading(), 28, PRIMARY);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(heading);
        addLine(titles, info.originalTitle(), 17, SECONDARY, 0);
        addLine(titles, info.metaLine(), 16, SECONDARY, 4);
        TextView rating = addLine(titles, info.ratingLine(), 22, GOLD, 12);
        if (rating != null) rating.setTypeface(Typeface.DEFAULT_BOLD);
        addLine(titles, info.awardsLine(), 16, PRIMARY, 6);
        addLine(titles, info.directorLine(), 16, SECONDARY, 6);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(header);

        if (info.episode != null) {
            content.addView(label(activity.getString(R.string.bgsubs_info_episode)));
            TextView ep = text(info.episodeLine(), 18, PRIMARY);
            ep.setTypeface(Typeface.DEFAULT_BOLD);
            content.addView(ep);
            addLine(content, info.episodeDetail(), 16, GOLD, 2);
        }

        if (info.plot != null) {
            content.addView(label(activity.getString(info.plotInEnglish()
                    ? R.string.bgsubs_info_plot_en : R.string.bgsubs_info_plot)));
            TextView plot = text(info.plot, 17, PRIMARY);
            plot.setLineSpacing(0, 1.15f);
            content.addView(plot);
        }

        addPeople(content, R.string.bgsubs_info_cast, info.cast);
        addPeople(content, R.string.bgsubs_info_guests, info.guestCast);

        if (!info.similar.isEmpty()) {
            content.addView(label(activity.getString(R.string.bgsubs_info_similar)));
            LinearLayout row = row(content);
            for (BgSubsInfo.Similar s : info.similar) {
                LinearLayout card = card(130);
                ImageView p = image(122, 183);
                load(p, s.poster, dp(122));
                card.addView(p);
                card.addView(caption(s.caption()));
                card.setOnClickListener(v -> opener.openTitle(s.kind, s.id));
                row.addView(card);
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(content);
        present(scroll);
        header.requestFocus();
    }

    void showPerson(BgSubsInfo.Bio person) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(48), dp(28), dp(48), dp(28));

        ImageView photo = image(200, 300);
        load(photo, person.photo, dp(200));
        root.addView(photo);

        LinearLayout right = column();
        right.setPadding(dp(28), 0, 0, 0);
        TextView name = text(person.name == null ? "" : person.name, 28, PRIMARY);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        right.addView(name);
        Calendar today = Calendar.getInstance();
        addLine(right, person.lifeLine(today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH)), 16, SECONDARY, 6);
        addLine(right, person.placeLine(), 16, SECONDARY, 2);
        right.addView(label(activity.getString(person.bioInEnglish()
                ? R.string.bgsubs_info_bio_en : R.string.bgsubs_info_bio)));

        // focusable so ▲ ▼ scroll a long biography
        ScrollView bioScroll = new ScrollView(activity);
        bioScroll.setFocusable(true);
        TextView bio = text(person.bio != null ? person.bio : activity.getString(R.string.bgsubs_info_no_bio), 17,
                PRIMARY);
        bio.setLineSpacing(0, 1.15f);
        bioScroll.addView(bio);
        right.addView(bioScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        present(root);
        bioScroll.requestFocus();
    }

    private void addPeople(LinearLayout content, int labelRes, List<BgSubsInfo.Person> people) {
        if (people.isEmpty()) {
            return;
        }
        content.addView(label(activity.getString(labelRes)));
        LinearLayout row = row(content);
        for (BgSubsInfo.Person p : people) {
            LinearLayout card = card(112);
            ImageView photo = image(104, 156);
            load(photo, p.photo, dp(104));
            card.addView(photo);
            card.addView(caption(p.name));
            if (p.id != null) {
                final int id = p.id;
                card.setOnClickListener(v -> opener.openPerson(id));
            }
            row.addView(card);
        }
    }

    private Dialog present(View view) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.setContentView(view);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        open.add(dialog);
        dialog.setOnDismissListener(d -> open.remove(dialog));
        dialog.show();
        return dialog;
    }

    // --- views ------------------------------------------------------------------------------------

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** A horizontal, D-pad scrollable row of cards under a section label. */
    private LinearLayout row(LinearLayout content) {
        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        scroll.addView(row);
        content.addView(scroll);
        return row;
    }

    private LinearLayout card(int widthDp) {
        LinearLayout card = column();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setFocusable(true);
        card.setBackground(focusBackground());
        card.setOnFocusChangeListener((v, focused) ->
                v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(120).start());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(10));
        card.setLayoutParams(params);
        return card;
    }

    private Drawable focusBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(8));
        focused.setColor(0x332196F3);
        focused.setStroke(dp(3), FOCUS);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, new ColorDrawable(0));
        return states;
    }

    private ImageView image(int widthDp, int heightDp) {
        ImageView v = new ImageView(activity);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackgroundColor(PLACEHOLDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)));
        return v;
    }

    private TextView caption(String value) {
        TextView t = text(value == null ? "" : value, 13, PRIMARY);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private TextView label(String value) {
        TextView t = text(value, 15, LABEL);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView addLine(LinearLayout parent, String value, int sp, int color, int topDp) {
        if (value == null) {
            return null;
        }
        TextView t = text(value, sp, color);
        t.setPadding(0, dp(topDp), 0, 0);
        parent.addView(t);
        return t;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    // --- images -----------------------------------------------------------------------------------

    private void load(ImageView view, String url, int targetWidthPx) {
        view.setTag(url);
        if (url == null) {
            return;
        }
        Bitmap cached = bitmaps.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        images.execute(() -> {
            Bitmap bitmap = decode(BgSubsClient.fetchImage(url, http), targetWidthPx);
            if (bitmap == null) {
                return;
            }
            bitmaps.put(url, bitmap);
            activity.runOnUiThread(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap decode(byte[] bytes, int targetWidthPx) {
        if (bytes == null) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidthPx) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }
}
