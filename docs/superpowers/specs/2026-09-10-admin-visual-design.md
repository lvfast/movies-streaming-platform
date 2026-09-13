# Admin Visual Design

Date: 2026-09-13
Scope: P6 Admin core UI (protected shell, library, draft editor).

## Design read

Reading this as: a compact, internal **operations workspace** for film
administrators, in the product's existing **dark cinematic** language, leaning
toward **neutral zinc surfaces + a single brand-red accent + restrained,
reduced-motion-friendly interaction**.

This is a dashboard / data-table surface, not a marketing page, so the
landing-page rules (hero variance, kinetic type, marquee) do not apply. The
goal is legibility, keyboard flow and density, not spectacle.

Dials: `DESIGN_VARIANCE 4`, `MOTION_INTENSITY 2`, `VISUAL_DENSITY 7`.

## Color

The workspace reuses the application's existing dark palette direction
(`:root` in `frontend/src/styles.css`) and scopes it under admin-specific
custom properties so the public site is never affected. One accent (brand
red); no purple, no gradients, no pure black.

| Token | Value | Use |
| --- | --- | --- |
| `--admin-bg` | `#0b0b0e` | Workspace background |
| `--admin-surface` | `#15151a` | Panels, side navigation, tables |
| `--admin-surface-raised` | `#1e1e25` | Inputs, cards, hovered rows |
| `--admin-text` | `#f4f4f5` | Primary text |
| `--admin-muted` | `#a1a1aa` | Secondary text, meta |
| `--admin-accent` | `#e50914` | Primary actions, selected/focus accents |
| `--admin-accent-strong` | `#ff1f2c` | Accent hover/active |
| `--admin-danger` | `#ff453a` | Destructive confirmation, error text/borders |
| `--admin-border` | `#2a2a32` | Hairline separators, control borders |
| `--admin-focus` | `#ffffff` | Focus ring (inherits global 3px white outline) |

Contrast: body text `#f4f4f5` on `#15151a`/`#0b0b0e` exceeds WCAG AA (≈14:1);
muted `#a1a1aa` on `#15151a` exceeds AA (≈7:1). Accent `#e50914` is used only
for large text, fills with white text (≈4.7:1) and non-text indicators so color
is never the only state signal.

## Typography

Inherit the application stack (`Inter, ui-sans-serif, system-ui, ...`); no new
fonts are loaded. Type scale:

- Page title: 24px / 600
- Section heading: 16px / 600
- Body / controls: 14px / 400
- Meta, captions, table numeric cells: 13px / 400, `font-variant-numeric: tabular-nums`
- Lifecycle/state badges: 12px / 600, uppercase

## Spacing and radius

- Base unit: 4px. Section padding 24px (16px at ≤1024px, 16px at ≤390px).
- Control height: 36px; compact table controls 32px.
- One corner-radius scale: interactive controls 6px, surfaces 8px. No pill
  buttons, no mixed radii.

## Layout

- Desktop (≥1024px): persistent 240px side navigation + full-width content.
- ≤1024px: side navigation collapses to a compact top bar with a drawer toggle;
  the closed drawer is `visibility: hidden` (not focusable), focus moves to the
  close control on open and returns to the toggle on close, and Escape closes.
- ≤390px: drawer navigation; tables collapse to an essential-field layout
  (title, lifecycle, active media, revision) without horizontal clipping.
  Non-essential columns (`Released`, `Updated`) are hidden.

> Note: the library's "active media" column/filter is derived from
> `activeMediaVersionId` (whether the movie has an active media version). It is
> not a per-version processing state; exposing `UPLOADING`/`PROCESSING`/
> `READY`/`FAILED` in the library requires the P7 media-version contract and is
> deferred to post-MVP.

## States

Color is never the only indicator: lifecycle/state badges always carry a text
label, and focus/hover use borders plus the global white focus ring.

- Loading: skeleton rows matching the final table row shape.
- Empty: composed message with a single primary action (e.g. "Create film").
- Error: inline alert with detail, request reference, and a retry action.
- Focus: inherited `outline: 3px solid #fff; outline-offset: 3px`.

## Motion

Static by default. Only `:hover` / `:active` / `:focus-visible` transitions on
`transform` and `opacity`, all gated behind `prefers-reduced-motion`. No
infinite loops, no scroll-driven animation.

## Scope guard

No public/consumer-page CSS changes are planned. All rules live in
`frontend/src/admin/admin.css`, scoped to `.admin-` class names, and import
only tokens already present in the application.
