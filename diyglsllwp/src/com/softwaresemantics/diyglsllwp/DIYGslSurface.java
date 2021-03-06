/*******************************************************************************
 * Cyril M. Hansen 2013
 * 
 * Licences :
 * Creative Commons Attribution-ShareAlike 3.0
 * Creative Commons Attribution - Partage dans les Mêmes Conditions 3.0 France
 * 
 * http://creativecommons.org/licenses/by-sa/3.0
 * http://creativecommons.org/licenses/by-sa/3.0/fr/
 * 
 * Sources :
 * https://github.com/cyrilmhansen/DIYGlslLwp
 ******************************************************************************/
package com.softwaresemantics.diyglsllwp;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.input.GestureDetector.GestureListener;
import com.badlogic.gdx.math.Vector2;

public class DIYGslSurface implements ApplicationListener, GestureListener {

	private static final String OPEN_GL_ES_2_0_REQUIRED = "OpenGL ES 2.0 required";

	private static final String ERROR_LOADING_SHADER = "Error loading shader";

	private static final String HARDWARE_TOO_SLOW_FOR_SHADER = "Hardware too slow for shader";

	private static final String DO_PROCESS_SCREEN_SHOT = "doProcessScreenShot";

	private static final String NO_EXCEPTION_MSG = "no exception msg";

	private String shaderProgram;

	private ShaderProgram shader;

	// Screen surface / 2 triangles
	Mesh mesh;

	private boolean timeDithering = false;
	private int timeDitheringFactor = 2;
	private int nbRender = 0;

	private boolean touchEnabled;

	private FrameBuffer m_fbo = null;
	private TextureRegion m_fboRegion = null;

	private SpriteBatch batch;
	private int renderSurfaceWidth;
	private int renderSurfaceHeight;

	private float m_fboScaler = 1.0f;
	private float epsilon = 0.00001f;

	// TODO : offer option / preserve ratio or fit shader to screen / for now
	// this is preserve ratio and truncate
	private int effectiveSurfaceWidth;
	private int effectiveSurfaceHeight;

	private float mouseCursorX;
	private float mouseCursorY;

	private long timeOrigin;

	private float time;

	private boolean doscreenShotRequest;
	private ScreenshotProcessor screenshotProc;

	private String errorMsg;

	private boolean showFPS = true;

	private BitmapFont font;

	private ClickHandler listener;

	private NativeCallback nativeCallback;

	private boolean timeLoop;

	private int timeLoopPeriod;
	
	private float speedFactor = 1.0f;

	private boolean forceMediump;

	private static boolean renderGuard = false;

	// GL ES 2.0 is required
	public boolean needsGL20() {
		return true;
	}

	/** detailed constructor */
	public DIYGslSurface(String shaderGLSL, boolean reductionFactorEnabled,
			int reductionFactor, boolean touchEnabled, boolean displayFPSLWP,
			boolean timeDither, int timeDitherFactor, boolean timeLoop,
			int timeLoopPeriod, boolean forceMediump, float speedFactor) {
		
		renderGuard = true;

		this.shaderProgram = shaderGLSL;
		this.m_fboScaler = reductionFactor;

		this.timeDithering = timeDither;
		this.timeDitheringFactor = timeDitherFactor;

		this.touchEnabled = touchEnabled;
		this.showFPS = displayFPSLWP;

		this.timeLoop = timeLoop;
		this.timeLoopPeriod = timeLoopPeriod;
		this.forceMediump = forceMediump;
		
		this.speedFactor = speedFactor;

	}

	public void updatePrefs(boolean reductionFactorEnabled,
			int reductionFactor, boolean touchEnabled, boolean displayFPSLWP,
			boolean timeDither, int timeDitherFactor, boolean timeLoop,
			int timeLoopPeriod, boolean forceMediump, float speedFactor) {

		this.m_fboScaler = reductionFactor;

		this.timeDithering = timeDither;
		this.timeDitheringFactor = timeDitherFactor;

		this.touchEnabled = touchEnabled;
		this.showFPS = displayFPSLWP;

		this.timeLoop = timeLoop;
		this.timeLoopPeriod = timeLoopPeriod;
		
		this.speedFactor = speedFactor;

		// change may require shader reload
		if (this.forceMediump != forceMediump) {
			this.forceMediump = forceMediump;
			setupShader();
		}

		updateRenderSurfaceSize();

		// Force re creation of framebuffer if needed
		this.m_fbo = null;

	}

	public void updateShader(String shaderGLSL) {
		// force mediump for float uniforms
		this.shaderProgram = shaderGLSL;

		setupShader();
	}

