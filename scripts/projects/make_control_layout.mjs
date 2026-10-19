#!/usr/bin/env node
// Projects fork (M15): writes the default on-screen control layout in both languages,
// app_pojavlauncher/src/main/assets/default.json (Turkish) and default_en.json (English).
//
// Usage: node scripts/projects/make_control_layout.mjs
//
// The layout keeps the Minecraft HUD clear on 16:9 to 21:9 phones at the game's Auto GUI scale
// (ControlLayoutTest checks it): a block top-left (pause, chat, inventory; hide, menu and the
// "more" and "mods" drawers), the joystick bottom-left with sneak and sprint above it, use, attack
// and jump bottom-right. The top-right (toasts, effects, minimap), the top centre (boss bars), the
// right middle (scoreboard), the bottom centre (hotbar and vitals) and the crosshair stay free.
// Positions are dp from a screen corner; offsets between buttons follow the button scale setting
// (${preferred_scale}), the edge inset does not. ${inset} is the cutout inset (ControlData).
// Both files share one geometry; only the labels differ (ControlLabels maps them).
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const ASSETS = join(ROOT, "app_pojavlauncher", "src", "main", "assets");

export const EDGE = 12; // dp from the screen edges
const k = "${preferred_scale} / 100"; // offsets between keys follow the button size setting

// GLFW key codes and upstream's special buttons (ControlData.SPECIALBTN_*).
const K = {
  SPACE: 32, ESC: 256, TAB: 258, CAPS: 280, F1: 290, F3: 292, F5: 294, LSHIFT: 340, LCTRL: 341,
  B: 66, C: 67, E: 69, F: 70, M: 77, Q: 81, T: 84, X: 88, Z: 90,
  KEYBOARD: -1, TOGGLECTRL: -2, MOUSE_LEFT: -3, MOUSE_RIGHT: -4, VIRTUALMOUSE: -5, MOUSE_MID: -6, MENU: -9,
};

// Colours: ARGB, written as Java ints (Gson reads them into int fields).
const argb = (hex) => hex | 0;
const STYLE = {
  primary: { bg: argb(0xa3121016), ring: argb(0x4df3ebdd) }, // obsidian 64 %, bone 30 %
  secondary: { bg: argb(0x80121016), ring: argb(0x38f3ebdd) }, // obsidian 50 %, bone 22 %
  stick: { bg: argb(0x5c121016), ring: argb(0x47f3ebdd) }, // obsidian 36 %, bone 28 %
  note: { bg: argb(0xd9121016), ring: argb(0x4df3ebdd) },
};

const GAME = { game: true, menu: false };
const MENU = { game: false, menu: true };
const BOTH = { game: true, menu: true };

