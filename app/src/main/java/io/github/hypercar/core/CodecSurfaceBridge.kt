package io.github.hypercar.core

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** GPU-only scaling between hardware codecs; no decoded YUV readback. */
internal class CodecSurfaceBridge(output: Surface, private val sw: Int, private val sh: Int,
                                  private val tw: Int, private val th: Int) : AutoCloseable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var program = 0
    private var textureId = 0
    private var thread: HandlerThread? = null
    private val available = LinkedBlockingQueue<Boolean>(1)
    private var decoderSurface: Surface? = null
    val input: Surface get() = checkNotNull(decoderSurface)
    private val matrix = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }
    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            val configs = arrayOfNulls<EGLConfig>(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            window = EGL14.eglCreateWindowSurface(display,config,output,intArrayOf(EGL14.EGL_NONE),0)
            current()
            fun shader(kind: Int, source: String): Int {
                val id=GLES20.glCreateShader(kind)
                try { GLES20.glShaderSource(id,source); GLES20.glCompileShader(id)
                    val status=IntArray(1);GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,status,0)
                    check(status[0]!=0) { GLES20.glGetShaderInfoLog(id) }; return id
                } catch(e:Exception){GLES20.glDeleteShader(id);throw e}
            }
            val vs=shader(GLES20.GL_VERTEX_SHADER,"attribute vec4 p; attribute vec2 uv; uniform mat4 m; varying vec2 t; void main(){gl_Position=p;t=(m*vec4(uv,0.,1.)).xy;}")
            val fs=try {shader(GLES20.GL_FRAGMENT_SHADER,"""
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES image; uniform vec2 stepSize; uniform float sharp;
                varying vec2 t;
                void main(){vec3 c=texture2D(image,t).rgb;
                  vec3 n=(texture2D(image,t+vec2(stepSize.x,0.)).rgb+texture2D(image,t-vec2(stepSize.x,0.)).rgb+
                          texture2D(image,t+vec2(0.,stepSize.y)).rgb+texture2D(image,t-vec2(0.,stepSize.y)).rgb)*.25;
                  gl_FragColor=vec4(clamp(c+sharp*(c-n),0.,1.),1.);}
            """.trimIndent())}catch(e:Exception){GLES20.glDeleteShader(vs);throw e}
            program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs)
            val status=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0)
            check(status[0]!=0) { GLES20.glGetProgramInfoLog(program) }
            val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);textureId=ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            thread=HandlerThread("CarWithCodecSurface").also { it.start() }
            texture=SurfaceTexture(textureId).also { tex -> tex.setDefaultBufferSize(sw,sh)
                tex.setOnFrameAvailableListener({available.offer(true)},Handler(checkNotNull(thread).looper)) }
            decoderSurface=Surface(checkNotNull(texture))
            detach()
        } catch(error:Exception){close();throw error}
    }
    private fun current(){check(EGL14.eglMakeCurrent(display,window,window,context)){"Codec EGL makeCurrent failed"}}
    private fun detach(){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)}
    fun render(pts: Long) {
        check(available.poll(250,TimeUnit.MILLISECONDS)!=null){"Decoder Surface frame timeout"}
        current()
        try {
            val tex=checkNotNull(texture);tex.updateTexImage();tex.getTransformMatrix(matrix)
            GLES20.glViewport(0,0,tw,th);GLES20.glClearColor(0f,0f,0f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // The receiver owns the canvas: stretch the source over its full declared screen.
            // AirPlay's normalized touch -> source pixel mapping then stays valid on both axes.
            val scale=maxOf(tw.toDouble()/sw,th.toDouble()/sh)
            GLES20.glUseProgram(program)
            val pos=GLES20.glGetAttribLocation(program,"p");val uv=GLES20.glGetAttribLocation(program,"uv")
            vertices.position(0);GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(pos)
            vertices.position(2);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(uv)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"m"),1,false,matrix,0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program,"stepSize"),1f/sw,1f/sh)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"sharp"),if(scale>1.001) .18f else 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            check(GLES20.glGetError()==GLES20.GL_NO_ERROR){"Codec GPU draw failed"}
            check(EGLExt.eglPresentationTimeANDROID(display,window,pts*1000))
            check(EGL14.eglSwapBuffers(display,window)){"Codec EGL swap failed"}
        } finally {detach()}
    }
    override fun close() {
        decoderSurface?.release();decoderSurface=null
        texture?.release();texture=null
        thread?.quitSafely();thread=null
        if(display!=EGL14.EGL_NO_DISPLAY){
            if(context!=EGL14.EGL_NO_CONTEXT && window!=EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,window,window,context)){
                if(program!=0)GLES20.glDeleteProgram(program)
                if(textureId!=0)GLES20.glDeleteTextures(1,intArrayOf(textureId),0)
            }
            detach()
            if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,window)
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context)
            // Default EGLDisplay is process-shared: do not eglTerminate other active UI encoders.
            EGL14.eglReleaseThread()
        }
        window=EGL14.EGL_NO_SURFACE;context=EGL14.EGL_NO_CONTEXT;display=EGL14.EGL_NO_DISPLAY
    }
}
