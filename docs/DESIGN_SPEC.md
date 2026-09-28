# Ember — Complete Design Spec (Reference for Suya Phot Visual System)

Dark crypto-wallet UI adapted as visual system for **Suya Phot**. Font **Sora**. Palette **#131314 / #222222 / #e55f11 / #ffffff**.

---

## 1. Source of truth and confidence

| Item | Source | Confidence |
|---|---|---|
| Font = Sora | Explicit in reference sheet | Exact |
| 4 colors: #131314, #222222, #e55f11, #ffffff | Explicit in reference sheet | Exact |
| Surfaces, radii, gradients, opacities, spacing | Measured and implemented | Consistent |
| Green `#3fd05e`, red `#ff6b5a` | Green sampled; red semantic | Exact |

---

## 2. Design tokens

### 2.1 Color tokens

```
--bg:          #131314   /* App background, darkest */
--surface:     #222222   /* Raised: inputs, chips, cards, dialogs, sheets */
--accent:      #e55f11   /* Brand accent: active nav, buttons, selection, toggles */
--white:       #ffffff

--text:        #ffffff
--text-muted:  rgba(255, 255, 255, 0.50)
--text-faint:  rgba(255, 255, 255, 0.35)
--text-soft:   #dddddd

--line:        rgba(255, 255, 255, 0.08)  /* 1px hairlines, dividers */
--fill-05:     rgba(255, 255, 255, 0.05)  /* Sub-panels, keypad panel */
--fill-06:     rgba(255, 255, 255, 0.06)  /* Card backgrounds */
--fill-07:     rgba(255, 255, 255, 0.07)  /* Icon buttons, bottom bar */
--fill-20:     rgba(255, 255, 255, 0.20)  /* Badges, overlays */

--positive:    #3fd05e
--negative:    #ff6b5a

--toggle-off:  #3a3a3a
--stroke-mid:  #555555
--stroke-low:  #444444

--screen-bg:   radial-gradient(120% 55% at 15% 0, #272b22, #131314 62%)
```

**Rule of the accent:** `#e55f11` appears ONLY on active bottom nav indicator, primary buttons, checked selections/toggles, focus rings, progress indicators, and active filters. Never for body text.

---

## 3. Typography (Sora)

- Display / Numbers: 36sp, 400 weight (PIN dots / hero balance / stats)
- Title: 20sp–22sp, 500 weight (Screen titles, folder titles)
- Subheading: 14sp–15sp, 500 weight
- Body / List: 14sp, 400 weight
- Caption / Metadata: 11sp–12sp, 400 weight, muted (rgba(255,255,255,0.50))
- Keypad Digits: 22sp, 300 weight
- Badges: 12sp, 500 weight

---

## 4. Spacing, Radii, Borders, Shadows

- **Spacing:** 2dp, 4dp, 6dp, 8dp, 10dp, 12dp, 14dp, 16dp, 18dp, 22dp.
- **Screen horizontal padding:** 18dp.
- **Radii:**
  - Chip: 10dp
  - Input field / Tab item: 16dp
  - Sub-panel / Sheet: 18dp
  - Card / Dialog: 20dp
  - Keypad panel / Pill: 24dp
  - Button / Bottom nav pill: 30dp
  - Circle: 50%
- **Borders:** 1dp solid `rgba(255,255,255,0.08)`. Focus border `1dp #e55f11`.
- **Shadows:** Flat design with subtle elevations, no heavy drop shadows.

---

## 5. Motion Tokens

- Press feedback: 100ms
- Small state change: 160ms
- Navigation / tab change: 220ms
- Modal / Sheet entrance: 260ms
- Media viewer open/close: 300ms
- Lock shake / error: 250ms
- Easing: FastOutSlowIn / Cubic(0.2, 0.0, 0.0, 1.0)
