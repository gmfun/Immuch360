package app.alextran.immich.immersive

import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.getAbsoluteTransform
import kotlin.math.max

/**
 * Polls the controllers every frame, like the ControllerListenerSystem of Meta's SplatSample, and
 * reports the buttons just pressed plus the head pose. With hand tracking, A and X mean index
 * pinches, so hand buttons are reported separately. A thumbstick push reports one direction only.
 *
 * The presses come from [ControllerEdges], which compares each controller with its own previous
 * state rather than trusting Controller.changedButtons: after a long idle time the controllers
 * sleep, hand tracking may take over, and the SDK may flip isActive or recreate the controller
 * entities, the likely reasons why a user of build 14 found the viewer deaf to the buttons after a
 * long idle time. Every such transition is logged under the Immuch360 tag, and an exception in the
 * viewer never escapes into the SDK, which could otherwise stop running this system.
 */
internal class ImmersiveInputSystem(
  private val listener: Listener,
  private val now: () -> Long = { SystemClock.uptimeMillis() },
) : SystemBase() {
  interface Listener {
    fun onButtonsPressed(controllerBits: Int, handBits: Int)

    /**
     * Every frame, with the head pose, and [gripSpread] the distance in meters between the two controllers (or hands)
     * while both hold their grip, null otherwise: the stretch that resizes a flat screen, see [ScreenStretch].
     */
    fun onFrame(head: Pose?, gripSpread: Float?)
  }

  private val headQuery =
    Query.where { has(AvatarAttachment.id) }
      .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }

  private val edges = ControllerEdges { message -> Log.i(TAG, message) }

  override fun execute() {
    var controllerBits = 0
    var handBits = 0
    val grips = ArrayList<Vector3>(2)
    try {
      val controllers = Query.where { has(Controller.id) }.eval().filter { it.isLocal() }
      val present = HashSet<Long>()
      for (entity in controllers) {
        val controller = entity.getComponent<Controller>()
        val type = controller.type
        val active = controller.isActive
        present.add(entity.id)
        if ((controller.buttonState and GRIPS) != 0) {
          try {
            grips += getAbsoluteTransform(entity).t
          } catch (e: Exception) {
            Log.w(TAG, "no pose for the grip of controller ${entity.id}", e)
          }
        }
        // Inactive controllers are polled too: a press is a press even when the SDK reports the
        // controller asleep, which it may keep doing after a wake up
        val down = edges.pressed(entity.id, active, type.name, controller.buttonState, now())
        if (down == 0) continue
        if (!active) Log.i(TAG, "buttons 0x${Integer.toHexString(down)} from the inactive $type ${entity.id}")
        if (type == ControllerType.HAND) handBits = handBits or down else controllerBits = controllerBits or down
      }
      edges.retain(present)
    } catch (e: Exception) {
      Log.e(TAG, "controller polling failed", e)
    }
    if (controllerBits != 0 || handBits != 0) {
      try {
        listener.onButtonsPressed(controllerBits, handBits)
      } catch (e: Exception) {
        Log.e(TAG, "button handling failed", e)
      }
    }

    val head =
      try {
        headQuery.eval().firstOrNull()?.getComponent<Transform>()?.transform
      } catch (e: Exception) {
        Log.e(TAG, "head query failed", e)
        null
      }
    val gripSpread = if (grips.size == 2) (grips[0] - grips[1]).length() else null
    try {
      listener.onFrame(head, gripSpread)
    } catch (e: Exception) {
      Log.e(TAG, "frame handling failed", e)
    }
  }

  private companion object {
    val GRIPS = ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR
  }
}

/**
 * The two handed stretch of a flat screen: both grips held, the hands pulled apart or brought together scale the
 * screen by the same ratio, from the width it had when the second grip went down. Pure, unit tested.
 */
internal class ScreenStretch {
  private var startSpread = 0f
  private var startWidth = 0f

