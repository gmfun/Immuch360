package app.alextran.immich.immersive

import com.meta.spatial.runtime.StereoMode
import kotlin.math.roundToInt

/**
 * How a flat 3D media packs its eyes, as Flutter names it in the flatFormat of ImmersiveApi.open (see
 * guessFlatStereoFormat on the Flutter side): side by side or top and bottom, with eyes at the aspect of their half of
 * the frame or squeezed into it, or a photo with its color on the left and its depth on the right (RGBD). A regular
 * photo or video is [MONO]: the whole frame for both eyes, on the same screen, for a view larger than the 2D window.
 */
internal enum class FlatFormat(val wire: String) {
  SBS("sbs"),
  HALF_SBS("halfSbs"),
  TB("tb"),
  HALF_TB("halfTb"),
  RGBD("rgbd"),
  MONO("mono");

  companion object {
    /** The format Flutter sent as [wire], null for none or for one this viewer does not know (a newer Flutter side). */
    fun parse(wire: String?): FlatFormat? = entries.firstOrNull { it.wire == wire }
  }
}

/**
 * Flat 3D media on a screen in front of the user (the stereo quad for a photo, a flat video layer for a video): where
 * each eye lies in the frame, the shape it is shown with, and for an RGBD photo both eyes made from its depth. Pure
 * functions, unit tested.
 */
internal object FlatStereo {
  /** A rectangle of a frame, in pixels. */
  data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

  /**
   * Total disparity of an RGBD photo, from the farthest to the nearest point, as a fraction of the eye width: 3%, about
   * what GAV Stereo Studio exports by default, comfortable on a screen 2 m away.
   */
  const val RGBD_DISPARITY = 0.03f

  /**
   * The depth strengths the Depth − and + buttons of an RGBD photo step through, as the total disparity of
   * [RGBD_DISPARITY]: from off (both eyes the color) to [RGBD_MAX_DISPARITY], [RGBD_DISPARITY_STEP] at a time. Past
   * GAV Stereo Studio's own 8%, for trying: most people no longer fuse the eyes somewhere above it.
   */
  const val RGBD_MAX_DISPARITY = 0.12f
  const val RGBD_DISPARITY_STEP = 0.005f

  /** What the Plane − and + buttons change the screen plane by, within 0 (far) and 1 (near). */
  const val RGBD_PLANE_STEP = 0.1f

  /** The strength [step] steps of [RGBD_DISPARITY_STEP] away from [current], on the grid of the steps, in bounds. */
  fun stepStrength(current: Float, step: Int): Float =
    (((current / RGBD_DISPARITY_STEP).roundToInt() + step) * RGBD_DISPARITY_STEP).coerceIn(0f, RGBD_MAX_DISPARITY)

  /** The screen plane [step] steps of [RGBD_PLANE_STEP] away from [current], on the grid of the steps, in 0 to 1. */
  fun stepPlane(current: Float, step: Int): Float =
    (((current / RGBD_PLANE_STEP).roundToInt() + step) * RGBD_PLANE_STEP).coerceIn(0f, 1f)

  /** [strength] as the Depth label shows it, in percent of the width with one decimal: "3.5%", "12%". */
  fun strengthPercent(strength: Float): String {
    val tenths = (strength * 1000).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
  }

  /** Whether a media packed as [format] has an eye of its own for each eye: not an RGBD photo, nor a 2D media. */
  fun hasSecondEye(format: FlatFormat): Boolean = format != FlatFormat.RGBD && format != FlatFormat.MONO

  /** Depth of an RGBD photo that stays on the screen plane, 0 far to 1 near: what is nearer comes out of it. */
  const val RGBD_SCREEN_PLANE = 0.5f

  /**
   * The left and the right eye of a [width] x [height] frame packed as [format]; for an RGBD photo its color and its
   * depth, for a [FlatFormat.MONO] media the whole frame twice. An odd side leaves its last column or row out.
   */
  fun eyes(format: FlatFormat, width: Int, height: Int): Pair<Region, Region> =
    when (format) {
      FlatFormat.SBS,
      FlatFormat.HALF_SBS,
      FlatFormat.RGBD -> {
        val half = width / 2
        Region(0, 0, half, height) to Region(half, 0, half, height)
      }
      FlatFormat.TB,
      FlatFormat.HALF_TB -> {
        val half = height / 2
        Region(0, 0, width, half) to Region(0, half, width, half)
      }
      FlatFormat.MONO -> Region(0, 0, width, height).let { it to it }
    }

