/** Copyright (c) 2022-2023, Harry Huang
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.animations.*;
import cn.harryh.arkpets.easings.EasingLinear;
import cn.harryh.arkpets.easings.EasingLinearVector2;
import cn.harryh.arkpets.utils.*;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.math.Vector2;

import java.util.*;

import static cn.harryh.arkpets.Const.*;


public class ArkPets extends ApplicationAdapter implements InputProcessor {
	/* RENDER PROCESS */
	public Plane plane;
	public ArkChar cha;
	public ArkConfig config;
	public ArkTray tray;
	public GeneralBehavior behavior;

	private HWndCtrl hWndTopmost;
	private LoopCtrl getHWndLoopCtrl;

	private int APP_FPS = fpsDefault;
	private float WD_SCALE; // Window Scale
	private int WD_W; // Window Real Width
	private int WD_H; // Window Real Height
	private boolean isToolwindowStyle;

	private Vector2 WD_poscur; // Window Current Position
	private Vector2 WD_postar; // Window Target Position
	private EasingLinear WD_alpha; // Window Opacity Easing
	private EasingLinearVector2 WD_poseas; // Window Position Easing
	private int OFFSET_Y = 0;

	public ArkPets(String $title) {
		APP_TITLE = $title;
	}

	@Override
	public void create() {
		// When the APP was created
		// 1.App setup
		Logger.info("App", "Create");
		Gdx.input.setInputProcessor(this);
		config = Objects.requireNonNull(ArkConfig.getConfig());
		APP_FPS = config.display_fps;
		Gdx.graphics.setForegroundFPS(APP_FPS);
		getHWndLoopCtrl = new LoopCtrl(1.0f / APP_FPS * 4);
		// 2.Character setup
		Logger.info("App", "Using model asset \"" + config.character_asset + "\"");
		cha = new ArkChar(config.character_asset, new AssetCtrl.AssetAccessor(config.character_files), skelBaseScale);
		cha.setCanvas(APP_FPS);
		behavior = new GeneralBehavior(config, cha.anim_list);
		cha.setAnimation(behavior.defaultAnim());
		Logger.info("Animation", "Animation stages " + behavior.getStages());
		// 3.Window params setup
		WD_poscur = new Vector2(0, 0);
		WD_postar = new Vector2(0, 0);
		WD_poseas = new EasingLinearVector2(new EasingLinear(0, 1, linearEasingDuration));
		WD_alpha = new EasingLinear(0, 1, linearEasingDuration);
		WD_SCALE = config.display_scale;
		WD_W = (int)(WD_SCALE * cha.flexibleLayout.getWidth());
		WD_H = (int)(WD_SCALE * cha.flexibleLayout.getHeight());
		// 4.Plane setup
		plane = new Plane(new ArrayList<>(), config.physic_gravity_acc);
		plane.setFrict(config.physic_air_friction_acc, config.physic_static_friction_acc);
		plane.setBounce(0);
		plane.setObjSize(WD_W, WD_H);
		plane.setSpeedLimit(config.physic_speed_limit_x, config.physic_speed_limit_y);
		ArkConfig.Monitor primaryMonitor = ArkConfig.Monitor.getMonitors()[0];
		initWindow((int)(primaryMonitor.size[0] * 0.1f), (int)(primaryMonitor.size[0] * 0.1f));
		promiseToolwindowStyle(1000);
		// 5.Tray icon setup
		tray = new ArkTray(this);
		// Setup complete
		Logger.info("App", "Render");
	}

	@Override
	public void render() {
		// 1.Render the next frame.
		cha.next();
		if (cha.anim_frame.isLoopEnd()) {
			// When an animation's loop ends:
			Logger.debug("Monitor", "FPS" + Gdx.graphics.getFramesPerSecond() + ", Heap" + (int)Math.ceil((Gdx.app.getJavaHeap() >> 10) / 1024f) + "MB");
		}

		// 2.Select a new animation.
		AnimData newAnim = behavior.autoCtrl(Gdx.graphics.getDeltaTime()); // AI anim.
		if (!mouse_drag) { // If no dragging:
			plane.updatePosition(Gdx.graphics.getDeltaTime());
			if (cha.anim_queue[0].mobility() != 0) {
				if (willReachBorder(cha.anim_queue[0].mobility())) {
					// Turn around if auto-walk cause the collision from screen border.
					newAnim = cha.anim_queue[0];
					newAnim = new AnimData(newAnim.animClip(), null, newAnim.loop(), newAnim.interruptable(), newAnim.offsetY(), -newAnim.mobility());
					tray.keepAnim = tray.keepAnim == null ? null : newAnim;
				}
				walkWindow(0.85f * cha.anim_queue[0].mobility());
			}
		} else { // If dragging:
			newAnim = behavior.dragging();
		}
		if (plane.getDropping()) { // If dropping, do not change anim.
			newAnim = behavior.defaultAnim();
		} else if (plane.getDropped()) { // If dropped, play the dropped anim.
			newAnim = behavior.dropped();
		} else if (tray.keepAnim != null) { // If keep-anim is enabled.
			newAnim = tray.keepAnim;
		}
		changeAnimation(newAnim); // Apply the new anim.

		// 3.Window activities.
		setWindowPosTar(plane.getX(), - (WD_H + plane.getY()));
		setWindowPosCur(Gdx.graphics.getDeltaTime());
		setWindowAlphaCur(Gdx.graphics.getDeltaTime());
		promiseToolwindowStyle(1);
	}

	@Override
	public void resize(int x, int y) {
		Logger.debug("Window", "Resized to " + x + " * " + y);
	}

	@Override
	public void dispose() {
		Logger.info("App", "Dispose");
		tray.remove();
	}

	public boolean canChangeStage() {
		return behavior != null && behavior.getStages().size() > 1;
	}

	public void changeStage() {
		if (canChangeStage()) {
			behavior.nextStage();
			Logger.info("Animation", "Changed to " + behavior.getCurrentStage());
			changeAnimation(behavior.defaultAnim());
		}
	}

	private void changeAnimation(AnimData animData) {
		if (animData != null && !animData.isEmpty()) {
			// If it is needed to change animation:
			if (cha.setAnimation(animData))
				OFFSET_Y = (int)(animData.offsetY() * config.display_scale);
		}
	}


	/* INPUT PROCESS */
	private final Vector2 mouse_pos = new Vector2();
	private int mouse_intention_x = 1;
	private boolean mouse_drag = false;

	@Override
	public boolean touchDown(int screenX, int screenY, int pointer, int button) {
		Logger.debug("Input", "Click+ Btn " + button +" @ " + screenX + ", " + screenY);
		mouse_pos.set(screenX, screenY);
		if (pointer <= 0) {
			if (button == Input.Buttons.LEFT) {
				cha.setAnimation(behavior.clickStart());
				tray.hideDialog();
				return true;
			} else if (button == Input.Buttons.RIGHT) {
				Logger.debug("Plane Debug Message", plane.getDebugMsg());
				// Toggle tray dialog:
				tray.toggleDialog((int)(plane.getX() + screenX), (int)(-plane.getY() - WD_H));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean touchDragged(int screenX, int screenY, int pointer) {
		// Logger.debug("Input", "Dragged to " + screenX + ", " + screenY);
		mouse_drag = true;
		int t = (int)Math.signum(screenX - mouse_pos.x);
		mouse_intention_x = t == 0 ? mouse_intention_x : t;
		int x = (int)(WD_poscur.x + screenX - mouse_pos.x);
		int y = (int)(WD_poscur.y + screenY - mouse_pos.y);
		plane.changePosition(Gdx.graphics.getDeltaTime(), x, - (WD_H + y));
		setWindowPos((int)plane.getX(),  - (WD_H + (int)plane.getY()), true);
		return true;
	}

	@Override
	public boolean touchUp(int screenX, int screenY, int pointer, int button) {
		Logger.debug("Input", "Click- Btn " + button +" @ " + screenX + ", " + screenY);
		mouse_pos.set(screenX, screenY);
		if (pointer <= 0) {
			if (!mouse_drag)
				if (button == Input.Buttons.LEFT)
					changeAnimation(behavior.clickEnd());
				else {
					cha.setPositionTar(cha.positionTar.x, cha.positionTar.y, mouse_intention_x);
					if (tray.keepAnim != null && cha.anim_queue[0].mobility() != 0) {
						AnimData newAnim = cha.anim_queue[0];
						newAnim = new AnimData(newAnim.animClip(), null, newAnim.loop(), newAnim.interruptable(), newAnim.offsetY(), Math.abs(newAnim.mobility()) * mouse_intention_x);
						tray.keepAnim = newAnim;
					}
				}
		}
		mouse_drag = false;
		return true;
	}

	@Override
	public boolean keyDown(int keycode) {
		Logger.debug("Input", "Key+ @ " + keycode);
		return false;
	}

	@Override
	public boolean keyUp(int keycode) {
		Logger.debug("Input", "Key- @ " + keycode);
		return false;
	}

	@Override
	public boolean keyTyped(char character) {
		return false;
	}

	@Override
	public boolean mouseMoved(int screenX, int screenY) {
		return false;
	}

	@Override
	public boolean scrolled(float a, float b) {
		return false;
	}

	/* WINDOW API */
	private final String APP_TITLE;
	public HWndCtrl hWndMine;

	private void initWindow(int x, int y) {
		// Initialize HWnd
		if (hWndMine == null || hWndMine.isEmpty())
			hWndMine = new HWndCtrl(null, APP_TITLE);
		hWndTopmost = refreshWindowIdx();
		// Set the initial style of the window
		hWndMine.setWindowExStyle(HWndCtrl.WS_EX_LAYERED | HWndCtrl.WS_EX_TOPMOST);
		refreshMonitorInfo();
		setWindowPos(x, y, true);
		setWindowTransparent(false);
		plane.changePosition(0, WD_postar.x, - (WD_postar.y + WD_H));
	}

	public void setWindowTransparent(boolean $transparent) {
		if ($transparent)
			hWndMine.setWindowExStyle(hWndMine.getWindowExStyle() | HWndCtrl.WS_EX_TRANSPARENT);
		else
			hWndMine.setWindowExStyle(hWndMine.getWindowExStyle() & ~HWndCtrl.WS_EX_TRANSPARENT);
	}

	private void setWindowPos(int x, int y, boolean override) {
		if (hWndMine == null) return;
		if (getHWndLoopCtrl.isExecutable(Gdx.graphics.getDeltaTime())) {
			refreshMonitorInfo();
			HWndCtrl new_hwnd_topmost = refreshWindowIdx();
			if (new_hwnd_topmost != hWndTopmost) {
				hWndTopmost = new_hwnd_topmost;
				if (x == WD_poscur.x && y == WD_poscur.y)
					return;
			}
		}
		if (override) {
			setWindowPosTar(x, y);
			WD_poscur.x = WD_postar.x;
			WD_poscur.y = WD_postar.y;
			WD_poseas.eX.curDuration = WD_poseas.eX.DURATION;
			WD_poseas.eY.curDuration = WD_poseas.eY.DURATION;
		}
		hWndMine.setWindowPosition(hWndTopmost, (int)WD_poscur.x, (int)WD_poscur.y, WD_W, WD_H);
	}

	private HWndCtrl refreshWindowIdx() {
		ArrayList<HWndCtrl> windowList = HWndCtrl.getWindowList(true);
		//Logger.debug("HWND", windowList.toString());
		HWndCtrl minWindow = null;
		HashMap<Integer, HWndCtrl> line = new HashMap<>();
		int myPos = (int)(WD_poscur.x + WD_W / 2);
		int minNum = 2048;
		int myNum = getArkPetsWindowNum(APP_TITLE);
		final float quantityProduct = 1;
		if (plane != null) {
			// Reset plane additions.
			plane.barriers.clear();
			plane.pointCharges.clear();
		}
		for (HWndCtrl hWndCtrl : windowList) {
			int wndNum = getArkPetsWindowNum(hWndCtrl.windowText);
			// Distinguish non-peer windows from peers.
			if (wndNum == -1){
				if (hWndCtrl.posLeft <= myPos && myPos <= hWndCtrl.posRight) {
					// This window and the app are share the same vertical line.
					if (-hWndCtrl.posBottom < plane.borderTop() && -hWndCtrl.posTop > plane.borderBottom()) {
						// This window is "under" the app.
						for (int h = -hWndCtrl.posTop; h > -hWndCtrl.posBottom; h--) {
							// Mark the window's y-position in the vertical line.
							if (!line.containsKey(h))
								line.put(h, (h == -hWndCtrl.posTop) ? hWndCtrl : HWndCtrl.EMPTY); // Record this window.
						}
					}
				}
			} else {
				if (config.behavior_do_peer_repulsion && wndNum != myNum && plane != null) {
					// This window is peer window, set as point charges.
					plane.setPointCharge(-hWndCtrl.getCenterY(), hWndCtrl.getCenterX(), quantityProduct);
				}
				// Find the last peer window to handle the z-index.
				if (wndNum > myNum && wndNum < minNum) {
					minNum = getArkPetsWindowNum(hWndCtrl.windowText);
					minWindow = hWndCtrl;
				}
			}
			// Window iteration end.
		}
		if (minWindow == null || minWindow.isEmpty()) {
			// Set as the top window if there is no peer.
			minWindow = new HWndCtrl(-1);
		}
		if (plane != null) {
			// Set barriers according to the vertical line.
			for (int h = (int)plane.borderTop(); h > plane.borderBottom(); h--) {
				if (line.containsKey(h)) {
					HWndCtrl temp = line.get(h);
					if (temp != null && temp.hWnd != null)
						plane.setBarrier(-temp.posTop, temp.posLeft, temp.windowWidth, false);
				}
			}
		}
		return minWindow; // Return the last peer window.
	}

	private void refreshMonitorInfo() {
		ArkConfig.Monitor[] monitors = ArkConfig.Monitor.getMonitors();
		if (monitors.length == 0) {
			Logger.error("App", "Failed to get monitors information");
			throw new RuntimeException("Launch ArkPets failed due to monitors config error.");
		}
		plane.world.clear();
		boolean flag = true;
		for (ArkConfig.Monitor i : ArkConfig.Monitor.getMonitors()) {
			if (!flag) break;
			flag = config.display_multi_monitors;
			float left = i.virtual[0];
			float right = left + i.size[0];
			float top = -i.virtual[1];
			float bottom = top - i.size[1] + config.display_margin_bottom;
			plane.world.add(new Plane.RectArea(left, right, top, bottom));
		}
	}

	private void promiseToolwindowStyle(int maxRetries) {
		if (!isToolwindowStyle) {
			// Make sure ArkPets has been set as foreground window once
			for (int i = 0; ; i++) {
				if (hWndMine.isForeground()) {
					hWndMine.setWindowExStyle(hWndMine.getWindowExStyle() | HWndCtrl.WS_EX_TOOLWINDOW);
					Logger.info("Window", "SetForegroundWindow succeeded");
					isToolwindowStyle = true;
					break;
				} else if (i > maxRetries) {
					return;
				}
				hWndMine.setForeground();
			}
		}
	}

	public static int getArkPetsWindowNum(String title) {
		final String prefix = coreTitle;
		final String prefix2 = " (";
		final String suffix = ")";
		if (title != null && !title.isEmpty()) {
			try {
				if (title.indexOf(prefix) == 0) {
					if (title.equals(prefix))
						return 0;
					if (title.indexOf(prefix+prefix2) == 0)
						if (title.lastIndexOf(suffix) == title.length()-suffix.length())
							return Integer.parseInt(title.substring(prefix.length()+prefix2.length(), title.length()-suffix.length()));
				}
			} catch (Exception e) {
				Logger.error("Window", "Unable to get ArkPets window number, details see below.", e);
				return -1;
			}
		}
		return -1; // Not ArkPets window
	}

	/* WINDOW OPERATION RELATED */
	private void walkWindow(float len) {
		float expectedLen = len * WD_SCALE * (30f / APP_FPS);
		int realLen = randomRound(expectedLen);
		plane.changePosition(Gdx.graphics.getDeltaTime(), WD_postar.x + realLen, - (WD_postar.y + WD_H));
	}

	private int randomRound(float val) {
		int integer = (int)val;
		float decimal = val - integer;
		int offset = Math.abs(decimal) >= Math.random() ? (val >= 0 ? 1 : -1) : 0;
		return integer + offset;
	}

	private boolean willReachBorder(float len) {
		if (plane == null) return false;
		return (plane.getX() >= plane.borderRight() - WD_W && len > 0) || (plane.getX() <= plane.borderLeft() && len < 0);
	}

	private void setWindowPosTar(float $pos_x, float $pos_y) {
		WD_postar.set($pos_x, $pos_y);
		WD_poseas.eX.update($pos_x);
		WD_poseas.eY.update($pos_y + OFFSET_Y);
	}

	private void setWindowPosCur(float $deltaTime) {
		WD_poscur.set(WD_poseas.eX.step($deltaTime), WD_poseas.eY.step($deltaTime));
		setWindowPos((int)WD_poscur.x, (int)WD_poscur.y, false);
	}

	public void setWindowAlphaTar(float $alpha) {
		WD_alpha.update($alpha);
	}

	private void setWindowAlphaCur(float $deltaTime) {
		if (WD_alpha.curValue == WD_alpha.TO) return;
		WD_alpha.step($deltaTime);
		hWndMine.setWindowAlpha(WD_alpha.curValue);
	}


	/* UTILS */
	public static class LoopCtrl {
		private final float minIntervalTime;
		private float accumTime;

		/** Loop Controller instance.
		 * @param $minIntervalTime The minimal interval time for each loop.
		 */
		public LoopCtrl(float $minIntervalTime) {
			minIntervalTime = $minIntervalTime;
		}

		/** Returns true if the loop is executable now.
		 * @param $deltaTime The updated delta time.
		 */
		public boolean isExecutable(float $deltaTime) {
			accumTime += $deltaTime;
			if (accumTime >= minIntervalTime) {
				accumTime = 0;
				return true;
			} else {
				return false;
			}
		}
	}
}