  /** Both grips are held and the stretch has started. */
  var active = false
    private set

  /**
   * The width in meters the screen should have for the hands [spread] meters apart, null while both grips are not
   * held (null [spread]) and on the frame the stretch starts. [width] is the width of the screen now, read only then.
   */
  fun update(spread: Float?, width: Float): Float? {
    if (spread == null) {
      active = false
      return null
    }
    if (!active) {
      if (spread < MIN_SPREAD_M || width <= 0f) return null
      active = true
      startSpread = spread
      startWidth = width
      return null
    }
    return startWidth * max(spread, MIN_SPREAD_M) / startSpread
  }

  companion object {
    /** Hands closer than this (one hand over the other) neither start a stretch nor shrink the screen to nothing. */
    const val MIN_SPREAD_M = 0.05f
  }
}

/**
 * Edge detection of the buttons of each controller (or tracked hand), kept per entity from one update to the next.
 * A controller seen for the first time, one that becomes active or inactive, and one that switches between hand and
 * controller start again from the buttons they hold at that update: nothing counts as pressed then, so that neither a
 * stale state from before a sleep nor a pinch held while the hands take over fires an action. [log] receives each of
 * those transitions, for logcat. The left thumbstick held left or right repeats its press (see [TURN_BITS]): a
 * sweep around the sphere is one long push, not a series of flicks. Pure: no Spatial SDK call, unit tested.
 */
internal class ControllerEdges(private val log: (String) -> Unit = {}) {
  private class Seen(var active: Boolean, var type: String, var buttons: Int) {
    /** The turn direction being held, and when it fires again (uptime ms); 0 when none is held. */
    var turnBit = 0
    var turnRepeatAt = 0L
  }

  private val controllers = HashMap<Long, Seen>()

  /**
   * The buttons of controller [id] pressed since its previous update, given whether it is [active], its [type] and the
   * buttons it holds now at [nowMs] (uptime). A thumbstick push reports one direction only, see [firstDirectionOnly].
   * A turn direction of the left thumbstick ([TURN_BITS]) still held [TURN_REPEAT_DELAY_MS] after its press is
   * reported again, then every [TURN_REPEAT_INTERVAL_MS], until the stick comes back.
   */
  fun pressed(id: Long, active: Boolean, type: String, buttons: Int, nowMs: Long = 0L): Int {
    val seen = controllers[id]
    if (seen == null) {
      log("controller $id appeared: ${describe(active, type)}, buttons ${hex(buttons)}")
      controllers[id] = Seen(active, type, buttons)
      return 0
    }
    if (seen.active != active || seen.type != type) {
      log(
        "controller $id is now ${describe(active, type)} (was ${describe(seen.active, seen.type)}), " +
          "buttons ${hex(buttons)}, edges reset",
      )
      seen.active = active
      seen.type = type
      seen.buttons = buttons
      seen.turnBit = 0
      return 0
    }
    val before = seen.buttons
    seen.buttons = buttons
    var down = buttons and before.inv()
    down = firstDirectionOnly(down, before, LEFT_STICK)
    down = firstDirectionOnly(down, before, RIGHT_STICK)
    return down or turnRepeat(seen, down, buttons, nowMs)
  }

  /** The turn direction to report again because the left thumbstick is still held, or 0. */
  private fun turnRepeat(seen: Seen, down: Int, buttons: Int, nowMs: Long): Int {
    val pressedTurn = down and TURN_BITS
    if (pressedTurn != 0) {
      seen.turnBit = pressedTurn.takeLowestOneBit()
      seen.turnRepeatAt = nowMs + TURN_REPEAT_DELAY_MS
      return 0
    }
    if (seen.turnBit == 0) return 0
    if ((buttons and seen.turnBit) == 0) {
      seen.turnBit = 0
      return 0
    }
    if (nowMs < seen.turnRepeatAt) return 0
    seen.turnRepeatAt = nowMs + TURN_REPEAT_INTERVAL_MS
    return seen.turnBit
  }

