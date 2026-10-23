package app.alextran.immich.immersive

import app.alextran.immich.immersive.ImmersiveControls.Action
import com.meta.spatial.runtime.ButtonBits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveInputTest {
  private val log = mutableListOf<String>()
  private val edges = ControllerEdges { log += it }

  private fun actions(
    controller: Int = 0,
    hand: Int = 0,
    panelVisible: Boolean = false,
    outside: Boolean = false,
    grabbable: Boolean = false,
    held: Boolean = false,
  ): List<Action> = ImmersiveControls.actionsFor(controller, hand, panelVisible, outside, grabbable, held)

  // Edges

  @Test
  fun `a press counts once, on the update it goes down`() {
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", 0))
    assertEquals(ButtonBits.ButtonA, edges.pressed(1, true, "CONTROLLER", ButtonBits.ButtonA))
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", ButtonBits.ButtonA))
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", 0))
    assertEquals(ButtonBits.ButtonA, edges.pressed(1, true, "CONTROLLER", ButtonBits.ButtonA))
  }

  @Test
  fun `a controller seen for the first time starts from what it holds`() {
    assertEquals(0, edges.pressed(7, true, "CONTROLLER", ButtonBits.ButtonSqueezeR))
    assertEquals(0, edges.pressed(7, true, "CONTROLLER", ButtonBits.ButtonSqueezeR))
    assertTrue(log.single().startsWith("controller 7 appeared"))
  }

  @Test
  fun `a controller that wakes up starts again from its buttons, then presses count again`() {
    edges.pressed(1, true, "CONTROLLER", 0)
    // Asleep with A held, as the SDK may leave it
    assertEquals(0, edges.pressed(1, false, "CONTROLLER", ButtonBits.ButtonA))
    // Awake: what it holds at that update is the new start, a stale A does not toggle the panel
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", ButtonBits.ButtonA))
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", 0))
    assertEquals(ButtonBits.ButtonA, edges.pressed(1, true, "CONTROLLER", ButtonBits.ButtonA))
    assertTrue(log.any { it.contains("is now controller, inactive") })
    assertTrue(log.any { it.contains("is now controller, active") })
  }

  @Test
  fun `a press from a controller the SDK still reports inactive is not lost`() {
    edges.pressed(1, false, "CONTROLLER", 0)
    assertEquals(ButtonBits.ButtonTriggerR, edges.pressed(1, false, "CONTROLLER", ButtonBits.ButtonTriggerR))
  }

  @Test
  fun `hands taking over from the controllers start from their pinch`() {
    edges.pressed(2, true, "CONTROLLER", 0)
    assertEquals(0, edges.pressed(2, true, "HAND", ButtonBits.ButtonA))
    assertEquals(0, edges.pressed(2, true, "HAND", ButtonBits.ButtonA))
    assertEquals(0, edges.pressed(2, true, "HAND", 0))
    assertEquals(ButtonBits.ButtonA, edges.pressed(2, true, "HAND", ButtonBits.ButtonA))
    assertTrue(log.any { it.contains("is now hand, active (was controller, active)") })
  }

  @Test
  fun `controllers that are gone are forgotten, and a recreated one starts afresh`() {
    edges.pressed(1, true, "CONTROLLER", 0)
    edges.pressed(2, true, "CONTROLLER", 0)
    edges.retain(setOf(2))
    assertTrue(log.any { it.startsWith("controller 1 is gone") })
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", ButtonBits.ButtonX))
    assertEquals(ButtonBits.ButtonX, edges.pressed(2, true, "CONTROLLER", ButtonBits.ButtonX))
  }

  @Test
  fun `a diagonal thumbstick push gives its first direction only`() {
    edges.pressed(1, true, "CONTROLLER", 0)
    assertEquals(ButtonBits.ButtonThumbRL, edges.pressed(1, true, "CONTROLLER", ButtonBits.ButtonThumbRL))
    // The up threshold crossed on the next update, the stick still held left: no second direction
    val diagonal = ButtonBits.ButtonThumbRL or ButtonBits.ButtonThumbRU
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", diagonal))
    // Both thresholds on the same update: left or right before up or down
    edges.pressed(1, true, "CONTROLLER", 0)
    assertEquals(ButtonBits.ButtonThumbRL, edges.pressed(1, true, "CONTROLLER", diagonal))
  }

  @Test
  fun `the two thumbsticks and the buttons are told apart`() {
    edges.pressed(1, true, "CONTROLLER", ButtonBits.ButtonThumbLU)
    val both = ButtonBits.ButtonThumbLU or ButtonBits.ButtonThumbRD or ButtonBits.ButtonB
    assertEquals(ButtonBits.ButtonThumbRD or ButtonBits.ButtonB, edges.pressed(1, true, "CONTROLLER", both))
  }

  // Actions

  @Test
  fun `B or Y closes and nothing else happens`() {
    assertEquals(listOf(Action.CLOSE), actions(controller = ButtonBits.ButtonB or ButtonBits.ButtonA))
    assertEquals(listOf(Action.CLOSE), actions(controller = ButtonBits.ButtonY or ButtonBits.ButtonTriggerR))
  }

  @Test
  fun `the thumbstick never touches the info panel`() {
    for (visible in listOf(false, true)) {
      assertEquals(listOf(Action.PREVIOUS), actions(controller = ButtonBits.ButtonThumbRL, panelVisible = visible))
      assertEquals(listOf(Action.NEXT), actions(controller = ButtonBits.ButtonThumbRR, panelVisible = visible))
      assertEquals(listOf(Action.TURN_LEFT), actions(controller = ButtonBits.ButtonThumbLL, panelVisible = visible))
      assertEquals(listOf(Action.TURN_RIGHT), actions(controller = ButtonBits.ButtonThumbLR, panelVisible = visible))
      assertEquals(listOf(Action.STICK_UP), actions(controller = ButtonBits.ButtonThumbRU, panelVisible = visible))
      assertEquals(listOf(Action.STICK_DOWN), actions(controller = ButtonBits.ButtonThumbRD, panelVisible = visible))
      assertEquals(
        listOf(Action.LEFT_STICK_UP),
        actions(controller = ButtonBits.ButtonThumbLU, panelVisible = visible),
      )
      assertEquals(
        listOf(Action.LEFT_STICK_DOWN),
        actions(controller = ButtonBits.ButtonThumbLD, panelVisible = visible),
      )
    }
  }

  @Test
  fun `the left thumbstick held left or right turns again after a delay, then at an interval`() {
    val held = ButtonBits.ButtonThumbLR
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", 0, nowMs = 1000))
    assertEquals(held, edges.pressed(1, true, "CONTROLLER", held, nowMs = 1000))
    // Held, but not for long enough yet
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", held, nowMs = 1000 + ControllerEdges.TURN_REPEAT_DELAY_MS - 1))
    assertEquals(held, edges.pressed(1, true, "CONTROLLER", held, nowMs = 1000 + ControllerEdges.TURN_REPEAT_DELAY_MS))
    val second = 1000 + ControllerEdges.TURN_REPEAT_DELAY_MS + ControllerEdges.TURN_REPEAT_INTERVAL_MS
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", held, nowMs = second - 1))
    assertEquals(held, edges.pressed(1, true, "CONTROLLER", held, nowMs = second))
    // Released: nothing more, and a new push starts over with its own delay
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", 0, nowMs = second + 1000))
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", 0, nowMs = second + 2000))
    assertEquals(held, edges.pressed(1, true, "CONTROLLER", held, nowMs = second + 3000))
    assertEquals(0, edges.pressed(1, true, "CONTROLLER", held, nowMs = second + 3000 + 100))
  }

  @Test
  fun `the other thumbstick directions and the buttons do not repeat`() {
    // The right thumbstick left or right changes the media: held, it must not skip through the album
    val bits = listOf(ButtonBits.ButtonThumbRR, ButtonBits.ButtonThumbRL, ButtonBits.ButtonThumbRU, ButtonBits.ButtonA)
    for (bit in bits) {
      val own = ControllerEdges()
      own.pressed(2, true, "CONTROLLER", 0, nowMs = 0)
      assertEquals(bit, own.pressed(2, true, "CONTROLLER", bit, nowMs = 0))
      assertEquals(0, own.pressed(2, true, "CONTROLLER", bit, nowMs = 5000))
    }
  }

  @Test
  fun `a turn held across a controller reset does not repeat`() {
    val held = ButtonBits.ButtonThumbLL
    edges.pressed(1, true, "CONTROLLER", 0, nowMs = 0)
    assertEquals(held, edges.pressed(1, true, "CONTROLLER", held, nowMs = 0))
    assertEquals(0, edges.pressed(1, false, "CONTROLLER", held, nowMs = 100))
    assertEquals(0, edges.pressed(1, false, "CONTROLLER", held, nowMs = 5000))
  }

  @Test
  fun `A, X, grip and menu show or hide the info panel`() {
    val toggles =
      listOf(
        ButtonBits.ButtonA,
        ButtonBits.ButtonX,
        ButtonBits.ButtonSqueezeL,
        ButtonBits.ButtonSqueezeR,
        ButtonBits.ButtonMenu,
      )
    for (bit in toggles) {
      assertEquals(listOf(Action.TOGGLE_PANEL), actions(controller = bit, panelVisible = false))
      assertEquals(listOf(Action.TOGGLE_PANEL), actions(controller = bit, panelVisible = true))
    }
  }

  @Test
  fun `on a flat screen the grip grabs it and leaves the panel alone`() {
    for (bit in listOf(ButtonBits.ButtonSqueezeL, ButtonBits.ButtonSqueezeR)) {
      assertEquals(emptyList<Action>(), actions(controller = bit, panelVisible = false, grabbable = true))
      assertEquals(emptyList<Action>(), actions(controller = bit, panelVisible = true, grabbable = true))
    }
    assertEquals(listOf(Action.TOGGLE_PANEL), actions(controller = ButtonBits.ButtonA, grabbable = true))
  }

  @Test
  fun `a trigger or a pinch away from the panel hides it and does nothing else`() {
    for (trigger in listOf(ButtonBits.ButtonTriggerL, ButtonBits.ButtonTriggerR)) {
      assertEquals(listOf(Action.HIDE_PANEL), actions(controller = trigger, panelVisible = true, outside = true))
      // On the panel it clicks a button
      assertEquals(emptyList<Action>(), actions(controller = trigger, panelVisible = true, outside = false))
    }
    assertEquals(listOf(Action.HIDE_PANEL), actions(hand = ButtonBits.ButtonA, panelVisible = true, outside = true))
    assertEquals(listOf(Action.HIDE_PANEL), actions(hand = ButtonBits.ButtonX, panelVisible = true, outside = true))
    assertEquals(emptyList<Action>(), actions(hand = ButtonBits.ButtonA, panelVisible = true, outside = false))
    // A button that toggles the panel keeps doing it
    assertEquals(
      listOf(Action.TOGGLE_PANEL, Action.PLAY_PAUSE),
      actions(controller = ButtonBits.ButtonA or ButtonBits.ButtonTriggerR, panelVisible = true, outside = true),
    )
  }

  @Test
  fun `while a flat screen is held the thumbsticks up or down resize it`() {
    for (up in listOf(ButtonBits.ButtonThumbRU, ButtonBits.ButtonThumbLU)) {
      assertEquals(listOf(Action.SCREEN_LARGER), actions(controller = up, grabbable = true, held = true))
    }
    for (down in listOf(ButtonBits.ButtonThumbRD, ButtonBits.ButtonThumbLD)) {
      assertEquals(listOf(Action.SCREEN_SMALLER), actions(controller = down, grabbable = true, held = true))
    }
    // Not held: as before
    assertEquals(listOf(Action.STICK_UP), actions(controller = ButtonBits.ButtonThumbRU, grabbable = true))
    assertEquals(listOf(Action.NEXT), actions(controller = ButtonBits.ButtonThumbRR, grabbable = true, held = true))
  }

  // Two handed stretch

  @Test
  fun `the stretch scales the screen by the ratio of the hands spread`() {
    val stretch = ScreenStretch()
    assertNull(stretch.update(null, 2f))
    // The second grip goes down: the start
    assertNull(stretch.update(0.4f, 2f))
    assertTrue(stretch.active)
    // The width read at the start counts, not the one passed later
    assertEquals(4f, stretch.update(0.8f, 3f)!!, 1e-4f)
    assertEquals(1f, stretch.update(0.2f, 3f)!!, 1e-4f)
    // A grip let go ends it, the next one starts from the width then
    assertNull(stretch.update(null, 1f))
    assertFalse(stretch.active)
    assertNull(stretch.update(0.5f, 1f))
    assertEquals(2f, stretch.update(1f, 0f)!!, 1e-4f)
  }

  @Test
  fun `hands one over the other neither start a stretch nor shrink the screen to nothing`() {
    val stretch = ScreenStretch()
    assertNull(stretch.update(0.01f, 2f))
    assertFalse(stretch.active)
    assertNull(stretch.update(0.5f, 2f))
    assertEquals(2f * ScreenStretch.MIN_SPREAD_M / 0.5f, stretch.update(0f, 2f)!!, 1e-4f)
  }

  @Test
  fun `the menu gesture toggles the panel, a pinch only shows a hidden one`() {
    assertEquals(listOf(Action.TOGGLE_PANEL), actions(hand = ButtonBits.ButtonMenu, panelVisible = true))
    assertEquals(listOf(Action.SHOW_PANEL), actions(hand = ButtonBits.ButtonA, panelVisible = false))
    assertEquals(listOf(Action.SHOW_PANEL), actions(hand = ButtonBits.ButtonX, panelVisible = false))
    // A pinch on a panel button clicks it, the panel stays
    assertEquals(emptyList<Action>(), actions(hand = ButtonBits.ButtonA, panelVisible = true))
  }

  @Test
  fun `the trigger plays or pauses while the panel is hidden only`() {
    assertEquals(listOf(Action.PLAY_PAUSE), actions(controller = ButtonBits.ButtonTriggerR, panelVisible = false))
    assertEquals(emptyList<Action>(), actions(controller = ButtonBits.ButtonTriggerL, panelVisible = true))
    // A pinch is no trigger
    assertEquals(emptyList<Action>(), actions(hand = ButtonBits.ButtonTriggerR, panelVisible = false))
    // The panel shown by the same update takes the trigger
    assertEquals(
      listOf(Action.TOGGLE_PANEL),
      actions(controller = ButtonBits.ButtonA or ButtonBits.ButtonTriggerR, panelVisible = false),
    )
  }

  @Test
  fun `controller buttons the SDK files under a hand still work`() {
    assertEquals(listOf(Action.CLOSE), actions(hand = ButtonBits.ButtonB))
    assertEquals(listOf(Action.NEXT), actions(hand = ButtonBits.ButtonThumbRR))
    assertEquals(
      listOf(Action.STICK_UP, Action.SHOW_PANEL),
      actions(hand = ButtonBits.ButtonThumbRU or ButtonBits.ButtonA),
    )
  }

  @Test
  fun `actions run in order, thumbstick then panel then trigger`() {
    assertEquals(
      listOf(Action.STICK_DOWN, Action.TOGGLE_PANEL),
      actions(controller = ButtonBits.ButtonThumbRD or ButtonBits.ButtonX, panelVisible = true),
    )
    assertEquals(
      listOf(Action.NEXT, Action.TOGGLE_PANEL, Action.PLAY_PAUSE),
      actions(
        controller = ButtonBits.ButtonThumbRR or ButtonBits.ButtonX or ButtonBits.ButtonTriggerL,
        panelVisible = true,
      ),
    )
  }
}