// Top-left block.
const ROW1_H = 48;
const ROW2_Y = ROW1_H + 8;
const ROW2_H = 40;
// Drawer panels: a grid to the right of the block, level with its top.
const PANEL_X = 194;
const TILE_W = 64;
const TILE_H = 48;
const TILE_GAP = 6;
const tile = (i) => ({
  x: { edge: "left", off: PANEL_X + (i % 3) * (TILE_W + TILE_GAP) },
  y: { edge: "top", off: Math.floor(i / 3) * (TILE_H + TILE_GAP) },
  w: TILE_W,
  h: TILE_H,
});
// Joystick bottom-left; sneak and sprint centred above it, or centred between it and the top-left
// block when a short screen or large buttons leave less room.
const STICK = 150;
const PAIR = 52;
const PAIR_X = STICK / 2 - (PAIR * 2 + 8) / 2;
const BLOCK_H = ROW2_Y + ROW2_H;
const PAIR_ABOVE = `(\${screen_height} - \${height} - px(${EDGE}) - px(${STICK + 8}) * ${k})`;
const PAIR_MIDDLE = `((\${screen_height} - px(${STICK - BLOCK_H}) * ${k}) / 2 - \${height} / 2)`;
const PAIR_Y = `(${PAIR_ABOVE} + ${PAIR_MIDDLE} + abs(${PAIR_ABOVE} - ${PAIR_MIDDLE})) / 2`;
// Use, attack and jump bottom-right, centred on the jump button. Where the row is wider than the
// room beside the hotbar (16:9 phones; the hotbar measured at its largest, a GUI pixel being
// screen height / 240), "use" stands on "attack" instead of beside it. clamp01(v) is written with
// abs(), the one function exp4j gives a layout for it.
const JUMP = 64;
const ACT = 56;
const ACT_Y = (JUMP - ACT) / 2;
const HOTBAR_RIGHT = "(${screen_width} / 2 + 91 * ${screen_height} / 240)";
const USE_ROW_X = `(\${screen_width} - \${width} - \${inset} - px(${EDGE}) - px(${JUMP + 8 + ACT + 8}) * ${k})`;
const USE_STACK_X = `(\${screen_width} - \${width} - \${inset} - px(${EDGE}) - px(${JUMP + 8}) * ${k})`;
const USE_ROW_Y = `(\${screen_height} - \${height} - px(${EDGE}) - px(${ACT_Y}) * ${k})`;
const USE_STACK_Y = `(\${screen_height} - \${height} - px(${EDGE}) - px(${ACT_Y + ACT + 8}) * ${k})`;
const CROWDED = `((${HOTBAR_RIGHT} + px(6) - ${USE_ROW_X}) * 1000)`;
const STACKED = `((abs(${CROWDED}) - abs(${CROWDED} - 1) + 1) / 2)`;
const USE_X = `${USE_ROW_X} + ${STACKED} * (${USE_STACK_X} - ${USE_ROW_X})`;
const USE_Y = `${USE_ROW_Y} + ${STACKED} * (${USE_STACK_Y} - ${USE_ROW_Y})`;