	/**
	 * Constructor with default params, custom shader
	 * 
	 * @param forcedShaderProgram
	 */
	public DIYGslSurface(String forcedShaderProgram) {
		renderGuard = true;
		
		this.shaderProgram = forcedShaderProgram;
	}

	/**
	 * Constructor with default params and shader (demo mode)
	 */
	public DIYGslSurface() {
		renderGuard = true;
	}

	public void create() {

		// GL 20 (GL2ES) Required
		if (!Gdx.graphics.isGL20Available()) {
			Gdx.app.log("DIYGslSurface", "isGL20Available returns false");
			if (nativeCallback != null) {
				nativeCallback.onRequirementFailure(null);
				// The Android specific code will decide to quit or not
				// Gdx.app.exit();
			}
		}

		Gdx.graphics.setVSync(true);

		Gdx.input.setInputProcessor(new GestureDetector(this));

		setupShader();

		effectiveSurfaceWidth = Gdx.graphics.getWidth();
		effectiveSurfaceHeight = Gdx.graphics.getHeight();

		updateRenderSurfaceSize();

		reserveRessources();

		// set the cursor somewhere else than @ 0,0
		// To be set as parameter ?
		mouseCursorX = 0.2f;
		mouseCursorY = 0.3f;
	}

	private void updateRenderSurfaceSize() {
		renderSurfaceWidth = effectiveSurfaceWidth;
		renderSurfaceHeight = effectiveSurfaceHeight;

		if (m_fboScaler > 1 + epsilon) {
			renderSurfaceWidth /= m_fboScaler;
			renderSurfaceHeight /= m_fboScaler;
		}
	}

	private void setupShader() {
		renderGuard = true;
		if (nativeCallback != null) {
			nativeCallback.notifyCompilation();
		}

		// setup shader and required associated data structures

		// setup verbose shader compile error messages
		ShaderProgram.pedantic = false;

		String effectiveFragShader = shaderProgram;

		errorMsg = null;
		if (shaderProgram != null) {
			try {
				if (forceMediump) {
					effectiveFragShader = "precision mediump float;\n"
							+ effectiveFragShader.replaceAll("uniform%sfloat",
									"uniform mediump float");
				}
				shader = new CustomShader(shaderProgram);
			} catch (Exception ex) {
				// fall back to default shader
				// notify user
				errorMsg = ex.getMessage();
			}
		}

		if (shader == null) {
			// default shader, should have no problem
			try {
				shader = new HerokuSampleShader();
			} catch (Exception ex) {
				errorMsg = ex.getMessage();
			}
		}

		if (shader == null && nativeCallback != null) {
			// Android code called to notify the error
			nativeCallback.onRequirementFailure(errorMsg);
		}

	
		mesh = genFullViewRectangle();
	
		timeOrigin = System.currentTimeMillis();
		renderGuard = false;
		if (nativeCallback != null) {
			nativeCallback.notifyCompilationEnd();
		}
	}

	public void render() {

		// render of LWP can be called while the gallery is being setup
		// or the other way round
		// in this case, static data are not usable and we may crash

		// we exclude this by explicitely checking a guard
		if (renderGuard)
			return;

		// GL 20 Required
		if (!Gdx.graphics.isGL20Available()) {
			Gdx.app.log("DIYGslSurface", "isGL20Available returns false");

			// Gdx.app.exit()
			displayErrorMsg();
			return;
		}

		if (handleErrorAndSlowHardware()) {
			return;
		}

		initRenderFramebufferIfNeeded();

		// we do not render all frames in time dithering mode
		if (timeDithering) {
			if (nbRender % timeDitheringFactor != 0) {
				// don't clear view, don't render, don't process screenshots
				// we need vsync to ensure some delay before next frame ??
				return;
			}
		}

		try {

			// intermediate framebuffer is always used
			// like in web gallery
			m_fbo.begin();

			// Gdx.gl20.glClearColor(0, 0, 0, 0);
			Gdx.gl20.glClear(GL20.GL_COLOR_BUFFER_BIT);

			renderShaderonMesh();

			m_fbo.end();

		} catch (Exception ex) {
			String msg = ex.getMessage() != null ? ex.getMessage()
					: NO_EXCEPTION_MSG;
			Gdx.app.log("GDX render", msg, ex);
		}

		// scaled render
		batch.begin();
		try {
			batch.disableBlending();
			batch.draw(m_fboRegion, 0, 0, effectiveSurfaceWidth,
					effectiveSurfaceHeight);
			batch.enableBlending();

		} catch (Exception ex) {
			String msg = ex.getMessage() != null ? ex.getMessage()
					: NO_EXCEPTION_MSG;
			Gdx.app.log("m_fbo scale", msg);
		} finally {
			batch.end();
		}

		// FPS is drawn on real screen
		// in all cases
		if (showFPS) {
			batch.begin();
			font.draw(batch, "time:" + time, Gdx.graphics.getWidth() - 250, 15);
			font.draw(batch, "FPS:" + Gdx.graphics.getFramesPerSecond(),
					Gdx.graphics.getWidth() - 60, 15);
			batch.end();
		}

		// (Screenshot includes FPS)

		// Process snapshot requests if any
		// Proper gl context for this is only available in render() aka here
		if (doscreenShotRequest && screenshotProc != null) {
			try {
				screenshotProc.doProcessScreenShot();
			} catch (Exception ex) {
				Gdx.app.log(DO_PROCESS_SCREEN_SHOT, ex.getLocalizedMessage());
			} finally {
				doscreenShotRequest = false;
			}
		}

	}

