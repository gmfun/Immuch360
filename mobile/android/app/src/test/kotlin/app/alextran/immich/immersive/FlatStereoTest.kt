package app.alextran.immich.immersive

import com.meta.spatial.runtime.StereoMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flat 3D media: the eyes of a frame, their shape, and the eyes of an RGBD photo. */
class FlatStereoTest {
  private val background = 0xFF102030.toInt()
  private val foreground = 0xFFF0E0D0.toInt()
  private val far = 0xFF000000.toInt()
  private val near = 0xFFFFFFFF.toInt()

  @Test
  fun `the formats Flutter sends are read, an unknown one is none`() {
    assertEquals(FlatFormat.SBS, FlatFormat.parse("sbs"))
    assertEquals(FlatFormat.HALF_SBS, FlatFormat.parse("halfSbs"))
    assertEquals(FlatFormat.TB, FlatFormat.parse("tb"))
    assertEquals(FlatFormat.HALF_TB, FlatFormat.parse("halfTb"))
    assertEquals(FlatFormat.RGBD, FlatFormat.parse("rgbd"))
    assertEquals(FlatFormat.MONO, FlatFormat.parse("mono"))
    assertNull(FlatFormat.parse(null))
    assertNull(FlatFormat.parse("mvhevc"))
  }

  @Test
  fun `side by side frames split in two columns, stacked ones in two rows`() {
    val sbs = FlatStereo.eyes(FlatFormat.SBS, 3841, 1080)
    assertEquals(FlatStereo.Region(0, 0, 1920, 1080), sbs.first)
    assertEquals(FlatStereo.Region(1920, 0, 1920, 1080), sbs.second)
    val rgbd = FlatStereo.eyes(FlatFormat.RGBD, 4000, 3000)
    assertEquals(FlatStereo.Region(2000, 0, 2000, 3000), rgbd.second)
    val tb = FlatStereo.eyes(FlatFormat.HALF_TB, 1920, 1080)
    assertEquals(FlatStereo.Region(0, 0, 1920, 540), tb.first)
    assertEquals(FlatStereo.Region(0, 540, 1920, 540), tb.second)
  }

  @Test
  fun `a 2D media shows its whole frame to both eyes`() {
    val (left, right) = FlatStereo.eyes(FlatFormat.MONO, 4000, 3000)
    assertEquals(FlatStereo.Region(0, 0, 4000, 3000), left)
    assertEquals(left, right)
    assertEquals(4f / 3f, FlatStereo.eyeAspect(FlatFormat.MONO, 4000, 3000), 1e-4f)
    assertEquals(StereoMode.None, FlatStereo.stereoModeFor(FlatFormat.MONO, threeD = true))
    assertFalse(FlatStereo.hasSecondEye(FlatFormat.MONO))
    assertFalse(FlatStereo.hasSecondEye(FlatFormat.RGBD))
    assertTrue(FlatStereo.hasSecondEye(FlatFormat.SBS))
  }

  @Test
  fun `squeezed eyes show with the shape of the whole frame`() {
    assertEquals(16f / 9f, FlatStereo.eyeAspect(FlatFormat.SBS, 3840, 1080), 1e-4f)
    assertEquals(16f / 9f, FlatStereo.eyeAspect(FlatFormat.HALF_SBS, 1920, 1080), 1e-4f)
    assertEquals(16f / 9f, FlatStereo.eyeAspect(FlatFormat.TB, 1920, 2160), 1e-4f)
    assertEquals(16f / 9f, FlatStereo.eyeAspect(FlatFormat.HALF_TB, 1920, 1080), 1e-4f)
    assertEquals(4f / 3f, FlatStereo.eyeAspect(FlatFormat.RGBD, 8000, 3000), 1e-4f)
    assertEquals(16f / 9f, FlatStereo.eyeAspect(FlatFormat.SBS, 0, 0), 1e-4f)
  }

  @Test
  fun `squeezed eyes are stretched back on the texture, within its limits`() {
    val halfSbs = FlatStereo.eyes(FlatFormat.HALF_SBS, 1920, 1080).first
    assertEquals(1920 to 1080, FlatStereo.eyeSize(FlatFormat.HALF_SBS, halfSbs, 16f / 9f, 2560, 4088))
    val halfTb = FlatStereo.eyes(FlatFormat.HALF_TB, 1920, 1080).first
    assertEquals(1920 to 1080, FlatStereo.eyeSize(FlatFormat.HALF_TB, halfTb, 16f / 9f, 2560, 4088))
    val large = FlatStereo.eyes(FlatFormat.SBS, 7680, 2160).first
    assertEquals(2560 to 1440, FlatStereo.eyeSize(FlatFormat.SBS, large, 16f / 9f, 2560, 4088))
  }

