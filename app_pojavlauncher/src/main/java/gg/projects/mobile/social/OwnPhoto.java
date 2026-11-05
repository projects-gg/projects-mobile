package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gg.projects.mobile.social.SocialModel.Profile;

/**
 * MOB-PARITY: "Profil fotoğrafı" (the desktop's settings picker, {@code features/avatars}): the picture friends see.
 * First the linked Discord account's picture (only when it has one), then the head of the player's Microsoft account
 * (only when its skin is known), then the 24 avatars the app carries ({@code assets/avatars}, the desktop's manifest
 * order). The service takes {@code discord}, {@code preset:<slug>} or {@code minecraft:<texture>}. Pure Java.
 */
public final class OwnPhoto {
    private OwnPhoto() {}

    /** The desktop's manifest, in its order (each has {@code assets/avatars/<slug>.webp}). */
    public static final List<String> PRESETS = Collections.unmodifiableList(Arrays.asList(
            "golden-dragon", "ninja", "van-cat", "baby-dragon", "fox", "knight", "panda", "frost-dragon", "inventor", "owl",
            "wizard", "potion", "robot", "axolotl", "ember-dragon", "pirate", "lantern", "wolf", "crystal", "dwarf-miner",
            "compass", "bunny", "spellbook", "elf-ranger"));

    private static final Pattern DISCORD_PHOTO =
            Pattern.compile("^https://cdn\\.discordapp\\.com/avatars/(\\d{1,20})/((?:a_)?[0-9a-f]{32})\\.(?:png|webp|gif|jpg)(?:\\?.*)?$");

    public enum Kind { DISCORD, MINECRAFT, PRESET }

    public static final class Choice {
        public final Kind kind;
        /** What {@code PATCH /v1/me} takes as {@code avatar}. */
        public final String value;
        /** {@link Kind#PRESET}: the avatar's slug. */
        @Nullable
        public final String slug;
        /** {@link Kind#DISCORD}: the Discord account and its picture's hash; {@link Kind#MINECRAFT}: the texture. */
        @Nullable
        final String discordId, hash;

        Choice(Kind kind, String value, @Nullable String slug, @Nullable String discordId, @Nullable String hash) {
            this.kind = kind;
            this.value = value;
            this.slug = slug;
            this.discordId = discordId;
            this.hash = hash;
        }
    }

    /** The choices in the picker's order. */
    public static List<Choice> choices(@Nullable String discordPhoto, @Nullable String minecraftTexture) {
        List<Choice> out = new ArrayList<>();
        Matcher m = discordPhoto == null ? null : DISCORD_PHOTO.matcher(discordPhoto);
        if (m != null && m.matches()) out.add(new Choice(Kind.DISCORD, "discord", null, m.group(1), m.group(2)));
        if (minecraftTexture != null && minecraftTexture.matches("[0-9a-f]{40,64}")) {
            out.add(new Choice(Kind.MINECRAFT, "minecraft:" + minecraftTexture, null, null, minecraftTexture));
        }
        for (String slug : PRESETS) out.add(new Choice(Kind.PRESET, "preset:" + slug, slug, null, null));
        return Collections.unmodifiableList(out);
    }

    /** Which choice the player's {@code avatar} is; null when none of them (a Discord picture without a tile). */
    @Nullable
    public static String chosen(@Nullable String avatar, List<Choice> choices) {
        if (avatar == null) return null;
        for (Choice c : choices) if (c.value.equals(avatar)) return c.value;
        return null;
    }

    /** The player's profile as it looks with {@code choice}, for its tile. */
    public static Profile preview(Profile me, Choice choice) {
        switch (choice.kind) {
            case DISCORD:
                return new Profile(me.id, me.username, me.displayName, "discord", choice.hash, null, choice.discordId);
            case MINECRAFT:
                return new Profile(me.id, me.username, me.displayName, choice.value, null, null, null);
            case PRESET:
            default:
                return new Profile(me.id, me.username, me.displayName, choice.value, null, null, null);
        }
    }
}