export const CONTROLS = [
  // Top-left, first row: what a player reaches for most.
  { icon: "pause", tr: "DURAKLAT", en: "PAUSE", keys: [K.ESC], ...GAME,
    x: { edge: "left", off: 0 }, y: { edge: "top", off: 0 }, w: 48, h: ROW1_H },
  { icon: "back", tr: "GERİ", en: "BACK", keys: [K.ESC], ...MENU,
    x: { edge: "left", off: 0 }, y: { edge: "top", off: 0 }, w: 48, h: ROW1_H },
  // Chat opens the soft keyboard with it; in menus the same place brings the keyboard back.
  { icon: "chat", tr: "SOHBET", en: "CHAT", keys: [K.T, K.KEYBOARD], ...GAME,
    x: { edge: "left", off: 56 }, y: { edge: "top", off: 0 }, w: 48, h: ROW1_H },
  { icon: "keyboard", tr: "KLAVYE", en: "KEYBOARD", keys: [K.KEYBOARD], ...MENU,
    x: { edge: "left", off: 56 }, y: { edge: "top", off: 0 }, w: 48, h: ROW1_H },
  { icon: "inventory", tr: "ENVANTER", en: "INVENTORY", keys: [K.E], ...GAME,
    x: { edge: "left", off: 112 }, y: { edge: "top", off: 0 }, w: 56, h: ROW1_H },
  // Second row, lighter: hide (always shown, upstream keeps it), menu and the two drawers.
  { icon: "hide", tr: "GİZLE", en: "HIDE", keys: [K.TOGGLECTRL], ...BOTH, style: "secondary",
    x: { edge: "left", off: 0 }, y: { edge: "top", off: ROW2_Y }, w: 40, h: ROW2_H },
  { icon: "menu", tr: "MENÜ", en: "MENU", keys: [K.MENU], ...BOTH, style: "secondary",
    x: { edge: "left", off: 46 }, y: { edge: "top", off: ROW2_Y }, w: 40, h: ROW2_H },
  { drawer: true, icon: "more", tr: "DİĞER", en: "MORE", keys: [], ...GAME, style: "secondary",
    x: { edge: "left", off: 92 }, y: { edge: "top", off: ROW2_Y }, w: 44, h: ROW2_H,
    subs: [
      { icon: "view", tr: "KAMERA", en: "CAMERA", keys: [K.F5], ...tile(0) },
      { icon: "players", tr: "OYUNCULAR", en: "PLAYERS", keys: [K.TAB], toggle: true, ...tile(1) },
      { icon: "drop", tr: "BIRAK", en: "DROP", keys: [K.Q], ...tile(2) },
      { icon: "swap", tr: "EL DEĞİŞ", en: "SWAP HANDS", keys: [K.F], ...tile(3) },
      { icon: "pick", tr: "BLOK SEÇ", en: "PICK BLOCK", keys: [K.MOUSE_MID], ...tile(4) },
      { icon: "hud", tr: "ARAYÜZ", en: "HUD", keys: [K.F1], ...tile(5) },
      { icon: "keyboard", tr: "KLAVYE", en: "KEYBOARD", keys: [K.KEYBOARD], ...tile(6) },
      { icon: "mouse", tr: "FARE", en: "MOUSE", keys: [K.VIRTUALMOUSE], ...tile(7) },
      { icon: "debug", tr: "F3", en: "F3", keys: [K.F3], ...tile(8) },
    ] },
  // Keys of the Projects pack's mods: WI Zoom, Gamma Utils, Simple Voice Chat, Xaero's Better PvP.
  // Zoom, brightness and push-to-talk follow the launchers' conflict-free table (ProLauncher2
  // crates/pl-mc/data/key-bindings.json, copied to assets/key-bindings.json), which a new options.txt
  // of the Projects profile gets; the others keep the mods' own defaults. Zoom and talk act while
  // held. In a game without the mod they do nothing.
  { drawer: true, icon: "mods", tr: "MODLAR", en: "MODS", keys: [], ...GAME, style: "secondary",
    x: { edge: "left", off: 142 }, y: { edge: "top", off: ROW2_Y }, w: 44, h: ROW2_H,
    subs: [
      { icon: "zoom", tr: "YAKINLAŞ", en: "ZOOM", keys: [K.C], ...tile(0) },
      { icon: "bright", tr: "PARLAKLIK", en: "BRIGHTNESS", keys: [K.X], ...tile(1) },
      { icon: "mic", tr: "MİKROFON", en: "MICROPHONE", keys: [K.M], ...tile(2) },
      { icon: "waypoint", tr: "YOL NOKTASI", en: "WAYPOINT", keys: [K.B], ...tile(3) },
      { icon: "map", tr: "HARİTA", en: "MAP", keys: [K.Z], ...tile(4) },
      { icon: "talk", tr: "KONUŞ", en: "TALK", keys: [K.CAPS], ...tile(5) },
    ] },
  // Bottom-left.
  { joystick: true, icon: "stick", tr: "", en: "", keys: [], ...GAME, style: "stick",
    x: { edge: "left", off: 0 }, y: { edge: "bottom", off: 0 }, w: STICK, h: STICK },
  { icon: "sneak", tr: "EĞİL", en: "SNEAK", keys: [K.LSHIFT], toggle: true, ...GAME,
    x: { edge: "left", off: PAIR_X }, y: { expr: PAIR_Y }, w: PAIR, h: PAIR },
  { icon: "sprint", tr: "KOŞ", en: "SPRINT", keys: [K.LCTRL], toggle: true, ...GAME,
    x: { edge: "left", off: PAIR_X + PAIR + 8 }, y: { expr: PAIR_Y }, w: PAIR, h: PAIR },
  // Bottom-right. Attack and use pass the finger's movement on to the camera, so a player aims
  // while holding them (mining, eating, a bow).
  { icon: "jump", tr: "ZIPLA", en: "JUMP", keys: [K.SPACE], ...GAME,
    x: { edge: "right", off: 0 }, y: { edge: "bottom", off: 0 }, w: JUMP, h: JUMP },
  { icon: "attack", tr: "SALDIR", en: "ATTACK", keys: [K.MOUSE_LEFT], pass: true, ...GAME,
    x: { edge: "right", off: JUMP + 8 }, y: { edge: "bottom", off: ACT_Y }, w: ACT, h: ACT },
  { icon: "use", tr: "KULLAN", en: "USE", keys: [K.MOUSE_RIGHT], pass: true, ...GAME,
    x: { expr: USE_X }, y: { expr: USE_Y }, w: ACT, h: ACT },
  // Bottom-right in menus (a tap there is already a left click).
  { icon: "shift", tr: "SHIFT", en: "SHIFT", keys: [K.LSHIFT], toggle: true, ...MENU,
    x: { edge: "right", off: 0 }, y: { edge: "bottom", off: 0 }, w: JUMP, h: JUMP },
  { icon: "rightclick", tr: "SAĞ TIK", en: "RIGHT CLICK", keys: [K.MOUSE_RIGHT], ...MENU,
    x: { edge: "right", off: JUMP + 8 }, y: { edge: "bottom", off: ACT_Y }, w: ACT, h: ACT },
  // Shown in the controls editor only (upstream's welcome text, no glyph).
  { note: true, tr: "DÜZENLEYİCİYE HOŞ GELDİN.\nAYARLARI İÇİN BİR TUŞA DOKUN.\nDAHA FAZLASI İÇİN ÜSTTEKİ DİŞLİYE DOKUN.",
    en: "WELCOME TO THE EDITOR.\nTAP A BUTTON TO SEE ITS SETTINGS.\nTAP THE GEAR AT THE TOP FOR MORE.",
    keys: [], game: false, menu: false, style: "note", center: true, w: 288, h: 110 },
];

