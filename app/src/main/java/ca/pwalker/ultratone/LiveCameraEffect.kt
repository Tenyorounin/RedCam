package ca.pwalker.ultratone

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.camera.view.PreviewView

/** Applies the live look to CameraX's continuously updating preview on the GPU. */
internal class LiveCameraEffect private constructor(private val view: PreviewView) {
    private val shader = RuntimeShader(SOURCE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var depthBitmap: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
    @Volatile private var mode = ReferenceRenderer.Mode.DAY
    private var hasFreshDepthGuide = false
    private var infinityFocus = false
    private var circlesBitmap: Bitmap? = null
    init {
        shader.setInputShader("depthGuide", BitmapShader(depthBitmap!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("circles", BitmapShader(depthBitmap!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("strength", 0.78f)
        shader.setFloatUniform("zoomRatio", 1f)
        shader.setIntUniform("mode", 0)
        shader.setIntUniform("monochrome", 0)
        shader.setIntUniform("depthEnabled", 0)
        shader.setIntUniform("ringsOnly", 0)
        shader.setIntUniform("glowEnabled", 0)
        shader.setIntUniform("vignetteEnabled", 0)
        shader.setFloatUniform("viewSize", 1f, 1f)
        shader.setFloatUniform("depthScale", 1f, 1f)
        shader.setFloatUniform("depthOffset", 0f, 0f)
        shader.setFloatUniform("focusDepth", 0.5f)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "cameraImage"))
    }

    fun setLook(mode: ReferenceRenderer.Mode, finish: ReferenceRenderer.Finish, strength: Float, zoom: Float, nightEnhancement: Boolean = false, infinityFocus: Boolean = false, effects: ReferenceRenderer.Effects = ReferenceRenderer.Effects(), ringsOnly: Boolean = false) {
        if (this.mode != mode && mode == ReferenceRenderer.Mode.CLOSE_UP) {
            // Never apply a depth map from the scene that was visible before this mode switch.
            hasFreshDepthGuide = false
        }
        shader.setIntUniform("ringsOnly", if (ringsOnly) 1 else 0)
        this.mode = mode
        this.infinityFocus = infinityFocus

        if (infinityFocus) hasFreshDepthGuide = false
        shader.setIntUniform("mode", when (mode) {
            ReferenceRenderer.Mode.DAY -> 0
            ReferenceRenderer.Mode.NIGHT -> 1
            ReferenceRenderer.Mode.CLOSE_UP -> 2
            ReferenceRenderer.Mode.SUNSET -> 3
            ReferenceRenderer.Mode.LONG_EXPOSURE -> 4
            ReferenceRenderer.Mode.LANDSCAPE -> 5
        })
        shader.setIntUniform("glowEnabled", if (effects.glow) 1 else 0)
        shader.setIntUniform("vignetteEnabled", if (effects.vignette) 1 else 0)
        shader.setIntUniform("monochrome", if (finish == ReferenceRenderer.Finish.BLACK_AND_WHITE) 1 else 0)
        shader.setIntUniform("nightEnhancement", if (nightEnhancement && mode == ReferenceRenderer.Mode.NIGHT) 1 else 0)
        shader.setFloatUniform("strength", strength.coerceIn(0f, 1f))
        shader.setFloatUniform("zoomRatio", zoom.coerceAtLeast(1f))
        shader.setIntUniform("depthEnabled", if (mode == ReferenceRenderer.Mode.CLOSE_UP && hasFreshDepthGuide && !infinityFocus) 1 else 0)
        updateSize()
        // Re-attach after changing RuntimeShader uniforms so PreviewView's
        // hardware layer redraws immediately (not on a later camera rebind).
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "cameraImage"))
        view.invalidate()
        view.postInvalidateOnAnimation()
    }

    /** Takes ownership of the depth and foreground guides used by the lens blur. */
    fun updateDepthGuide(bitmap: Bitmap, circles: Bitmap, focusDepth: Float) {
        val oldCircles = circlesBitmap
        circlesBitmap = circles
        shader.setInputShader("circles", BitmapShader(circles, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        oldCircles?.let { mainHandler.postDelayed({ it.recycle() }, 2000L) }
        val oldDepth = depthBitmap
        depthBitmap = bitmap
        shader.setInputShader("depthGuide", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        hasFreshDepthGuide = true
        shader.setIntUniform("depthEnabled", if (mode == ReferenceRenderer.Mode.CLOSE_UP && hasFreshDepthGuide && !infinityFocus) 1 else 0)
        updateSize()
        if (oldDepth != null && oldDepth !== bitmap) {
            mainHandler.postDelayed({ if (oldDepth !== depthBitmap && !oldDepth.isRecycled) oldDepth.recycle() }, 2_000L)
        }
        shader.setFloatUniform("focusDepth", focusDepth.coerceIn(0f, 1f))
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "cameraImage"))
        view.invalidate()
        view.postInvalidateOnAnimation()
    }

    fun clearDepthGuide() {
        hasFreshDepthGuide = false
        shader.setIntUniform("depthEnabled", 0)
        view.invalidate()
        view.postInvalidateOnAnimation()
    }

    private fun updateSize() {
        val viewWidth = view.width.coerceAtLeast(1).toFloat()
        val viewHeight = view.height.coerceAtLeast(1).toFloat()
        shader.setFloatUniform("viewSize", viewWidth, viewHeight)
        val guide = depthBitmap
        val guideWidth = guide?.width?.coerceAtLeast(1)?.toFloat() ?: 1f
        val guideHeight = guide?.height?.coerceAtLeast(1)?.toFloat() ?: 1f
        // PreviewView is configured as FIT_CENTER, so the full camera frame is
        // contained inside the view with letterboxing where the aspect ratios
        // differ. The depth guide uses that same mapping as the displayed image.
        val fitScale = minOf(viewWidth / guideWidth, viewHeight / guideHeight)
        val renderedWidth = guideWidth * fitScale
        val renderedHeight = guideHeight * fitScale
        val offsetX = (viewWidth - renderedWidth) / 2f
        val offsetY = (viewHeight - renderedHeight) / 2f
        val scaleX = guideWidth / renderedWidth.coerceAtLeast(1f)
        val scaleY = guideHeight / renderedHeight.coerceAtLeast(1f)
        val apertureRadius = 3.5f
        shader.setFloatUniform("bokehRadius", apertureRadius * fitScale)
        shader.setFloatUniform(
            "depthScale",
            scaleX,
            scaleY
        )
        shader.setFloatUniform(
            "depthOffset",
            -offsetX * scaleX,
            -offsetY * scaleY
        )
    }

    fun close() {
        view.setRenderEffect(null)
        depthBitmap?.let { if (!it.isRecycled) it.recycle() }
        depthBitmap = null
        circlesBitmap?.recycle()
        circlesBitmap = null
    }

    companion object {
        fun attach(view: PreviewView): LiveCameraEffect? {
            if (Build.VERSION.SDK_INT < 33) return null
            return try { LiveCameraEffect(view) } catch (_: Exception) { null }
        }

        private val SOURCE = """
            uniform shader cameraImage;
            uniform shader depthGuide;
            uniform shader circles;
            uniform float strength;
            uniform float zoomRatio;
            uniform int mode;
            uniform int monochrome;
            uniform int nightEnhancement;
            uniform int depthEnabled;
            uniform int ringsOnly;
            uniform int glowEnabled;
            uniform int vignetteEnabled;
            uniform float2 viewSize;
            uniform float2 depthScale;
            uniform float2 depthOffset;
            uniform float focusDepth;
            uniform float bokehRadius;

            float lumaOf(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
            float3 enhanceNight(float2 p, float3 rgb) {
                float2 offset = 3.0 / max(viewSize, float2(1.0));
                float localLight = (
                    lumaOf(cameraImage.eval(p + float2(offset.x, 0.0)).rgb) +
                    lumaOf(cameraImage.eval(p - float2(offset.x, 0.0)).rgb) +
                    lumaOf(cameraImage.eval(p + float2(0.0, offset.y)).rgb) +
                    lumaOf(cameraImage.eval(p - float2(0.0, offset.y)).rgb) +
                    lumaOf(rgb) * 2.0
                ) / 6.0;
                float current = lumaOf(rgb);
                float mappedLight = 0.93 * pow(max(localLight, 0.0), 0.78) + 0.018;
                float reflectance = clamp(current / (localLight + 0.045), 0.0, 2.6);
                float restored = clamp(mappedLight * pow(reflectance, 0.90), 0.0, 1.0);
                float target = mix(current, restored, 0.72);
                float saturation = mix(0.70, 1.0, smoothstep(0.06, 0.34, current));
                return clamp(float3(target) + (rgb - float3(current)) * saturation, 0.0, 1.0);
            }

            float3 grade(float3 rgb) {
                float amount = clamp(strength, 0.0, 1.0);
                float luma = lumaOf(rgb);
                float target = (luma - 0.46) * 1.08 + 0.46 + clamp((0.46 - luma) * 0.08, -0.025, 0.025);
                float toe = 1.0 - smoothstep(0.0, 0.30, target);
                target += 0.014 * toe;
                float shoulder = smoothstep(0.72, 1.0, target);
                target -= 0.16 * shoulder * max(target - 0.72, 0.0);
                float detail = clamp((luma - 0.46) * (0.035 + clamp((zoomRatio - 1.0) / 4.0, 0.0, 1.0) * 0.025), -0.025, 0.025);
                target = clamp(mix(luma, target + detail, amount), 0.0, 1.0);

                if (monochrome == 1) {
                    float mono = dot(rgb, float3(0.28, 0.62, 0.10));
                    float bw = clamp((mono - 0.46) * 1.16 + 0.46, 0.0, 1.0);
                    return float3(mix(luma, bw, amount));
                }

                float saturation = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
                float3 chroma = rgb - float3(luma);
                float3 color = float3(target) + chroma * (1.04 + clamp(saturation, 0.0, 0.6) * 0.10);
                float warmTone = smoothstep(0.02, 0.22, min(rgb.r, rgb.g) - rgb.b);
                float greenTone = smoothstep(0.015, 0.18, rgb.g - max(rgb.r, rgb.b));
                float shadow = 1.0 - smoothstep(0.08, 0.58, luma);
                float highlight = smoothstep(0.56, 0.96, luma);
                float night = mode == 1 ? 1.0 : 0.0;
                color.r += (0.012 * warmTone + 0.006 * highlight - 0.003 * shadow) * amount;
                color.g += (-0.010 * greenTone + 0.001 * highlight) * amount;
                color.b += (-0.008 * warmTone - 0.005 * highlight + (0.004 + 0.004 * night) * shadow) * amount;
                if (night > 0.0) {
                    color.r *= 1.0 - 0.025 * shadow;
                    color.b *= 1.0 + 0.025 * shadow;
                }
                if (mode == 3) {
                    color.r += 0.020 * highlight - 0.012 * shadow;
                    color.g += 0.004 * highlight + 0.003 * shadow;
                    color.b += -0.024 * highlight + 0.020 * shadow;
                }
                return clamp(color, 0.0, 1.0);
            }

            float4 main(float2 p) {
                float4 base = cameraImage.eval(p);
                float3 rgb = base.rgb;
                if (nightEnhancement == 1 && mode == 1) rgb = enhanceNight(p, rgb);
                if (depthEnabled == 1) {
                    float2 dp = p * depthScale + depthOffset;
                    float4 guide = depthGuide.eval(dp);
                    // Depth values are inverse depth (higher is closer), so only
                    // blur the farther background side of the selected subject.
                    float person = smoothstep(0.18, 0.62, guide.g);
                    float separation = max(focusDepth - guide.r, 0.0);
                    float blur = smoothstep(0.12, 0.50, separation);
                    blur = blur * blur * 0.88;
                    blur *= 1.0 - person;
                    if (blur > 0.002 && ringsOnly == 1) {
                        float4 light = circles.eval(dp);
                        rgb = rgb * (1.0 - light.a * blur) + light.rgb * blur;
                    }
                    if (blur > 0.002 && ringsOnly == 0) {
                        // Gather RGB from inside a soft-edged circular aperture. Each
                        // tap is checked against its own depth so near/focused detail
                        // cannot bleed into the defocused background. Bright source
                        // colors therefore become colored CoC discs through the same
                        // blur kernel; no synthetic white circles are painted on top.
                        float radius = blur * bokehRadius;
                        float3 softened = float3(0.0);
                        float weightSum = 0.0;
                        for (int i = 0; i < 12; i++) {
                                // Vogel disk: evenly covers a circular aperture without
                                // the visible lattice from a clipped square grid.
                                float dist = sqrt((float(i) + 0.5) / 12.0);
                                float angle = float(i) * 2.39996323;
                                float2 unitOffset = dist * float2(cos(angle), sin(angle));
                                float2 samplePos = p + unitOffset * radius;
                                float4 sampleGuide = depthGuide.eval(samplePos * depthScale + depthOffset);
                                float samplePerson = smoothstep(0.18, 0.62, sampleGuide.g);
                                float sampleSeparation = max(focusDepth - sampleGuide.r, 0.0);
                                float sampleBlur = smoothstep(0.12, 0.50, sampleSeparation);
                                sampleBlur = sampleBlur * sampleBlur * 0.88;
                                sampleBlur *= 1.0 - samplePerson;
                                if (sampleBlur < 0.06) continue;
                                float weight = 1.0 - smoothstep(0.78, 1.0, dist);
                                softened += cameraImage.eval(samplePos).rgb * weight;
                                weightSum += weight;
                        }
                        if (weightSum > 0.001) {
                            softened /= weightSum;
                            rgb = mix(rgb, softened, blur);
                            float4 light = circles.eval(dp);
                            rgb = rgb * (1.0 - light.a * blur) + light.rgb * blur;
                        }
                    }
                }
                float3 result = grade(rgb);
                if (glowEnabled == 1) {
                    float2 haloOffset = max(viewSize / 150.0, float2(1.0));
                    float halo = max(
                        max(lumaOf(cameraImage.eval(p + float2(haloOffset.x, 0.0)).rgb),
                            lumaOf(cameraImage.eval(p - float2(haloOffset.x, 0.0)).rgb)),
                        max(lumaOf(cameraImage.eval(p + float2(0.0, haloOffset.y)).rgb),
                            lumaOf(cameraImage.eval(p - float2(0.0, haloOffset.y)).rgb))
                    );
                    if (glowEnabled == 1) {
                        float bloom = smoothstep(0.68, 0.97, halo) * 0.075;
                        result += float3(bloom, bloom * 0.98, bloom * 0.92);
                    }
                }
                if (vignetteEnabled == 1) {
                    float2 centered = (p / max(viewSize, float2(1.0)) - float2(0.5)) * 2.0;
                    float radius = length(centered);
                    float falloff = smoothstep(0.48, 1.32, radius);
                    result *= 1.0 - 0.20 * falloff;
                }
                return float4(clamp(result, 0.0, 1.0), base.a);
            }
        """.trimIndent()
    }
}
