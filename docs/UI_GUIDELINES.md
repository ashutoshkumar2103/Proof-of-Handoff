# HandOffly UI guidelines

Short rules for the two React apps (`frontend/`, `support-portal/`). They describe what the code already does; follow them so new screens look and behave like the rest.

> **Do not create a visually different implementation for a UI pattern that already exists. Reuse the existing shared component/style.**
> Before adding a component, class or colour, search `src/components/` and `src/index.css`. Extend the shared one if it almost fits.

## Where things live

| Need | Use |
|---|---|
| A dropdown (top-bar menus, "More") | `components/ActionMenu.tsx`, styled by `.menu`, `.menu-panel`, `.menu-item` |
| A top-bar entry that is a dropdown on wide screens and a list on narrow ones | `NavMenu` in `components/Layout.tsx` (it wraps `ActionMenu`) |
| A dialog | the `.modal-overlay` / `.modal` markup (see `ConfirmDialog`, `ChangePasswordModal`) |
| A message that reports what the user just did | `useTransient` (`components/ui.tsx`): it clears itself after 5 s |
| Status, badges, notices | `StatusBadge`, `.badge-*`, `.notice-*` |
| Colours, radius, shadow | the tokens at the top of `index.css` (`--primary`, `--surface`, `--border`, `--radius`, `--shadow-lg`, …) |

Never write a colour, radius or shadow as a literal when a token exists. Light and dark themes both come from the tokens, so a component that uses only tokens works in both.

## Look

- **Typography:** the page font (`--font`); `h1` 1.6rem, `h2` 1.25rem, body text 1rem, secondary text `.muted`, small print `.small`. Labels in menus and buttons are weight 500–600, never a different family.
- **Radius:** `--radius` (10px) for cards, panels and dialogs; `--radius-sm` (7px) for buttons, inputs, menu items, notices.
- **Shadow:** `--shadow` (small) on cards in a page, with their 1px border; `--shadow-lg` only on things that float above the page (dropdown panels, dialogs).
- **Spacing:** layout gaps are `1rem` (`.stack`), row gaps `0.75rem` (`.row`), card padding as `.card`; menu panels have `0.35rem` padding, `0.4rem` from their button.
- **Hover:** `--surface-2` background. **Focus:** a 2px `--primary` outline (keep it visible). **Disabled:** 55% opacity, `not-allowed`.
- **Active ("this is the page you are on"):** `--primary` text on `--primary-soft`, weight 600. It is one rule in `index.css` shared by the top-bar links, a dropdown's button, the items inside a dropdown and the narrow menu; add a selector to that rule instead of writing a second one. A page-less action (such as Change Password, which is a dialog) is never marked active.

## Buttons

One primary action per area: `.btn-primary`. Everything else is a plain `.btn`. `.btn-ghost` for quiet text actions (Close, Cancel), `.btn-danger` only for destructive actions, `.btn-sm` for dense toolbars. Do not restyle a button locally to make it stand out; pick the right variant. A feature that is locked by plan keeps its button and shows a lock (🔒) with the plan message, rather than disappearing.

## Navigation and dropdowns

- The top bar lists pages; an entry that groups pages or actions is a dropdown (`NavMenu` → `ActionMenu`) with the same items in the narrow menu.
- Items are the existing pages or dialogs; a dropdown decides nothing itself. Keep labels short and the order stable.
- The dropdown opens under its button's left edge; if it would not fit there it lines up with the right edge; it is never outside the screen (`ActionMenu` measures and moves it). Do not position a menu with its own CSS.
- Opens by click or tap, on hover where the device has one, and by the keyboard: ArrowDown opens and moves through the items, Home/End jump, Escape closes and returns focus to the button, tabbing out closes it.
- The item for the page you are on is marked active (`current: true` on the `ActionMenuItem`, compared with the route; `aria-current="page"` comes with it).

## Responsive

- Breakpoints in use: 860px (the top bar becomes a menu button; dropdowns become lists), 760px and 560px (tables and forms reflow).
- Test every change at desktop, tablet (~768px), 390px and 375px, in both themes. No horizontal page scroll; wide tables scroll inside `.table-wrap`.
- Touch targets are at least 44px high on narrow screens. Anything that floats (menus, dialogs) must stay inside the viewport: dialogs use `max-height: calc(100dvh - 2rem)` and scroll inside, menus are placed by `ActionMenu`.
- Do not rely on hover for anything a touch user needs.

## Copy

- A typed name is a "Typed acknowledgement", never a legal e-signature (see `CLAUDE.md`).
- Messages that report a result clear themselves after 5 seconds (`useTransient`); a page's own state (a failed load, a locked feature) stays for as long as it is true.