	private void displayErrorMsg() {
		batch.begin();
		font.draw(batch, OPEN_GL_ES_2_0_REQUIRED, 50, 50);
		batch.end();
	}

	private boolean handleErrorAndSlowHardware() {
		// If rendering is too slow, display an error
		if (Gdx.graphics.getDeltaTime() > 5
				&& Gdx.graphics.getFramesPerSecond() < 2) {
			shader = null;
			errorMsg = HARDWARE_TOO_SLOW_FOR_SHADER;
		}

		// If missing shader, display error
		if (shader == null) {
			if (errorMsg == null) {
				errorMsg = ERROR_LOADING_SHADER;
			}

			batch.begin();
			font.draw(batch, errorMsg, 50, 50);
			batch.end();

			return true;
		}

		return false;
	}

	/**
	 * called in render only
	 * 
	 * in all other cases, we want to force full reinit for optimization reasons
	 */
	private void initRenderFramebufferIfNeeded() {
		if (m_fbo == null) {
			forceNewRenderBuffer();
		}
	}

	private void forceNewRenderBuffer() {
		// RGB888 = No alpha channel needed
		m_fbo = new FrameBuffer(Format.RGB888, renderSurfaceWidth,
				renderSurfaceHeight, false);
		m_fboRegion = new TextureRegion(m_fbo.getColorBufferTexture());

		// view is y flipped without this
		m_fboRegion.flip(false, true);
	}

	/**
	 * handler for GDX resize event
	 */
	public void resize(int width, int height) {
		this.effectiveSurfaceWidth = width;
		this.effectiveSurfaceHeight = height;

		updateRenderSurfaceSize();

		if (m_fbo != null) {
			m_fbo.dispose();
		}

		forceNewRenderBuffer();
	}

	/**
	 * render shader on mesh, update shader vars for frame
	 */
	private void renderShaderonMesh() {

		shader.begin();

		shader.setUniformf("resolution", renderSurfaceWidth,
				renderSurfaceHeight);

		time = (float) ((System.currentTimeMillis() - timeOrigin) * speedFactor / 1000.0d);

		// Process optional time loop
		if (timeLoop && (time > timeLoopPeriod)) {
			timeOrigin = timeOrigin + (timeLoopPeriod * 1000);
		}

		shader.setUniformf("time", time);
		shader.setUniformf("mouse", mouseCursorX, mouseCursorY);

		// render surface as mesh
		mesh.render(shader, GL20.GL_TRIANGLES);

		shader.end();

	}

	/**
	 * @param maxX
	 * @param maxY
	 * @return mesh setup for rectangular view
	 */
	public static Mesh genFullViewRectangle() {

		float x1 = -1.0f, y1 = -1.0f;
		float x2 = 1.0f, y2 = 1.0f;
		float z0 = 0f;
		float u1 = 0.0f, v1 = 0.0f;
		float u2 = 1.0f, v2 = 1.0f;

		Mesh mesh = new Mesh(true, 4, 6, new VertexAttribute(Usage.Position, 3,
				ShaderProgram.POSITION_ATTRIBUTE), new VertexAttribute(
				Usage.TextureCoordinates, 2, ShaderProgram.TEXCOORD_ATTRIBUTE
						+ "0"));

		mesh.setVertices(new float[] { x1, y1, z0, u1, v1, x2, y1, z0, u2, v1,
				x2, y2, z0, u2, v2,
				// x1, y1, z0, u1, v1,
				// x2, y2, z0, u2, v2,
				x1, y2, z0, u1, v2 });

		// use indices to simplify vertices array
		mesh.setIndices(new short[] { 0, 1, 2, 0, 2, 3 });

		return mesh;
	}

