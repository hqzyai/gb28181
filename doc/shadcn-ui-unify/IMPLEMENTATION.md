# GB28181 console UI profile

This application uses Vue 2.6.10 and Element UI 2.15.14. The team's React-oriented standard is applied as a visual compatibility theme. No React, Radix, shadcn React components, router, store or form-library migration is included. Element UI's DOM, events, validation, popup manager and data-table engine remain the implementation.

Profiles: **shadcn-neutral-v3**, **lucide-outline-v1**, **hqzy-logomark-v2**. Existing light mode remains the default; no theme switcher is added. The four menu entries and hidden-route policy are unchanged.

## Coverage

| Area | Implementation | Behavior preserved |
| --- | --- | --- |
| Console shell and login | Light neutral sidebar, selected indicators, breadcrumb, tags, navbar, login form, visible keyboard focus | Route targets, fixed AI tag, close/scroll behavior, authentication and redirect |
| AI safety analysis | Cards, compact toolbar, empty/loading/error/disabled states, history table, status tags and pagination | Engines, playback, channel selection, requests, history pinning and detection coordinates |
| Device access | GB/JT lists and channels, push/proxy lists, edit forms, dialogs and common tree | Data models, search, selection, pagination, actions and player/PTZ behavior |
| User management | User and API-key tables, nested forms, destructive text actions | Account/API-key actions, draft reset, validation, clipboard events and permissions |
| Operations | Information cards, history/real-time log wrappers and toolbar | WebSocket, filters, downloads, automatic/virtual scrolling and ANSI content |
| Shared controls | Element buttons, inputs, select, table, checkbox, radio, switch, tabs, dialogs, picker, menus, tooltip, messages, loading and progress | Original control props, event payloads, portal placement and popup stacking |

`web/src/styles/tokens.scss` maps the team's exact semantic colors. `unified-ui.scss` consumes these tokens in the existing controls; page-specific styles remove old overriding colors, weights and shapes. Standard controls use 36px minimum height; existing compact toolbars use 32px. Input/button radius is 6px, card/dialog radius 12px, popover/table radius 8px, badge radius 4px. Focus is a 2px ring with 2px offset. Switch display width uses Element's supported 32px prop rather than overriding its inline layout.

## Assets and provenance

- UI font families are Geist and Noto Sans SC, with Geist Mono for application log/code wrappers. Fonts are self-hosted with `font-display: swap`; no runtime Google Fonts request is used.
- Geist/Geist Mono are pinned to `@fontsource-variable/*@5.2.6`. Noto Sans SC uses the full Google Fonts v41 400–600 variable release, including all 101 Unicode-range shards. No page-text-only subset or all-shard preload is used. `web/src/assets/fonts/sources.json` records each immutable file URL/hash. Font licenses are distributed next to the files.
- Lucide uses the official `lucide-static@0.468.0` named static subset, compatible with Vue 2 without an additional framework dependency. `UiIcon` preserves official shapes with 1.75px non-scaling strokes. Element's internal and retained page icon nodes use explicit official SVG masks, 16px glyphs and 1.75px visible strokes, preserving their event nodes. This is a static compatibility adapter, not a DOM scanner or React wrapper. `assets/lucide/sources.json` records all mappings and original SVG hashes; the package's ISC/Feather MIT license is retained.
- The favicon and console entry share `web/public/hqzyai-logomark.svg`, SHA-256 `720d90e26fcd94a1000c5a40cd2dd042c07299f5bae601ba7e3052330e534c52`. The 793:568 ratio and original blue gradient remain intact; display frames are 28px and 24px, with `object-fit: contain`. This user-provided asset has no asserted open-source license.

## Compatibility boundaries

Element controls are themed, not claimed as official shadcn components. Video/playback engines, PTZ diagrams, timelines, maps/charts and log ANSI content keep their specialized graphics and business colors. Unknown external/legacy `SvgIcon` keys retain the original fallback, including filled sprites. The legacy icon font remains for specialized graphics and hidden routes with remaining consumers.

The tree's 26px virtual row measurement, sidebar widths/collapse breakpoint, AI workspace 16px padding and 480/430px rows, existing responsive breakpoint, player/control-panel dimensions, percentage dialog widths/drag geometry, table fixed columns, PopupManager z-index stack and log 20px line height remain local geometry exceptions. Root viewport settings now permit normal browser zoom. Existing narrow-layout limitations are not represented as a mobile product redesign.

## Verification

Production build: `npm run build:prod` in the server's isolated Node 20 build directory, using the locked application dependencies. No framework/library upgrade or new product dependency was required.

- `node tests/aiAnalysis-history-state.cjs`: 10 passed.
- Existing `npm run test:unit -- --runInBand`: 27 passed, 1 pre-existing `validUsername` expectation failed; no added failure after the Hamburger state-class compatibility repair.
- Existing `npm run lint`: same baseline, 7 errors and 705 warnings. These are recorded, not silently reported as a passing lint suite.
- Real Chromium checks cover the nine visible pages, user-form blur feedback, cancel/reset/reopen, nested API-key dialogs, focus/hover, font/resource loading, favicon, Lucide size/stroke and 390/768/1179/1181/1440px viewport widths. No new document horizontal overflow or resource failures appeared relative to baseline. The JT1078 terminal API's pre-existing 404 remains in both runs.
- API/store/router/business scripts and event contracts are unchanged by the visual migration. Private screenshots, reports and credentials stay outside the public source tree.

The current username validator accepts any string, including empty strings, and its legacy unit expectation fails. Existing user creation has no form model and does not run form validation on submit. Those business issues are outside this visual-only migration; UI validation checks do not create accounts or API keys. This report is not a complete WCAG or mobile usability certification.