const scaled = (off) => (off ? ` - px(${off}) * \${preferred_scale} / 100` : "");
const scaledPlus = (off) => (off ? ` + px(${off}) * \${preferred_scale} / 100` : "");

function xExpr(c) {
  if (c.center) return "${screen_width} / 2 - ${width} / 2";
  if (c.x.expr) return c.x.expr;
  return c.x.edge === "left"
    ? `\${inset} + px(${EDGE})${scaledPlus(c.x.off)}`
    : `\${screen_width} - \${width} - \${inset} - px(${EDGE})${scaled(c.x.off)}`;
}

function yExpr(c) {
  if (c.center) return "${screen_height} / 2 - ${height} / 2";
  if (c.y.expr) return c.y.expr;
  return c.y.edge === "top"
    ? `px(${EDGE})${scaledPlus(c.y.off)}`
    : `\${screen_height} - \${height} - px(${EDGE})${scaled(c.y.off)}`;
}

function keycodes(keys) {
  const out = [0, 0, 0, 0];
  keys.forEach((k, i) => (out[i] = k));
  return out;
}

/** One ControlData as Gson writes it (keys sorted, like the earlier files). */
function data(c, lang, inherited = {}) {
  const style = STYLE[c.style || inherited.style || "primary"];
  const d = {
    bgColor: style.bg,
    cornerRadius: c.note ? 12 : c.joystick ? 0 : 40,
    displayInGame: c.game ?? inherited.game,
    displayInMenu: c.menu ?? inherited.menu,
    dynamicX: xExpr(c),
    dynamicY: yExpr(c),
    height: c.h,
    isSwipeable: false,
    isToggle: Boolean(c.toggle),
    keycodes: keycodes(c.keys),
    name: lang === "tr" ? c.tr : c.en,
    opacity: 1,
    passThruEnabled: Boolean(c.pass),
    strokeColor: style.ring,
    strokeWidth: c.note ? 1 : 1.5,
    width: c.w,
  };
  if (c.icon) d.icon = c.icon;
  if (c.joystick) {
    d.absolute = false;
    d.forwardLock = false;
    d.name = "";
  }
  return Object.fromEntries(Object.keys(d).sort().map((k) => [k, d[k]]));
}

export function layout(lang) {
  const out = { mControlDataList: [], mDrawerDataList: [], mJoystickDataList: [], scaledAt: 100, version: 8 };
  for (const c of CONTROLS) {
    if (c.joystick) out.mJoystickDataList.push(data(c, lang));
    else if (c.drawer) {
      out.mDrawerDataList.push({
        buttonProperties: c.subs.map((s) => data({ ...s, game: true, menu: true }, lang, { style: "primary" })),
        orientation: "FREE",
        properties: data(c, lang),
      });
    } else out.mControlDataList.push(data(c, lang));
  }
  return out;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  for (const [file, lang] of [["default.json", "tr"], ["default_en.json", "en"]]) {
    writeFileSync(join(ASSETS, file), JSON.stringify(layout(lang)) + "\n");
    console.log("wrote", file);
  }
}