	static class HerokuSampleShader extends CustomShader {
		private static final String DATA_SHADERS_HEROKUWIGGLE1_FRAG = "data/shaders/herokuwiggle1.frag";

		public HerokuSampleShader() {
			super(Gdx.files.internal(DATA_SHADERS_HEROKUWIGGLE1_FRAG)
					.readString());
		}
	}

	private static class CustomShader extends ShaderProgram {
		private static final String SHADER_COMPILATION_FAILED = "Shader compilation failed:\n";
		private static final String DATA_SHADERS_HEROKUBASE_VERT = "data/shaders/herokubase.vert";

		public CustomShader(String customFragShader) {

			super(
					Gdx.files.internal(DATA_SHADERS_HEROKUBASE_VERT)
							.readString(), customFragShader);

			if (!isCompiled()) {
				throw new RuntimeException(SHADER_COMPILATION_FAILED + getLog());
			}
		}

	}

	/**
	 * handler for android dispose event
	 */
	public void dispose() {
		freeRessourcesIfAny();
	}

	/**
	 * handler for android pause event
	 */
	public void pause() {
		freeRessourcesIfAny();
	}

	private void freeRessourcesIfAny() {

		// Free shader
		if (shader != null) {
			shader.dispose();
		}

		// Free ressources if any used
		if (m_fbo != null) {
			m_fbo.dispose();
			m_fbo = null;
		}

		m_fboRegion = null;

		// batch.dispose();
	}

	/**
	 * handler for android resume event
	 */
	public void resume() {

		Gdx.app.log("DIYGslSurface", "resume called");

		if (nativeCallback != null) {
			nativeCallback.onResumeGDX();
		}

		// Force recreation of buffers
		m_fbo = null;

		reserveRessources();
	}

	private void reserveRessources() {

		setupShader();

		batch = new SpriteBatch();
		font = new BitmapFont();

		forceNewRenderBuffer();

		setupShader();

	}

	// handler for ???
	public void previewStateChange(boolean isPreview) {
	}

	/**
	 * handler for touchDown Update cursor position if useful
	 */
	public boolean touchDown(float x, float y, int pointer, int button) {
		if (listener != null) {
			listener.onClick((int) x, (int) y);
		}
		if (touchEnabled) {
			// update mouse cursor pos
			mouseCursorX = x * 1.0f / renderSurfaceWidth;
			mouseCursorY = y * 1.0f / renderSurfaceHeight;
			return true;
		} else {
			// ignore event
			return false;
		}
	}

	/**
	 * tap event handler (ignored)
	 */
	public boolean tap(float x, float y, int count, int button) {
		return false;
	}

	/**
	 * longPress event handler (ignored)
	 */
	public boolean longPress(float x, float y) {
		// ignore event
		return false;
	}

	/**
	 * fling event handler (ignored)
	 */
	public boolean fling(float velocityX, float velocityY, int button) {
		// ignore event
		return false;
	}

	/**
	 * pan event handler
	 */
	public boolean pan(float x, float y, float deltaX, float deltaY) {
		if (touchEnabled) {
			// cam.translate(deltaX, deltaY);
			return true;
		} else {
			// ignore event
			return false;
		}
	}

	/**
	 * zoom event handler
	 */
	public boolean zoom(float initialDistance, float distance) {
		if (touchEnabled) {
			// float scaleInv = initialDistance / distance;
			// projectionUser.scale(scale, scale, 1.0f);
			// cam.zoom *= scaleInv;
			return true;
		} else {
			// ignore event
			return false;
		}
	}

	/**
	 * pinch event handler
	 */
	public boolean pinch(Vector2 initialPointer1, Vector2 initialPointer2,
			Vector2 pointer1, Vector2 pointer2) {
		// ignore event
		return false;
	}

	// Screen shot management
	// Screen shot processing is differed in render method due to gl details
	// data is then provided to screenshotProc callback

	public boolean isDoscreenShotRequest() {
		return doscreenShotRequest;
	}

	public void setDoscreenShotRequest(boolean doscreenShotRequest) {
		this.doscreenShotRequest = doscreenShotRequest;
	}

	public ScreenshotProcessor getScreenshotProc() {
		return screenshotProc;
	}

	public void setScreenshotProc(ScreenshotProcessor screenshotProc) {
		this.screenshotProc = screenshotProc;
	}

	public void addClickHandler(ClickHandler listener) {
		this.listener = listener;

	}

	public void setNativeCallback(NativeCallback callback) {
		this.nativeCallback = callback;
	}

	public static boolean isRenderGuard() {
		return renderGuard;
	}

	public static void setRenderGuard(boolean renderGuard) {
		DIYGslSurface.renderGuard = renderGuard;
	}

}
