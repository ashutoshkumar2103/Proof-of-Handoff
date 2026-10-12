# HandOffly Mobile — design system

The rules behind `src/styles.css` and the shared components. Follow them so a new screen looks like the rest. (The other HandOffly apps have their own guide, `docs/UI_GUIDELINES.md`; this app shares no code with them, so it repeats the brand tokens and keeps its own rules here.)

> Before adding a component, class or colour, look in `src/components/` and `src/styles.css`. Extend the shared one if it nearly fits.

## Tokens (top of `styles.css`)

| Token | Value | Use |
|---|---|---|
| `--bg` `--surface` `--surface-2` `--surface-raised` `--field` | layered: page, cards and bars, what sits inside a card, what floats (sheets), the inside of an input | pick the layer by what the element *is* |
| `--primary` `--primary-soft` | brand blue | primary actions, the current page, selected filters |
| `--success` `--warning` `--danger` `--neutral` (+ `-soft`) | status colours | badges, notices, quantities |
| `--space-1…5` | 4, 8, 12, 16, 24 px | every gap and padding |
| `--radius` / `--radius-sm` / `--radius-pill` | 10 / 8 / 999 px | cards and sheets / controls and notices / badges and chips |
| `--control-h` / `--control-h-sm` | 44 / 36 px | every input, select and button (the touch target); the small size only for secondary actions |
| `--card-pad` | 12 px | padding inside a card |
| `--icon-size` | 20 px (24 px in the bottom bar) | all icons |
| `--fs-title` `--fs-heading` `--fs-body` `--fs-small` `--fs-caption` | 22 / 17 / 16 / 14 / 12 px | page heading, card heading, body, secondary text, captions |

Never write a colour, radius, shadow or spacing literal where a token exists. Light and dark come from the same tokens (dark follows the phone unless the person chose a theme in Account), so a component that uses only tokens works in both.

## Rules

- **Type:** one family (`--font`). `h1` once per screen; `h2` for card and sheet headings; weight 600–700 for labels and buttons. Secondary text is `.muted`, hints under a field `.hint`.
- **Cards:** 1px `--border`, `--radius`, `--shadow` (one soft shadow); only things that float (sheets, toasts) use `--shadow-lg`. No gradients, no decorative effects.
- **Buttons:** one primary action per area (`.btn-primary`); everything else `.btn`. `.btn-negative` for what says no (Cancel, Close, Remove, Dismiss), `.btn-danger` for the destructive action itself. `.btn-sm` only for secondary actions.
- **Forms:** a visible `<label>` above every control (never placeholder-only), `.hint` for help, `.form-error` for a problem. 16 px text in inputs so the phone does not zoom. Dates and times are native `date` / `time` inputs. An icon inside a field (`.input-icon`: the eye, the search glass) never changes the field's width or height. Dropdowns are `.select-wrap` (one caret icon for all).
- **Icons:** the one set in `components/Icon.tsx` — 24 px outline, 1.75 stroke, round caps, `currentColor`. They are decorative (`aria-hidden`); the control around them carries the accessible name. No emoji, no mixed styles. Add a missing shape to that file rather than drawing one in a page.
- **States:** focus is a 2 px `--primary` outline (never removed); disabled is 55% opacity; loading is `Spinner` or the button's own "…ing" label; empty is `EmptyState`; a failed load is `ErrorNotice` with *Try again* (it stays); a result message ("Draft saved.") is a `Toast` that clears itself after 5 s (`useTransient`). The current page / selected choice is blue, bold, and also marked another way (a bar, a ring, `aria-current`, `aria-pressed`) so it never rests on colour alone.
- **Navigation:** the bottom bar holds the three places used all day (Handoffs, New, Account); less frequent places (Reports) sit in the top bar. The bar's height is `--nav-h`; the page is padded by it plus `env(safe-area-inset-bottom)` so the last control can always be reached.
- **Dialogs:** sheets (`.sheet`) rise from the bottom; closed only by their own button or Escape, never by a tap outside (a stray tap must not throw away typing).
- **Layout:** one column; cards in a vertical list; no horizontal page scroll at 320 px and up (check 320, 360, 375, 390, 430, 768). Quantities are shown by a few labelled figures, not a table.

## Shared components

`Icon`, `HandoffCard` (every handoff list), `ConfirmSheet` (`useConfirm`), `ReportAssistantSheet`, `HandoffForm`, and in `ui.tsx`: `Spinner`, `ErrorNotice`, `EmptyState`, `StatusBadge`, `Toast`.
