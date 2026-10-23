package gg.projects.mobile.social.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ChatCards;
import gg.projects.mobile.social.ChatLinkUi;
import gg.projects.mobile.social.ChatLinks;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ProjectsUi;

/**
 * MOB1 (CHAT2 §9; the owner: every https link the rules let through, a one-line warning before an
 * unknown site): links in a bubble and their cards. A link is bone and underlined (gold is OYNA's); a
 * trusted site opens at once, any other after one line that names its domain ("Bu bağlantı ornek.com
 * sitesine gidiyor.", "Vazgeç" / "Aç"); Android hands the address to the app that opens it (YouTube's,
 * a browser). Holding the bubble still opens its menu (a tap on a link is the only thing a link takes).
 * The card sits in the bubble under the text: the site's own icon and name, the title and the
 * description; a wide picture (a video, a stream, a projects.gg page) over the text, a squarer one
 * beside it. Its place is held from the start at its likely height; the content fades in (180 ms); a
 * card that does not come turns into the plain one (icon, site, address) at the same size. The phone
 * never fetches anything from a linked site: the hub makes the card.
 */
public final class ChatLinkViews {
    private ChatLinkViews() {}

    private static final LruCache<String, Bitmap> PICTURES = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService DECODE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A tap on a link. */
    public interface Opener {
        void open(ChatLinks.MessageLink link);
    }

