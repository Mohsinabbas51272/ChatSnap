---
name: Luminous Minimalist
colors:
  surface: '#f9f9ff'
  surface-dim: '#d8d9e3'
  surface-bright: '#f9f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f2f3fd'
  surface-container: '#ecedf7'
  surface-container-high: '#e6e7f2'
  surface-container-highest: '#e1e2ec'
  on-surface: '#191b23'
  on-surface-variant: '#424754'
  inverse-surface: '#2e3038'
  inverse-on-surface: '#eff0fa'
  outline: '#727785'
  outline-variant: '#c2c6d6'
  surface-tint: '#005ac2'
  primary: '#0058be'
  on-primary: '#ffffff'
  primary-container: '#2170e4'
  on-primary-container: '#fefcff'
  inverse-primary: '#adc6ff'
  secondary: '#5c5f61'
  on-secondary: '#ffffff'
  secondary-container: '#e0e3e5'
  on-secondary-container: '#626567'
  tertiary: '#924700'
  on-tertiary: '#ffffff'
  tertiary-container: '#b75b00'
  on-tertiary-container: '#fffbff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#d8e2ff'
  primary-fixed-dim: '#adc6ff'
  on-primary-fixed: '#001a42'
  on-primary-fixed-variant: '#004395'
  secondary-fixed: '#e0e3e5'
  secondary-fixed-dim: '#c4c7c9'
  on-secondary-fixed: '#191c1e'
  on-secondary-fixed-variant: '#444749'
  tertiary-fixed: '#ffdcc6'
  tertiary-fixed-dim: '#ffb786'
  on-tertiary-fixed: '#311400'
  on-tertiary-fixed-variant: '#723600'
  background: '#f9f9ff'
  on-background: '#191b23'
  surface-variant: '#e1e2ec'
typography:
  h1:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: -0.02em
  h2:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '700'
    lineHeight: '1.3'
    letterSpacing: -0.01em
  h3:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: '1.4'
    letterSpacing: -0.01em
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.6'
    letterSpacing: 0em
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: '1.5'
    letterSpacing: 0em
  label-bold:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: '1.4'
    letterSpacing: 0.02em
  label-caps:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  margin-edge: 20px
  gutter: 12px
---

## Brand & Style

This design system is engineered for an elite social communication experience. The brand personality is "Futuristic Serenity"—combining the high-speed energy of real-time messaging with a calm, uncluttered visual environment. It targets a digitally native audience that values efficiency and high-end aesthetics.

The visual style is a refined evolution of **Glassmorphism**, specifically optimized for a light mode environment. It utilizes multi-layered translucency to create a sense of physical depth without visual noise. Every interaction is designed to feel fluid and tactile, leveraging micro-interactions that provide immediate, soft feedback to user inputs.

## Colors

The palette centers on a pristine white base, using varying shades of light blue-grays to define structural boundaries. 

- **Primary Blue** is reserved for high-priority actions and active states, signaling intelligence and reliability. 
- **The Accent Pink/Red** is used strategically for ephemeral content (Stories) and urgent notifications, creating a vibrant "heat map" of activity against the cool background.
- **Glass Surfaces** utilize a 70% opacity white with a heavy backdrop blur (20px+) to maintain readability while appearing lightweight.

## Typography

This design system utilizes **Inter** for its mathematical precision and exceptional legibility at small sizes. 

- **Headlines** are aggressive and bold, using tight letter-spacing to create a compact, "editorial" look.
- **Body text** prioritizes breathing room with a generous line-height of 1.6x, ensuring long chat threads remain easy to scan.
- **Labels** often use uppercase and increased tracking to provide clear metadata separation without requiring heavy borders.

## Layout & Spacing

The layout follows a **dynamic, high-density grid** designed for mobile-first consumption. 

- **Margins:** A consistent 20px edge margin creates a frame that feels premium and intentional.
- **Density:** Content modules (chat bubbles, contact cards) use tight internal padding (12px) to maximize the amount of information visible on-screen, while external margins (16px) prevent the UI from feeling cluttered.
- **Safe Areas:** Navigation bars and action buttons are anchored with significant bottom-safe-area padding to ensure ergonomic accessibility on modern tall displays.

## Elevation & Depth

Depth in this design system is achieved through **optical layering** rather than traditional "stacking."

- **Glass Layers:** Floating elements (like navigation bars and pop-overs) use `backdrop-filter: blur(24px)` combined with a 1px solid white stroke at 20% opacity. This simulates a "beveled glass" edge.
- **Shadows:** We use "Ambient Softness"—shadows that are extremely diffused (Blur: 30px+) and low opacity (5-8%). The shadow color is tinted with the primary blue (`rgba(59, 130, 246, 0.08)`) to maintain a vibrant, light feel.
- **Interaction:** Upon press, elements should visually "sink" (scale 0.96) and their shadow spread should decrease, simulating physical pressure.

## Shapes

The shape language is defined by the **24px ultra-round corner**. 

- **Main Cards & Containers:** Follow the standard 24px (1.5rem) radius.
- **Buttons & Small Inputs:** Scale down to 16px (1rem) for a cohesive but distinct look.
- **Interactive States:** High-frequency interaction points like "Add Story" or "Camera" utilize perfect circles (pill-shaped) to distinguish them from content containers.
- **Strokes:** Use ultra-thin (1px) borders in a light gray-blue to provide definition on white-on-white layouts.

## Components

- **Glass Buttons:** Primary buttons use a solid #3B82F6 fill, but secondary buttons use the glass effect with a subtle blue text to maintain the futuristic aesthetic.
- **Chat Bubbles:** Inbound messages are Secondary Color (#F8FAFC) with 24px rounded corners (bottom-left at 4px). Outbound messages are Primary Blue with 24px rounded corners (bottom-right at 4px).
- **Story Rings:** Use a 2px gradient stroke using the Accent Color (#F43F5E) with a 2px gap between the ring and the user avatar.
- **Input Fields:** Search and Message inputs are secondary-colored containers (#F8FAFC) with no borders, relying on subtle depth for definition.
- **Floating Action Button (FAB):** A signature element using the Glassmorphism style with a vibrant blur, housing the "New Chat" icon.
- **Status Indicators:** Micro-dots (8px) using Success Green for online status, placed at the bottom-right of avatars with a 2px white "knockout" border.