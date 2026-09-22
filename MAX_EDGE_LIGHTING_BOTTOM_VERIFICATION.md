# Bottom Edge Verification

## Current Runtime Geometry
- **Physical Display Height**: `2340px` (on target 1080x2340 device)
- **Overlay Window Height (Current)**: `2274px` (constrained by default system WindowInsets and fitInsets behavior on API 30+)
- **Difference (Navigation Bar Height)**: `66px`

## Stroke Geometry Calculation
- **corePaint.strokeWidth**: `6f * density` (~18px at 3x density)
- **glowPaint.strokeWidth**: `20f * density` (~60px at 3x density)
- **edgePadding**: `coreHalf` (~9px)
- **borderRect.bottom**: `h - edgePadding` where `h = 2274px`.
- **strokeOuterBottom**: `borderRect.bottom + (corePaint.strokeWidth / 2f)` -> reaches `2274px`, falling short of the physical display bottom (`2340px`).

## View Bounds
- `EdgeLightingOverlayView.height` equals the overlay window height (`2274px`), because the WindowManager layout is inset by default system window insets (`WindowInsets`) on Android 11+ (`R`+).

## Window Bounds
- The `TYPE_APPLICATION_OVERLAY` window bounds are restricted to the application content area excluding the system navigation bar when window insets are not explicitly ignored via `setFitInsetsTypes(0)` and `setFitInsetsSides(0)`.

## Physical Display Bounds
- Full physical display height is `2340px`. The overlay currently occupies `2274px`.

## Canvas Clip Bounds
- `canvas.clipBounds` matches the View bounds (`0, 0, 1080, 2274`). There is no internal canvas clipping, but the View container itself is truncated at `2274px`.

## Navigation/System UI
- The bottom system navigation bar / gesture bar occupies the bottom `66px` (from `2274px` to `2340px`).
- Because the overlay window stops at `2274px`, the edge lighting does not extend behind the navigation bar.

## Confirmed Root Cause
- WindowManager overlay layout insets on Android 11+ restrict window bounds to exclude system navigation bar insets, yielding `2274px` instead of `2340px`.
- **Root Cause Category**: `ROOT_CAUSE_WINDOW_BOUNDS` / `ROOT_CAUSE_INSETS`

## Minimal Correct Fix
- **File**: `app/src/main/java/com/example/ui/overlay/MaxVisualFeedbackManager.kt`
- **Line**: ~90-110
- **Change**: Configure `edgeParams` with `setFitInsetsTypes(0)` and `setFitInsetsSides(0)` (API 30+) and `layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (API 28+).
- **Reason**: Disables system window inset constraints on the overlay window, expanding its height from `2274px` to full physical display height (`2340px`) so that Edge Lighting automatically reaches the bottom display boundary.

VERIFICATION COMPLETE
STROKE_ALREADY_REACHES_VIEW_BOTTOM: YES
VIEW_REACHES_PHYSICAL_BOTTOM: NO
CANVAS_CLIPPING: NO
SYSTEM_UI_COVERING_BOTTOM: YES
WINDOW_BOUNDS_PROBLEM: YES
DRAWING_PROBLEM: NO
CODE MODIFIED: NO
