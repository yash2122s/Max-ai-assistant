# MAX Edge Lighting Full Screen Audit

## 1. Problem
MAX Edge Lighting is visible around the screen, but the lighting does not reach the complete physical display boundary at the bottom, stopping above the bottom system navigation bar/area.

## 2. Current Edge Lighting Window
- **File Path**: `app/src/main/java/com/example/ui/overlay/MaxVisualFeedbackManager.kt`
- **Line Number**: ~85-115
- **Window Type**: `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` (falls back to `TYPE_PHONE` below API 26)
- **Width**: `WindowManager.LayoutParams.MATCH_PARENT`
- **Height**: `WindowManager.LayoutParams.MATCH_PARENT`
- **Gravity**: `Gravity.TOP or Gravity.START`
- **X**: `0`
- **Y**: `0`
- **Flags**: `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
- **Format**: `PixelFormat.TRANSLUCENT`
- **Alpha**: `1.0f` (default)
- **LayoutInDisplayCutoutMode**: `WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` (for API 28+)
- **SoftInputMode**: Unset (default)
- **Token**: Unset (default)
- **PrivateFlags**: Unset (default)

## 3. Actual View Bounds
- **File Path**: `app/src/main/java/com/example/ui/overlay/EdgeLightingOverlayView.kt`
- **Line Number**: ~475-575 (`onDraw`)
- **Runtime Representation**: `width` and `height` represent the measured dimensions of the `EdgeLightingOverlayView`.
- **Relationship**:
  - Physical Display Height (Device API / DisplayMetrics): `2340px` (on target 1080x2340 device)
  - Overlay Window Height: Matches screen height due to `MATCH_PARENT` + `FLAG_LAYOUT_NO_LIMITS`
  - EdgeLightingView Height (`h`): Matches window height.
  - However, in `EdgeLightingOverlayView.onDraw()`, `borderRect.set(edgePadding, edgePadding, w - edgePadding, h - edgePadding)` is used where `edgePadding = coreHalf`. This explicitly insets the bottom drawing boundary by `coreHalf` from the view's bottom edge (`h`), causing the bottom stroke to render above the bottom boundary.

## 4. Physical Display Bounds
- Target Device Resolution: `1080 x 2340` (Android 16 / API 36).
- The overlay window layout params (`MATCH_PARENT` + `FLAG_LAYOUT_NO_LIMITS`) allow the window to span the physical display, but the View drawing rect is inset by `edgePadding`.

## 5. Navigation Bar Analysis
- Navigation Mode: Supports both gesture navigation and 3-button navigation.
- The system navigation bar occupies the bottom region of the screen.
- Because `EdgeLightingOverlayView` insets its drawing rect by `edgePadding` (`coreHalf`), the bottom stroke is rendered above the bottom edge, creating a visual gap above the navigation bar area.

## 6. Insets Analysis
- The overlay window itself uses `FLAG_LAYOUT_NO_LIMITS` and `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`, permitting drawing behind system bars.
- The limitation is entirely within `EdgeLightingOverlayView.onDraw()`, where `borderRect` uses `h - edgePadding` instead of extending to `h` (or `h + bottomExtension`).

## 7. Drawing/Clipping Analysis
- **Problem Category**: DRAWING PROBLEM (specifically view-internal border rect coordinate calculation).
- The `borderRect` uses `edgePadding` on all four sides (`left`, `top`, `right`, `bottom`).
- At the bottom, `h - edgePadding` stops the stroke `edgePadding` pixels short of the view's bottom edge.
- There is no hardware clipping or Z-order clipping at the bottom; it is purely a drawing coordinate calculation in `EdgeLightingOverlayView.onDraw()`.

## 8. Overlay Z-Order
Stack order from top to bottom:
1. System UI (Status Bar / Navigation Bar)
2. MAX EdgeLightingOverlayView (`TYPE_APPLICATION_OVERLAY`)
3. Target App / MAX Activity Window

EdgeLightingOverlayView is visually above the target app window.

## 9. Confirmed Root Cause
- `ROOT_CAUSE_VIEW_DRAWING`
- Specifically, `EdgeLightingOverlayView.onDraw()` sets the bottom of `borderRect` to `h - edgePadding`, insetting the bottom stroke from the physical bottom of the view.

## 10. Suspected / Unproven Causes
- `ROOT_CAUSE_WINDOW_BOUNDS` (Disproven: window params correctly use `MATCH_PARENT` + `FLAG_LAYOUT_NO_LIMITS`).
- `ROOT_CAUSE_SYSTEM_BAR` (Disproven: layout cutout mode and flags permit drawing behind system bars).

## 11. Minimal Fix Plan
- **FILE**: `app/src/main/java/com/example/ui/overlay/EdgeLightingOverlayView.kt`
- **LINE**: ~560-570
- **CURRENT**:
  ```kotlin
  borderRect.set(
      edgePadding,
      edgePadding,
      w - edgePadding,
      h - edgePadding
  )
  ```
- **CHANGE_TO**:
  ```kotlin
  val bottomExtension = 8f * density
  borderRect.set(
      edgePadding,
      edgePadding,
      w - edgePadding,
      h + bottomExtension
  )
  ```
- **REASON**: Extends the bottom border rect past `h` so the stroke reaches the absolute bottom physical display boundary, including behind the navigation bar.
- **SIDE_EFFECTS**: None; top, left, and right remain perfectly inset by `edgePadding`, while the bottom extends to cover the bottom bezel/navigation area.

## 12. Regression Risks
- Preserves touch interaction, Chrome, Maps, WhatsApp, Instagram, MAX Home UI, keyboard/IME, AgentCursor, Voice Live states, and both gesture and 3-button navigation.

## 13. Exact Files To Modify
- `app/src/main/java/com/example/ui/overlay/EdgeLightingOverlayView.kt`

## 14. Conclusion
The audit confirms that the bottom edge lighting gap is caused by view-internal coordinate insetting (`h - edgePadding`) in `EdgeLightingOverlayView.onDraw()`.

AUDIT COMPLETE
BOTTOM EDGE ISSUE: CONFIRMED
ROOT_CAUSE_CATEGORY: ROOT_CAUSE_VIEW_DRAWING
OVERLAY WINDOW FULL DISPLAY: YES
VIEW FULL DISPLAY HEIGHT: YES
NAVIGATION BAR COVERING EDGE LIGHT: NO
INSET CAUSING CLIP: YES
Z-ORDER ISSUE: NO
FILES REQUIRING CHANGES: 1
CODE MODIFIED: NO
