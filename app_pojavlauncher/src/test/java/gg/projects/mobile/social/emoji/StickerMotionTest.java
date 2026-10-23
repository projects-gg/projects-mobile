package gg.projects.mobile.social.emoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Sticker motion on the phone (the desktop's features/emoji/sticker-motion.test.ts). */
public class StickerMotionTest {
    @Test
    public void hasAPatternForEveryMotionAStickerNames() {
        assertEquals(14, StickerMotion.NAMES.size());
        for (String name : StickerMotion.NAMES) {
            StickerMotion motion = StickerMotion.of(name);
            assertNotNull(name, motion);
            assertEquals(name, motion.name);
        }
        for (StickerSet.Sticker sticker : StickerSetTest.PL1.stickers()) {
            assertNotNull(sticker.id, StickerMotion.of(sticker.motion));
        }
        assertNull(StickerMotion.of("spin"));
        assertNull(StickerMotion.of(null));
    }

    @Test
    public void movesOnceAndEndsWhereItBegan() {
        for (String name : StickerMotion.NAMES) {
            StickerMotion motion = StickerMotion.of(name);
            List<StickerMotion.Frame> frames = motion.frames;
            assertEquals(name, 0f, frames.get(0).offset, 0f);
            assertEquals(name, 1f, frames.get(frames.size() - 1).offset, 0f);
            for (int i = 1; i < frames.size(); i++) {
                assertTrue(name, frames.get(i).offset > frames.get(i - 1).offset);
            }
            assertTrue(name, frames.get(0).atRest());
            assertTrue(name, frames.get(frames.size() - 1).atRest());
            assertTrue(name, motion.duration > 0 && motion.duration <= 1200);
            assertTrue(name, motion.pivotX >= 0f && motion.pivotX <= 1f);
            assertTrue(name, motion.pivotY >= 0f && motion.pivotY <= 1f);
            boolean moves = false;
            for (StickerMotion.Channel channel : StickerMotion.Channel.values()) {
                moves |= motion.animates(channel);
            }
            assertTrue(name, moves);
            for (StickerMotion.Frame frame : frames) {
                assertTrue(name, frame.alpha > 0f && frame.alpha <= 1f);
                assertTrue(name, Math.abs(frame.translationX) <= 0.2f && Math.abs(frame.translationY) <= 0.2f);
                assertTrue(name, Math.abs(frame.rotation) <= 15f);
            }
        }
    }

    @Test
    public void keepsTheDesktopsTimingAndPivot() {
        StickerMotion bounce = StickerMotion.of("bounce");
        assertEquals(820, bounce.duration);
        assertEquals(0.5f, bounce.pivotX, 0f);
        assertEquals(1f, bounce.pivotY, 0f);
        StickerMotion.Frame top = bounce.frames.get(2);
        assertEquals(0.32f, top.offset, 0f);
        assertEquals(-0.14f, top.translationY, 0f);
        assertEquals(0.96f, top.scaleX, 0f);
        assertEquals(1.05f, top.scaleY, 0f);
        assertSame(StickerMotion.Easing.DOWN, top.easing);
        StickerMotion swing = StickerMotion.of("swing");
        assertEquals(0.35f, swing.pivotX, 0f);
        assertEquals(-12f, swing.frames.get(1).rotation, 0f);
        // Only glitch blinks, in steps.
        StickerMotion glitch = StickerMotion.of("glitch");
        assertTrue(glitch.animates(StickerMotion.Channel.ALPHA));
        assertTrue(glitch.frames.get(0).easing.stepEnd);
        assertTrue(glitch.frames.get(5).easing.isLinear());
        for (String name : StickerMotion.NAMES) {
            if (!name.equals("glitch")) assertFalse(name, StickerMotion.of(name).animates(StickerMotion.Channel.ALPHA));
        }
        StickerMotion.Easing out = StickerMotion.Easing.OUT;
        assertEquals(0.22f, out.x1, 0f);
        assertEquals(1f, out.y1, 0f);
        assertEquals(0.36f, out.x2, 0f);
        assertEquals(1f, out.y2, 0f);
    }

    @Test
    public void onlyFadesInUnderReducedMotion() {
        StickerMotion fade = StickerMotion.reduced();
        assertTrue(fade.isReduced());
        assertEquals(200, fade.duration);
        assertEquals(0f, fade.frames.get(0).alpha, 0f);
        assertEquals(1f, fade.frames.get(fade.frames.size() - 1).alpha, 0f);
        assertSame(StickerMotion.Easing.OUT, fade.frames.get(0).easing);
        for (StickerMotion.Channel channel : StickerMotion.Channel.values()) {
            assertEquals(channel.name(), channel == StickerMotion.Channel.ALPHA, fade.animates(channel));
        }
        assertFalse(StickerMotion.of("bounce").isReduced());
    }
}
