#!/usr/bin/env node
// BADGES-M (1 Nov 2026): writes the phone's profile badge drawings, app_pojavlauncher/src/main/java/gg/projects/
// mobile/social/MarkPaths.java, from the launcher's @pl/ui drawings (packages/ui/src/components/
// profile-mark-drawings.ts in projects-gg/MinecraftLauncher, itself made by packages/ui/scripts/
// build-profile-marks.mjs). Every shape and glyph keeps its own drawing for each pixel grid (12, 14, 16); this script
// only rewrites each path in the few commands Android's Path draws without a parser: absolute M, L, Q, C and Z, with
// every arc as cubic curves (at most a quarter turn each, checked against the true arc) and numbers to 1/1000 of a
// grid pixel. Node 22.18 or newer reads the TypeScript file as it is (its types are only stripped).
//
//   node scripts/projects/build-profile-marks.mjs <profile-mark-drawings.ts> [--check]
//
// --check writes nothing and fails when MarkPaths.java differs from what the drawings give.

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(here, "../../app_pojavlauncher/src/main/java/gg/projects/mobile/social/MarkPaths.java");

const SIZES = [12, 14, 16];
const SHAPES = ["seal", "round", "shield"];
const ROOMS = ["plain", "shield"];
const FILLED = ["star", "cloud", "bolt", "crown", "heart", "flask", "play", "trophy"];
/** The largest distance (grid pixels) a curve may stray from the arc it stands for. */
const ARC_TOLERANCE = 0.005;

function fail(message) {
  console.error(`build-profile-marks: ${message}`);
  process.exit(1);
}

// ---- SVG path → absolute M, L, Q, C, Z ----