    /** The text with its pressable links (only those the rules let through now). */
    public static CharSequence withLinks(final Context context, String text, List<ChatLinks.MessageLink> links, final Opener opener) {
        boolean any = false;
        for (ChatLinks.MessageLink l : links) any |= l.ok;
        if (!any) return text;
        SpannableString out = new SpannableString(text);
        final int bone = ContextCompat.getColor(context, R.color.projects_bone_100);
        for (final ChatLinks.MessageLink link : links) {
            if (!link.ok || link.link.end > text.length()) continue;
            out.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    opener.open(link);
                }

                @Override
                public void updateDrawState(TextPaint paint) {
                    paint.setColor(bone);
                    paint.setUnderlineText(true);
                }
            }, link.link.start, link.link.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /**
     * A tap on a link span opens it; everything else (a tap elsewhere, holding) stays the view's. Unlike
     * LinkMovementMethod this never takes a long press away from the bubble's menu.
     */
    public static void attachTouch(final TextView view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            ClickableSpan down;
            long downAt;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                CharSequence text = view.getText();
                if (!(text instanceof Spanned)) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    down = spanAt(view, (Spanned) text, event);
                    downAt = event.getEventTime();
                    return false;
                }
                if (action == MotionEvent.ACTION_UP && down != null) {
                    ClickableSpan up = spanAt(view, (Spanned) text, event);
                    boolean tap = up == down && event.getEventTime() - downAt < android.view.ViewConfiguration.getLongPressTimeout();
                    ClickableSpan span = down;
                    down = null;
                    if (tap) {
                        // The up is taken here, so the view's own touch handling never unpresses it.
                        view.cancelLongPress();
                        view.setPressed(false);
                        Haptics.press(view);
                        span.onClick(view);
                        return true;
                    }
                }
                if (action == MotionEvent.ACTION_CANCEL) down = null;
                return false;
            }
        });
    }

    @Nullable
    private static ClickableSpan spanAt(TextView view, Spanned text, MotionEvent event) {
        Layout layout = view.getLayout();
        if (layout == null) return null;
        int x = (int) event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
        int y = (int) event.getY() - view.getTotalPaddingTop() + view.getScrollY();
        int line = layout.getLineForVertical(y);
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null;
        int offset = layout.getOffsetForHorizontal(line, x);
        ClickableSpan[] spans = text.getSpans(offset, offset, ClickableSpan.class);
        return spans.length == 0 ? null : spans[0];
    }

    /**
     * Opens a link: a trusted site at once, any other after the one-line warning that names its domain
     * (the focus on "Vazgeç": the careful choice is the easy one).
     */
    public static void open(final Activity activity, final String url, String host, boolean trusted) {
        if (trusted) {
            ProjectsUi.openUrl(activity, url);
            return;
        }
        String domain = ChatLinkUi.domainOf(host);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.projects_links_warning, domain))
                .setNegativeButton(R.string.projects_links_cancel, null)
                .setPositiveButton(R.string.projects_links_open, (d, w) -> ProjectsUi.openUrl(activity, url))
                .create();
        dialog.setOnShowListener(d -> {
            View cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) cancel.requestFocus();
        });
        dialog.show();
    }

    // ---- cards ----

    /** The launcher's own icon for a card's {@code icon}. */
    @DrawableRes
    static int siteIcon(String icon) {
        switch (icon) {
            case "youtube": return R.drawable.projects_ic_chat2_youtube;
            case "twitch": return R.drawable.projects_ic_chat2_twitch;
            case "instagram": return R.drawable.projects_ic_chat2_instagram;
            case "tiktok": return R.drawable.projects_ic_chat2_tiktok;
            case "x": return R.drawable.projects_ic_chat2_x;
            case "discord": return R.drawable.projects_ic_chat2_discord;
            case "projects": return R.drawable.projects_ic_chat2_cube;
            case "modrinth": return R.drawable.projects_ic_chat2_package;
            default: return R.drawable.projects_ic_chat2_globe;
        }
    }

    /** The icon of a site a card could not be asked for (the desktop's guess by host). */
    static String iconOf(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (under(h, "youtube.com") || under(h, "youtu.be")) return "youtube";
        if (under(h, "twitch.tv")) return "twitch";
        if (under(h, "modrinth.com")) return "modrinth";
        if (under(h, "projects.gg")) return "projects";
        if (under(h, "discord.com") || under(h, "discord.gg")) return "discord";
        return "globe";
    }

    private static boolean under(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /**
     * Draws a link's card into {@code holder}: the hub's card, the plain one (with {@code card} null and
     * {@code waiting} false: none will come), or while it is on its way its place at the likely size.
     * {@code fresh}: the card just came (its content fades in).
     */
    public static void bindCard(final FrameLayout holder, @Nullable ChatCards.Card card, final ChatLinks.MessageLink link,
                                boolean waiting, boolean fresh, final View.OnClickListener open) {
        Context context = holder.getContext();
        float dp = context.getResources().getDisplayMetrics().density;
        String key = (card == null ? (waiting ? "wait" : "plain") : card.state + "|" + card.title) + "|" + link.link.url;
        // Shown first: the row's bind hides the holder, and an unchanged card keeps its views (MOB1 review).
        holder.setVisibility(View.VISIBLE);
        if (key.equals(holder.getTag(R.id.projects_chat_state)) && holder.getChildCount() > 0) return;
        holder.setTag(R.id.projects_chat_state, key);
        holder.removeAllViews();
        int width = Math.round(244 * dp);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(10 * dp);
        surface.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_850));
        surface.setStroke(Math.max(1, Math.round(dp)), ContextCompat.getColor(context, R.color.projects_obsidian_700));
        box.setBackground(surface);
        box.setClipToOutline(true);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 10 * view.getResources().getDisplayMetrics().density);
            }
        });
        holder.addView(box, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        ChatLinkUi.Shape shape = card != null ? ChatLinkUi.shapeOf(card) : ChatLinkUi.shapeFor(link.link.url, link.host);
        if (card == null && waiting) {
            // Its place, held at the likely size (no jump when it comes).
            View skeleton = new View(context);
            skeleton.setBackgroundColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
            int height = shape == ChatLinkUi.Shape.BANNER ? Math.round(width * 9f / 16f) + Math.round(46 * dp) : Math.round(64 * dp);
            box.addView(skeleton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            holder.setContentDescription(null);
            holder.setOnClickListener(null);
            holder.setClickable(false);
            return;
        }
        boolean ready = card != null && card.state == ChatCards.State.READY;
        String site = card != null ? card.site : ChatLinkUi.domainOf(link.host);
        String icon = card != null ? card.icon : iconOf(link.host);
        if (ready && card.widePicture()) {
            FrameLayout picture = new FrameLayout(context);
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
            picture.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (card.kind.equals("video") || card.kind.equals("stream")) picture.addView(playMark(context), playParams(dp));
            box.addView(picture, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(width * 9f / 16f)));
            loadPicture(image, card.image);
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Math.round(10 * dp), Math.round(8 * dp), Math.round(10 * dp), Math.round(9 * dp));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(siteLine(context, icon, site, ready ? card.author : null));
        String title = ready ? card.title : null;
        if (title != null) texts.addView(text(context, title, 13.5f, R.color.projects_bone_100, true, 2));
        if (ready && card.desc != null) texts.addView(text(context, card.desc, 12.5f, R.color.projects_ash_400, false, 2));
        if (title == null) texts.addView(text(context, ChatLinkUi.addressOf(link.link.url, link.host), 12.5f, R.color.projects_ash_300, false, 1));
        if (ready && card.sidePicture()) {
            ImageView side = new ImageView(context);
            side.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable round = new GradientDrawable();
            round.setCornerRadius(8 * dp);
            round.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
            side.setBackground(round);
            side.setClipToOutline(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(56 * dp), Math.round(56 * dp));
            lp.setMarginStart(Math.round(10 * dp));
            row.addView(side, lp);
            loadPicture(side, card.image);
        }
        box.addView(row);
        holder.setContentDescription(title != null ? context.getString(R.string.projects_links_card, site, title)
                : context.getString(R.string.projects_links_card_plain, site));
        holder.setClickable(true);
        holder.setOnClickListener(open);
        Press.attach(holder, Press.Style.CARD);
        if (fresh && Motion.enabled(context)) {
            box.setAlpha(0f);
            box.animate().alpha(1f).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
        }
    }

    private static View siteLine(Context context, String icon, String site, @Nullable String author) {
        float dp = context.getResources().getDisplayMetrics().density;
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(context);
        mark.setImageResource(siteIcon(icon));
        mark.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_ash_400));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(mark, new LinearLayout.LayoutParams(Math.round(14 * dp), Math.round(14 * dp)));
        TextView name = text(context, author == null ? site : site + " · " + author, 12f, R.color.projects_ash_400, false, 1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(6 * dp));
        line.addView(name, lp);
        return line;
    }

    private static TextView text(Context context, String value, float sp, int color, boolean bold, int lines) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(ContextCompat.getColor(context, color));
        t.setMaxLines(lines);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        try {
            t.setTypeface(ResourcesCompat.getFont(context, bold ? R.font.figtree_semibold : R.font.figtree_regular));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        t.setPadding(0, Math.round(2 * context.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static View playMark(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        ImageView play = new ImageView(context);
        play.setImageResource(R.drawable.projects_ic_chat2_play);
        play.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_bone_100));
        int pad = Math.round(10 * dp);
        play.setPadding(pad, pad, pad, pad);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xCC121016);
        play.setBackground(circle);
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return play;
    }

    private static FrameLayout.LayoutParams playParams(float dp) {
        return new FrameLayout.LayoutParams(Math.round(40 * dp), Math.round(40 * dp), Gravity.CENTER);
    }

    /** A card's picture: decoded once (off the main thread), kept in a small memory cache. */
    private static void loadPicture(final ImageView view, @Nullable final String dataUri) {
        if (dataUri == null) return;
        Bitmap cached = PICTURES.get(dataUri);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setTag(R.id.projects_chat_state, dataUri);
        DECODE.execute(() -> {
            Bitmap bitmap = decode(dataUri);
            MAIN.post(() -> {
                if (bitmap == null) return;
                PICTURES.put(dataUri, bitmap);
                if (dataUri.equals(view.getTag(R.id.projects_chat_state))) {
                    view.setImageBitmap(bitmap);
                    if (Motion.enabled(view.getContext())) {
                        view.setAlpha(0f);
                        view.animate().alpha(1f).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
                    }
                }
            });
        });
    }

    /** A card's picture is drawn at most this big (pixels); a bigger one is read at a half, a quarter… */
    private static final long MAX_PIXELS = 1_000_000L;
    /** A picture bigger than this in pixels is not read at all. */
    private static final long MAX_SOURCE_PIXELS = 40_000_000L;

    @Nullable
    private static Bitmap decode(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
            // The hub passes a picture on as it is (at most 64 KB, but of any size in pixels): its size is read
            // first, a big one is read at a fraction of it and a huge one not at all.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            if ((long) bounds.outWidth * bounds.outHeight > MAX_SOURCE_PIXELS) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2;
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