  @Test
  fun `the video layer shows each eye its half in 3D, the first eye in 2D`() {
    assertEquals(StereoMode.LeftRight, FlatStereo.stereoModeFor(FlatFormat.HALF_SBS, threeD = true))
    assertEquals(StereoMode.MonoLeft, FlatStereo.stereoModeFor(FlatFormat.SBS, threeD = false))
    assertEquals(StereoMode.UpDown, FlatStereo.stereoModeFor(FlatFormat.TB, threeD = true))
    assertEquals(StereoMode.MonoUp, FlatStereo.stereoModeFor(FlatFormat.HALF_TB, threeD = false))
    assertEquals(StereoMode.MonoLeft, FlatStereo.stereoModeFor(FlatFormat.RGBD, threeD = true))
  }

  @Test
  fun `the Depth buttons step by half a percent, from off to 12 percent`() {
    assertEquals(0.035f, FlatStereo.stepStrength(0.03f, 1), 1e-6f)
    assertEquals(0.025f, FlatStereo.stepStrength(0.03f, -1), 1e-6f)
    assertEquals(0f, FlatStereo.stepStrength(0.003f, -1), 0f)
    assertEquals(0f, FlatStereo.stepStrength(0f, -1), 0f)
    assertEquals(0.12f, FlatStereo.stepStrength(0.12f, 1), 1e-6f)
    // From any value onto the grid of the steps
    assertEquals(0.035f, FlatStereo.stepStrength(0.0312f, 1), 1e-6f)
  }

  @Test
  fun `the Plane buttons step by a tenth, from far to near`() {
    assertEquals(0.6f, FlatStereo.stepPlane(0.5f, 1), 1e-6f)
    assertEquals(0.4f, FlatStereo.stepPlane(0.5f, -1), 1e-6f)
    assertEquals(1f, FlatStereo.stepPlane(1f, 1), 0f)
    assertEquals(0f, FlatStereo.stepPlane(0f, -1), 0f)
  }

  @Test
  fun `the Depth label shows tenths of a percent only when there are some`() {
    assertEquals("3%", FlatStereo.strengthPercent(0.03f))
    assertEquals("3.5%", FlatStereo.strengthPercent(0.035f))
    assertEquals("0.5%", FlatStereo.strengthPercent(0.005f))
    assertEquals("12%", FlatStereo.strengthPercent(0.12f))
  }

  @Test
  fun `no depth strength leaves both eyes as the color`() {
    val color = IntArray(6) { 0xFF000000.toInt() or (it * 40) }
    val depth = IntArray(6) { if (it < 3) far else near }
    val (left, right) = FlatStereo.synthesizeEyes(color, depth, 6, 1, maxDisparityPx = 0f)
    assertArrayEquals(color, left)
    assertArrayEquals(color, right)
  }

  @Test
  fun `depth on the screen plane leaves both eyes as the color`() {
    val color = IntArray(6) { 0xFF000000.toInt() or (it * 40) }
    val depth = IntArray(6) { 0xFF808080.toInt() }
    val (left, right) = FlatStereo.synthesizeEyes(color, depth, 6, 1, maxDisparityPx = 8f, plane = 128f / 255f)
    assertArrayEquals(color, left)
    assertArrayEquals(color, right)
  }

  @Test
  fun `a near object moves right in the left eye and left in the right eye, the background fills its holes`() {
    // A background at the screen plane (far, plane 0) with an object two pixels wide, as near as can be, at 4 and 5
    val color = IntArray(10) { if (it == 4 || it == 5) foreground else background }
    val depth = IntArray(10) { if (it == 4 || it == 5) near else far }
    val (left, right) = FlatStereo.synthesizeEyes(color, depth, 10, 1, maxDisparityPx = 4f, plane = 0f)
    val b = background
    val f = foreground
    assertArrayEquals(intArrayOf(b, b, b, b, b, b, f, f, b, b), left)
    assertArrayEquals(intArrayOf(b, b, f, f, b, b, b, b, b, b), right)
  }

  @Test
  fun `each row is warped on its own`() {
    val color = intArrayOf(background, foreground, background, background) + IntArray(4) { background }
    val depth = intArrayOf(far, near, far, far) + IntArray(4) { far }
    val (left, _) = FlatStereo.synthesizeEyes(color, depth, 4, 2, maxDisparityPx = 2f, plane = 0f)
    // Row 0: the near pixel moves from 1 to 2, its place takes the background beside it; row 1 stays background
    assertArrayEquals(intArrayOf(background, background, foreground, background) + IntArray(4) { background }, left)
  }
}