function tokens(d) {
  const out = [];
  const re = /([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)/g;
  let match;
  let last = 0;
  while ((match = re.exec(d)) !== null) {
    const between = d.slice(last, match.index);
    if (/[^\s,]/.test(between)) fail(`unreadable path data "${between}" in ${d}`);
    out.push(match[1] !== undefined ? match[1] : Number(match[2]));
    last = re.lastIndex;
  }
  if (/[^\s,]/.test(d.slice(last))) fail(`unreadable path data at the end of ${d}`);
  return out;
}

function angle(ux, uy, vx, vy) {
  const dot = ux * vx + uy * vy;
  const len = Math.hypot(ux, uy) * Math.hypot(vx, vy);
  const a = Math.acos(Math.max(-1, Math.min(1, dot / len)));
  return ux * vy - uy * vx < 0 ? -a : a;
}

/** One SVG arc (SVG 1.1 implementation notes F.6.5) as cubic curves of at most a quarter turn each, checked. */
function arcToCubics(x1, y1, rxIn, ryIn, phiDeg, large, sweep, x2, y2) {
  if (x1 === x2 && y1 === y2) return [];
  let rx = Math.abs(rxIn);
  let ry = Math.abs(ryIn);
  if (rx === 0 || ry === 0) return [["L", x2, y2]];
  const phi = (phiDeg * Math.PI) / 180;
  const cos = Math.cos(phi);
  const sin = Math.sin(phi);
  const dx2 = (x1 - x2) / 2;
  const dy2 = (y1 - y2) / 2;
  const x1p = cos * dx2 + sin * dy2;
  const y1p = -sin * dx2 + cos * dy2;
  const lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  if (lambda > 1) {
    rx *= Math.sqrt(lambda);
    ry *= Math.sqrt(lambda);
  }
  const num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
  const den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
  const coef = (large === sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
  const cxp = (coef * rx * y1p) / ry;
  const cyp = (-coef * ry * x1p) / rx;
  const cx = cos * cxp - sin * cyp + (x1 + x2) / 2;
  const cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
  const ux = (x1p - cxp) / rx;
  const uy = (y1p - cyp) / ry;
  const vx = (-x1p - cxp) / rx;
  const vy = (-y1p - cyp) / ry;
  const theta1 = angle(1, 0, ux, uy);
  let delta = angle(ux, uy, vx, vy);
  if (!sweep && delta > 0) delta -= 2 * Math.PI;
  if (sweep && delta < 0) delta += 2 * Math.PI;
  const segments = Math.max(1, Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9));
  const step = delta / segments;
  const k = (4 / 3) * Math.tan(step / 4);
  const point = (a) => [
    cx + rx * Math.cos(a) * cos - ry * Math.sin(a) * sin,
    cy + rx * Math.cos(a) * sin + ry * Math.sin(a) * cos,
  ];
  const tangent = (a) => [
    -rx * Math.sin(a) * cos - ry * Math.cos(a) * sin,
    -rx * Math.sin(a) * sin + ry * Math.cos(a) * cos,
  ];
  const out = [];
  for (let i = 0; i < segments; i++) {
    const a1 = theta1 + i * step;
    const a2 = a1 + step;
    const [px1, py1] = point(a1);
    let [px2, py2] = point(a2);
    if (i === segments - 1) [px2, py2] = [x2, y2];
    const [tx1, ty1] = tangent(a1);
    const [tx2, ty2] = tangent(a2);
    const c = ["C", px1 + k * tx1, py1 + k * ty1, px2 - k * tx2, py2 - k * ty2, px2, py2];
    // The curve against the true ellipse: in the ellipse's own frame every point is 1 from its centre.
    for (let s = 0; s <= 20; s++) {
      const t = s / 20;
      const mt = 1 - t;
      const bx = mt ** 3 * px1 + 3 * mt * mt * t * c[1] + 3 * mt * t * t * c[3] + t ** 3 * px2;
      const by = mt ** 3 * py1 + 3 * mt * mt * t * c[2] + 3 * mt * t * t * c[4] + t ** 3 * py2;
      const ex = (cos * (bx - cx) + sin * (by - cy)) / rx;
      const ey = (-sin * (bx - cx) + cos * (by - cy)) / ry;
      const off = Math.abs(Math.hypot(ex, ey) - 1) * Math.min(rx, ry);
      if (off > ARC_TOLERANCE) fail(`an arc's curve strays ${off.toFixed(4)} px (arc to ${x2},${y2})`);
    }
    out.push(c);
  }
  return out;
}

/** The commands of one path: absolute M, L, Q, C and Z only. */
function normalize(d) {
  const t = tokens(d);
  const out = [];
  let i = 0;
  let cmd = null;
  let x = 0;
  let y = 0;
  let sx = 0;
  let sy = 0;
  const num = () => {
    const v = t[i++];
    if (typeof v !== "number") fail(`a number was expected in ${d}`);
    return v;
  };
  while (i < t.length) {
    if (typeof t[i] === "string") cmd = t[i++];
    else if (cmd === null) fail(`a path must start with a command: ${d}`);
    else if (cmd === "M") cmd = "L";
    switch (cmd) {
      case "M":
        x = num();
        y = num();
        sx = x;
        sy = y;
        out.push(["M", x, y]);
        break;
      case "L":
        x = num();
        y = num();
        out.push(["L", x, y]);
        break;
      case "H":
        x = num();
        out.push(["L", x, y]);
        break;
      case "V":
        y = num();
        out.push(["L", x, y]);
        break;
      case "Q": {
        const c = ["Q", num(), num(), num(), num()];
        x = c[3];
        y = c[4];
        out.push(c);
        break;
      }
      case "C": {
        const c = ["C", num(), num(), num(), num(), num(), num()];
        x = c[5];
        y = c[6];
        out.push(c);
        break;
      }
      case "A": {
        const rx = num();
        const ry = num();
        const phi = num();
        const large = num() !== 0;
        const sweep = num() !== 0;
        const x2 = num();
        const y2 = num();
        out.push(...arcToCubics(x, y, rx, ry, phi, large, sweep, x2, y2));
        x = x2;
        y = y2;
        break;
      }
      case "Z":
      case "z":
        out.push(["Z"]);
        x = sx;
        y = sy;
        cmd = null;
        break;
      default:
        // Relative commands and the smooth curves are not in the drawings: a new drawing using them fails loudly.
        fail(`command ${cmd} is not supported (absolute M L H V Q C A Z only): ${d}`);
    }
  }
  return out;
}

function number(v) {
  const r = Math.round(v * 1000) / 1000;
  return Object.is(r, -0) ? "0" : String(r);
}

/** The path as MarkGeometry reads it: one space between every command letter and number. */
function written(commands, size, label) {
  for (const c of commands) {
    for (let j = 1; j < c.length; j += 2) {
      // On-curve points stay on the grid; control points may lean out a little.
      const slack = c[0] === "C" || c[0] === "Q" ? 1 : 0.01;
      if (c[j] < -slack || c[j] > size + slack || c[j + 1] < -slack || c[j + 1] > size + slack) {
        fail(`${label} leaves its ${size} px grid at ${c[j]},${c[j + 1]}`);
      }
    }
  }
  return commands.map((c) => [c[0], ...c.slice(1).map(number)].join(" ")).join(" ");
}

// ---- Java ----

function javaString(s) {
  return `"${s}"`;
}

function build(drawings) {
  const { SHAPE_PATHS, GLYPH_PATHS, CHECK_PATHS } = drawings;
  const lines = [];
  lines.push("package gg.projects.mobile.social;");
  lines.push("");
  lines.push("/**");
  lines.push(" * Generated by scripts/projects/build-profile-marks.mjs (BADGES-M) from the launcher's @pl/ui drawings");
  lines.push(" * (profile-mark-drawings.ts): every badge outline and glyph drawn for each size's pixel grid, rewritten as");
  lines.push(" * absolute M, L, Q, C and Z (arcs as cubic curves) for {@link MarkGeometry}. Change the drawings there and run");
  lines.push(" * the script again, never by hand.");
  lines.push(" */");
  lines.push("final class MarkPaths {");
  lines.push("    private MarkPaths() {}");
  lines.push("");
  lines.push(`    /** The grids, in the order of every table below. */`);
  lines.push(`    static final int[] SIZES = {${SIZES.join(", ")}};`);
  lines.push(`    static final String[] SHAPES = {${SHAPES.map(javaString).join(", ")}};`);
  lines.push(`    /** The filled glyphs (the check is a stroke, {@link #CHECKS}). */`);
  lines.push(`    static final String[] FILLED = {${FILLED.map(javaString).join(", ")}};`);
  lines.push("");
  lines.push("    /** The outline per size and shape (filled with the badge's colour). */");
  lines.push("    static final String[][] OUTLINES = {");
  for (const size of SIZES) {
    lines.push("            {");
    for (const shape of SHAPES) {
      const d = SHAPE_PATHS[size]?.[shape];
      if (typeof d !== "string") fail(`no ${shape} outline at ${size}`);
      lines.push(`                    ${javaString(written(normalize(d), size, `${shape} ${size}`))},`);
    }
    lines.push("            },");
  }
  lines.push("    };");
  lines.push("");
  lines.push("    /**");
  lines.push("     * The filled glyphs (white) per size, room ({@code 0} inside the seal and the round badge, {@code 1} the");
  lines.push("     * shield's, a little smaller and higher) and glyph ({@link #FILLED}'s order).");
  lines.push("     */");
  lines.push("    static final String[][][] GLYPHS = {");
  for (const size of SIZES) {
    lines.push("            {");
    for (const room of ROOMS) {
      lines.push("                    {");
      for (const glyph of FILLED) {
        const d = GLYPH_PATHS[size]?.[room]?.[glyph];
        if (typeof d !== "string") fail(`no ${glyph} glyph (${room}) at ${size}`);
        lines.push(`                            ${javaString(written(normalize(d), size, `${glyph} ${room} ${size}`))},`);
      }
      lines.push("                    },");
    }
    lines.push("            },");
  }
  lines.push("    };");
  lines.push("");
  lines.push("    /** The check per size and room: a white stroke with round caps and joins, its legs at 45° (SOC-VERIFY). */");
  lines.push("    static final String[][] CHECKS = {");
  for (const size of SIZES) {
    const check = CHECK_PATHS[size];
    if (!check) fail(`no check at ${size}`);
    lines.push(
      `            {${ROOMS.map((room) => javaString(written(normalize(check[room]), size, `check ${room} ${size}`))).join(", ")}},`,
    );
  }
  lines.push("    };");
  lines.push("");
  lines.push("    /** The check's stroke width per size, in grid pixels. */");
  lines.push(`    static final float[] CHECK_WIDTHS = {${SIZES.map((size) => `${number(CHECK_PATHS[size].width)}f`).join(", ")}};`);
  lines.push("}");
  lines.push("");
  return lines.join("\n");
}

const args = process.argv.slice(2);
const check = args.includes("--check");
const source = args.find((a) => !a.startsWith("--"));
if (!source) fail("usage: node scripts/projects/build-profile-marks.mjs <profile-mark-drawings.ts> [--check]");
const drawings = await import(pathToFileURL(resolve(source)).href);
const java = build(drawings);
if (check) {
  let now = "";
  try {
    now = readFileSync(OUT, "utf8");
  } catch {
    // Missing: differs.
  }
  if (now !== java) fail(`${OUT} differs from the drawings: run without --check`);
  console.log("MarkPaths.java matches the drawings");
} else {
  writeFileSync(OUT, java);
  console.log(`wrote ${OUT}`);
}
