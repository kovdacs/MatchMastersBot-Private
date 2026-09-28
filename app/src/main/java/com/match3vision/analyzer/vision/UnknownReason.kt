package com.match3vision.analyzer.vision

/**
 * Per-cell UNKNOWN taxonomy for diagnostics (does not affect PASS/HOLD gates).
 *
 * Categories: `color` | `shape` | `special` | `occlusion` | `geometry/grid`
 * (combinable with `|`).
 */
object UnknownReason {

    fun diagnose(
        color: TileColor,
        shape: TileShape,
        special: SpecialType,
        occluded: Boolean,
        cellW: Int = 8,
        cellH: Int = 8,
        gridMethod: GridMethod? = null,
    ): String {
        val parts = mutableListOf<String>()
        if (occluded) parts += "occlusion"
        if (cellW < 4 || cellH < 4) parts += "geometry/grid"
        if (gridMethod == GridMethod.EVEN_SPLIT) parts += "geometry/grid"
        if (color == TileColor.UNKNOWN) parts += "color"
        if (shape == TileShape.UNKNOWN) parts += "shape"
        // Special sprite without a modeled type still surfaces as color/shape unk;
        // tag `special` when a non-NONE special coexists with unknown color.
        if (special != SpecialType.NONE && color == TileColor.UNKNOWN) parts += "special"
        if (parts.isEmpty()) {
            // Reconciler near-tie / contradiction with both channels known
            parts += "color"
            parts += "shape"
        }
        return parts.distinct().joinToString("|")
    }
}
