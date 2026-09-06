package com.yighy.pantograph.drawing

import com.yighy.pantograph.data.CustomBrushEntity

/**
 * The presets a fresh install starts with.
 *
 * Nine families rather than a long list of near-neighbours: each one is here because it is the
 * only one that shows what a particular part of the engine does. Anyone can make a variant of
 * a brush they already have; nobody discovers that spacing above 100% turns a stroke into a
 * trail of dots, or that a squashed tip is what makes rotation mean anything, by moving sliders
 * at random.
 *
 * No tips or textures: those are uris into the user's own files, and a preset shipped with the
 * app would have nothing to point at. Everything here is the built-in stamp, shaped.
 *
 * The numbers are a starting point, not a result - they were reasoned from what each parameter
 * does rather than arrived at by drawing with them.
 */
object DefaultBrushes {

    const val FOLDER_NAME = "Defaults"

    /** Black; a preset carries a colour column but the app never restores it. */
    private const val INK = 0xFF000000.toInt()

    fun entities(folderId: Long): List<CustomBrushEntity> = listOf(
        // The workhorse: nothing clever, and the one most drawing starts from.
        CustomBrushEntity(
            name = "Liner", folderId = folderId, colorArgb = INK,
            size = 6f, softness = 0f, opacity = 1f, flow = 1f,
            spacing = 0.05f, smoothing = 0.8f, rotation = 0f, sizeJitter = 0f
        ),

        // Velocity size: thin when the cursor is moving, thick when it slows into a corner.
        CustomBrushEntity(
            name = "Ink", folderId = folderId, colorArgb = INK,
            size = 14f, softness = 0.05f, opacity = 1f, flow = 1f,
            spacing = 0.05f, smoothing = 0.7f, rotation = 0f, sizeJitter = 0f,
            velocityEnabled = true, velocitySize = 0.8f
        ),

        // The ratio, which is the whole reason rotation is worth having on a built-in tip.
        CustomBrushEntity(
            name = "Nib", folderId = folderId, colorArgb = INK,
            size = 26f, softness = 0f, opacity = 1f, flow = 1f,
            spacing = 0.04f, smoothing = 0.6f, rotation = 45f, sizeJitter = 0f,
            tipRatio = 0.22f
        ),

        // Flow below 1 with the shape jitters: builds up where you go over it again.
        CustomBrushEntity(
            name = "Pencil", folderId = folderId, colorArgb = INK,
            size = 4f, softness = 0.1f, opacity = 0.9f, flow = 0.5f,
            spacing = 0.12f, smoothing = 0.35f, rotation = 0f,
            sizeJitter = 0.25f, scatterJitter = 0.15f, flowJitter = 0.3f,
            valueJitter = 0.08f
        ),

        // Colour jitter doing what it is for: the same colour, roughened until it reads as
        // material rather than as paint.
        CustomBrushEntity(
            name = "Chalk", folderId = folderId, colorArgb = INK,
            size = 24f, softness = 0.25f, opacity = 1f, flow = 0.7f,
            spacing = 0.14f, smoothing = 0.3f, rotation = 0f,
            rotationJitter = 180f, sizeJitter = 0.3f, scatterJitter = 0.25f, flowJitter = 0.35f,
            hueJitter = 0.03f, saturationJitter = 0.1f, valueJitter = 0.12f,
            tipShape = "Square", tipRatio = 0.8f
        ),

        // Opacity below 1 with full flow: flat colour that only darkens where strokes cross.
        CustomBrushEntity(
            name = "Marker", folderId = folderId, colorArgb = INK,
            size = 30f, softness = 0f, opacity = 0.65f, flow = 1f,
            spacing = 0.04f, smoothing = 0.6f, rotation = 30f, sizeJitter = 0f,
            tipShape = "Square", tipRatio = 0.55f
        ),

        // Softness and a very low flow: nothing lands in one pass, everything builds.
        CustomBrushEntity(
            name = "Airbrush", folderId = folderId, colorArgb = INK,
            size = 70f, softness = 0.95f, opacity = 1f, flow = 0.1f,
            spacing = 0.03f, smoothing = 0.5f, rotation = 0f, sizeJitter = 0f
        ),

        // Spacing above 100%, which is the only setting that stops a stroke being a line.
        CustomBrushEntity(
            name = "Stipple", folderId = folderId, colorArgb = INK,
            size = 8f, softness = 0.2f, opacity = 1f, flow = 0.9f,
            spacing = 1.8f, smoothing = 0.4f, rotation = 0f,
            sizeJitter = 0.5f, scatterJitter = 0.7f, flowJitter = 0.3f
        ),

        // Paints with the layer instead of a colour. Here mostly so the setting is found at
        // all: nobody goes looking in a jitter panel for a tool that smears.
        CustomBrushEntity(
            name = "Smudge", folderId = folderId, colorArgb = INK,
            size = 30f, softness = 0.5f, opacity = 1f, flow = 1f,
            spacing = 0.05f, smoothing = 0.5f, rotation = 0f, sizeJitter = 0f,
            smudge = 0.9f, smudgeLength = 0.6f
        )
    )
}