  /**
   * Width over height of one eye as the user sees it, for a [width] x [height] frame: that of its half of the frame,
   * twice as wide for squeezed eyes side by side, twice as tall for squeezed eyes stacked (both then show with the
   * aspect of the whole frame). 16:9 for an unknown size.
   */
  fun eyeAspect(format: FlatFormat, width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 16f / 9f
    val frame = width.toFloat() / height
    return when (format) {
      FlatFormat.SBS,
      FlatFormat.RGBD -> frame / 2f
      FlatFormat.TB -> frame * 2f
      FlatFormat.HALF_SBS,
      FlatFormat.HALF_TB,
      FlatFormat.MONO -> frame
    }
  }

  /**
   * The size of the image of one eye on the texture, in pixels: the height of its [region] (its width for eyes squeezed
   * side by side) at the shape of [eyeAspect], within [maxWidth] x [maxHeight].
   */
  fun eyeSize(format: FlatFormat, region: Region, aspect: Float, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    var height = region.height
    var width = (height * aspect).roundToInt()
    if (width < region.width) {
      // Stacked squeezed eyes: the full width is the detail to keep, the height grows instead
      width = region.width
      height = (width / aspect).roundToInt()
    }
    return StereoComposer.fitWithin(width.coerceAtLeast(1), height.coerceAtLeast(1), maxWidth, maxHeight)
  }

  /**
   * The stereo mode of the flat video layer: each eye its half of the frame in 3D, the left (or top) eye for both in
   * 2D. An RGBD frame has no second eye: its color half for both; a [FlatFormat.MONO] one the whole frame for both.
   */
  fun stereoModeFor(format: FlatFormat, threeD: Boolean): StereoMode =
    when (format) {
      FlatFormat.SBS,
      FlatFormat.HALF_SBS -> if (threeD) StereoMode.LeftRight else StereoMode.MonoLeft
      FlatFormat.TB,
      FlatFormat.HALF_TB -> if (threeD) StereoMode.UpDown else StereoMode.MonoUp
      FlatFormat.RGBD -> StereoMode.MonoLeft
      FlatFormat.MONO -> StereoMode.None
    }

  /**
   * Both eyes of an RGBD photo, from its [color] and its [depth] (ARGB pixels, [width] x [height] each, the depth in
   * grey, white near), by depth image based rendering: every pixel moves by its disparity, [maxDisparityPx] times its
   * depth less [plane], half to each eye in opposite directions, so that what is nearer than [plane] comes out of the
   * screen (it moves right in the left eye, left in the right eye). Where two pixels land on the same place the nearer
   * one wins; the holes this leaves beside the edges of near objects take the farther of their two neighbours, the
   * background they uncover.
   */
  fun synthesizeEyes(
    color: IntArray,
    depth: IntArray,
    width: Int,
    height: Int,
    maxDisparityPx: Float,
    plane: Float = RGBD_SCREEN_PLANE,
  ): Pair<IntArray, IntArray> {
    require(width > 0 && height > 0 && color.size == width * height && depth.size == width * height) {
      "${color.size} color and ${depth.size} depth pixels for $width x $height"
    }
    val left = IntArray(width * height)
    val right = IntArray(width * height)
    val near = FloatArray(width)
    val shift = FloatArray(width)
    val rowDepth = FloatArray(width)
    for (y in 0 until height) {
      val row = y * width
      for (x in 0 until width) {
        val d = (depth[row + x] and 0xFF) / 255f
        rowDepth[x] = d
        shift[x] = maxDisparityPx * (d - plane) / 2f
      }
      warpRow(color, row, width, rowDepth, shift, 1f, left, near)
      warpRow(color, row, width, rowDepth, shift, -1f, right, near)
    }
    return left to right
  }

  /**
   * One row of one eye: the pixels of [color] from [row] moved by [direction] times their [shift], the nearest winning
   * ([near] holds the depth written at each place, -1 for none yet), then the holes filled from their farther side.
   */
  private fun warpRow(
    color: IntArray,
    row: Int,
    width: Int,
    rowDepth: FloatArray,
    shift: FloatArray,
    direction: Float,
    out: IntArray,
    near: FloatArray,
  ) {
    near.fill(-1f)
    for (x in 0 until width) {
      val target = (x + direction * shift[x]).roundToInt()
      if (target < 0 || target >= width) continue
      val d = rowDepth[x]
      if (d > near[target]) {
        near[target] = d
        out[row + target] = color[row + x]
      }
    }
    var x = 0
    while (x < width) {
      if (near[x] >= 0f) {
        x++
        continue
      }
      val start = x
      while (x < width && near[x] < 0f) x++
      // The hole is start until x: its neighbours are start - 1 and x
      val before = start - 1
      val after = x
      val fill =
        when {
          before < 0 && after >= width -> 0xFF000000.toInt()
          before < 0 -> out[row + after]
          after >= width -> out[row + before]
          near[before] <= near[after] -> out[row + before]
          else -> out[row + after]
        }
      for (i in start until x) out[row + i] = fill
    }
  }
}