  /** Forgets the controllers that are gone (the SDK may recreate them after a sleep), logging each one. */
  fun retain(present: Set<Long>) {
    val iterator = controllers.entries.iterator()
    while (iterator.hasNext()) {
      val (id, seen) = iterator.next()
      if (id in present) continue
      log("controller $id is gone (was ${describe(seen.active, seen.type)})")
      iterator.remove()
    }
  }

  /**
   * The SDK thresholds each thumbstick axis separately: a diagonal push crosses the left threshold on one
   * frame and the up threshold on the next, two direction edges for one gesture. Keeps the first direction
   * of a push: drops the direction edges of [stick] when that stick already held a direction in the
   * previous update ([before]), and keeps a single one when several cross on the same frame (the lowest
   * bit, left or right before up or down).
   */
  private fun firstDirectionOnly(down: Int, before: Int, stick: Int): Int {
    val directions = down and stick
    if (directions == 0) return down
    val kept = if ((before and stick) != 0) 0 else directions.takeLowestOneBit()
    return (down and stick.inv()) or kept
  }

  private fun describe(active: Boolean, type: String): String =
    "${type.lowercase()}, ${if (active) "active" else "inactive"}"

  private fun hex(bits: Int): String = "0x${Integer.toHexString(bits)}"

  companion object {
    /** Direction bits of each thumbstick. */
    val LEFT_STICK =
      ButtonBits.ButtonThumbLL or ButtonBits.ButtonThumbLR or ButtonBits.ButtonThumbLU or ButtonBits.ButtonThumbLD
    val RIGHT_STICK =
      ButtonBits.ButtonThumbRL or ButtonBits.ButtonThumbRR or ButtonBits.ButtonThumbRU or ButtonBits.ButtonThumbRD

    /**
     * The left thumbstick left or right turns the view, and keeps turning while held. The right one changes the media
     * and never repeats: a held push must not skip through a whole album.
     */
    val TURN_BITS = ButtonBits.ButtonThumbLL or ButtonBits.ButtonThumbLR

    /** A push turns once at once; held past this delay it turns again, then at this interval. */
    const val TURN_REPEAT_DELAY_MS = 350L
    const val TURN_REPEAT_INTERVAL_MS = 220L
  }
}

/**
 * What the immersive viewer does with the buttons just pressed (ImmersiveInputSystem.Listener.onButtonsPressed),
 * decided apart from the viewer so that it can be tested:
 * - B or Y goes back to the app, and nothing else happens on that update;
 * - the right thumbstick left or right asks for the previous or the next media (the stick most headset galleries and
 *   players page with), the left thumbstick left or right turns the view, so that what is behind comes in front
 *   without turning the head, the right stick up or down seeks a video, sizes a flat screen or turns a photo, the left
 *   one up or down moves a flat screen farther or nearer (on any other media it does what the right one does); the
 *   viewer shows all of them on its small feedback line, never by bringing the info panel up;
 * - A, X or menu on a controller, and the menu gesture of a hand, show or hide the info panel; the grip too, except on
 *   a flat screen ([screenGrabbable]), where it grabs the screen to move it; an index pinch (A or X of a hand) only
 *   shows a hidden panel, since a pinch on a panel button must not hide the panel it clicks;
 * - the trigger plays or pauses a video while the panel is hidden (on the panel it clicks a button); the trigger or a
 *   pinch pointed away from the panel shown ([pressOutsidePanel]) hides it, and does nothing else;
 * - while the user holds a flat screen ([screenHeld]), either thumbstick up or down makes it larger or smaller.
 * Bits that only a controller has (B, Y, the thumbsticks) count as controller bits even when the SDK files them under
 * a hand: after the controllers wake up, the SDK may still take them for hands for a while.
 */
internal object ImmersiveControls {
  enum class Action {
    CLOSE,
    PREVIOUS,
    NEXT,
    TURN_LEFT,
    TURN_RIGHT,
    STICK_UP,
    STICK_DOWN,
    LEFT_STICK_UP,
    LEFT_STICK_DOWN,
    TOGGLE_PANEL,
    SHOW_PANEL,
    HIDE_PANEL,
    PLAY_PAUSE,
    SCREEN_LARGER,
    SCREEN_SMALLER,
  }

  /**
   * The actions for [controllerBits] and [handBits] pressed on the same update, in the order to run them.
   * [pressOutsidePanel]: the panel is shown and no pointer is on it. [screenGrabbable]: the media is on a flat screen
   * the grip grabs. [screenHeld]: the user holds that screen now.
   */
  fun actionsFor(
    controllerBits: Int,
    handBits: Int,
    panelVisible: Boolean,
    pressOutsidePanel: Boolean = false,
    screenGrabbable: Boolean = false,
    screenHeld: Boolean = false,
  ): List<Action> {
    val controllerOnly =
      ButtonBits.ButtonB or ButtonBits.ButtonY or ControllerEdges.LEFT_STICK or ControllerEdges.RIGHT_STICK
    val controller = controllerBits or (handBits and controllerOnly)
    val hand = handBits and controllerOnly.inv()
    if ((controller and (ButtonBits.ButtonB or ButtonBits.ButtonY)) != 0) return listOf(Action.CLOSE)
    val actions = mutableListOf<Action>()
    val stickUp = ButtonBits.ButtonThumbRU or ButtonBits.ButtonThumbLU
    val stickDown = ButtonBits.ButtonThumbRD or ButtonBits.ButtonThumbLD
    when {
      screenHeld && (controller and stickUp) != 0 -> actions += Action.SCREEN_LARGER
      screenHeld && (controller and stickDown) != 0 -> actions += Action.SCREEN_SMALLER
      (controller and ButtonBits.ButtonThumbRL) != 0 -> actions += Action.PREVIOUS
      (controller and ButtonBits.ButtonThumbRR) != 0 -> actions += Action.NEXT
      (controller and ButtonBits.ButtonThumbLL) != 0 -> actions += Action.TURN_LEFT
      (controller and ButtonBits.ButtonThumbLR) != 0 -> actions += Action.TURN_RIGHT
      (controller and ButtonBits.ButtonThumbRU) != 0 -> actions += Action.STICK_UP
      (controller and ButtonBits.ButtonThumbRD) != 0 -> actions += Action.STICK_DOWN
      (controller and ButtonBits.ButtonThumbLU) != 0 -> actions += Action.LEFT_STICK_UP
      (controller and ButtonBits.ButtonThumbLD) != 0 -> actions += Action.LEFT_STICK_DOWN
    }
    val grips = ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR
    val panelToggle =
      ButtonBits.ButtonA or ButtonBits.ButtonX or ButtonBits.ButtonMenu or (if (screenGrabbable) 0 else grips)
    val pinch = ButtonBits.ButtonA or ButtonBits.ButtonX
    val triggers = ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
    var visibleAfter = panelVisible
    if ((controller and panelToggle) != 0 || (hand and ButtonBits.ButtonMenu) != 0) {
      actions += Action.TOGGLE_PANEL
      visibleAfter = !panelVisible
    } else if (!panelVisible && (hand and pinch) != 0) {
      actions += Action.SHOW_PANEL
      visibleAfter = true
    } else if (panelVisible && pressOutsidePanel && ((controller and triggers) != 0 || (hand and pinch) != 0)) {
      // A click away from the panel dismisses it, as a click outside a menu does; the press does nothing else
      actions += Action.HIDE_PANEL
      return actions
    }
    // With the panel shown, the trigger clicks its buttons instead
    if (!visibleAfter && (controller and triggers) != 0) {
      actions += Action.PLAY_PAUSE
    }
    return actions
  }
}
