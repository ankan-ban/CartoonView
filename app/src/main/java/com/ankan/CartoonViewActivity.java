package com.ankan;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.Semaphore;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.hardware.Camera.Size;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.Display;

public class CartoonViewActivity extends Activity 
{

	public static int cameraWidth = 800;
	public static int cameraHeight = 480;

    public static int screenWidth = 800;
    public static int screenHeight = 480;


	private MyOGLSurfView mGlSurfView;
	CameraPreview  CamSurfView;	
	
	/** Called when the activity is first created. */
    public void onCreate(Bundle savedInstanceState) 
    {
        super.onCreate(savedInstanceState);
        
        // request no auto-rotate - always want in landscape mode
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        
        // requesting to turn the title OFF
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // making it full screen
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // don't dim the screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Display display = getWindowManager().getDefaultDisplay();
        screenWidth = display.getWidth();
        screenHeight = display.getHeight();

        cameraWidth = screenWidth;
        cameraHeight = screenHeight;

        MyOGLRenderer.maxArraySize = cameraWidth * cameraHeight;

		// create a opengl surf veiw object and make it active/visible
        mGlSurfView = new MyOGLSurfView(getApplication());
        setContentView(mGlSurfView);
        
        
        // create the camera surface
        CamSurfView = new CameraPreview(this, mGlSurfView.mRenderer);
        LayoutParams test = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        test.height = 1;
        test.width = 1;
        
        // add it to the view - it won't be visible but still needs to be present :(
        addContentView(CamSurfView, test);
        
        // I have chosen to implement touch events in the Camera's surfcae view class
        mGlSurfView.setOnTouchListener(CamSurfView);
        
        // keep each other's references just for easy access
        CamSurfView.oglSurfView = mGlSurfView;
        mGlSurfView.mRenderer.camSurf = CamSurfView; 
    }
    
    // overriding the two function below (to call ogl surf view's functions) is necessary
    
    protected void onPause() 
    {
    	// notify android to put saved images in it's gallery, etc
        //sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file://" + Environment.getExternalStorageDirectory())));

        // we are not running any more
    	mGlSurfView.mRenderer.bRunning = false;
    	
    	// release both the semaphores in case somebody is waiting for it.
    	mGlSurfView.mRenderer.writeSema.release();
    	mGlSurfView.mRenderer.readSema.release();

        // destroy camera object
        // mGlSurfView.mRenderer.camSurf.destroyCamera();

    	super.onPause();
        mGlSurfView.onPause();
        finish();
    }
    
    protected void onResume() {
        // recreate camera object
        // mGlSurfView.mRenderer.camSurf.createCamera();

    	mGlSurfView.mRenderer.bRunning = true;
        super.onResume();
        mGlSurfView.onResume();
    }

    /*
    protected void onStop()
    {
        mGlSurfView.mRenderer.bRunning = false;

        // release both the semaphores in case somebody is waiting for it.
        mGlSurfView.mRenderer.writeSema.release();
        mGlSurfView.mRenderer.readSema.release();
        mGlSurfView.onPause();
        super.onStop();
    }

    protected void onRestart()
    {
        mGlSurfView.mRenderer.bRunning = true;
        mGlSurfView.onResume();
        super.onRestart();
    }
    */
}

// GL surface view class - don't really know why it's needed?
class MyOGLSurfView extends GLSurfaceView 
{
	public MyOGLRenderer mRenderer;
    public MyOGLSurfView(Context context) 
    {
        super(context);
        
        // set the openGL version we want to use
        setEGLContextClientVersion(2);
        
        // create the openGL renderer object and set it as the current renderer
        mRenderer = new MyOGLRenderer(context);
        setRenderer(mRenderer);
    }	
}

// the renderer class - that does most of the openGL rendering work
class MyOGLRenderer implements GLSurfaceView.Renderer, PreviewCallback
{
	public static int maxArraySize = 1920*1080;	// max size to ensure it's enough for both sizes
	
	Context mContext;	// the context - don't know in which context is this context named as the context :(
	public CameraPreview camSurf;
	
	// some constants
    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;

	private static final String TAG = "CartoonView: ";	
	
	// the vertex data
    private final float[] mVertexData = {
            // X, Y, Z, U, V
    		-1, -1, 0, 0, 1,
    		 1, -1, 0, 1, 1,
    		-1,  1, 0, 0, 0,
   		 	 1,  1, 0, 1, 0,
    		
    		} ;

    // the vertex buffer
    private FloatBuffer mVertexBuffer;
	 
    private int mProgram;			// the shader program (containing vertex and fragment programs)
    private int mTextureID;			// handle to the texture
    private int muMVPMatrixHandle;	// handle to the matrix in the shader's input layout?
    private int maPositionHandle;	// handle of position in the shader's input layout?
    private int maTextureHandle;   	// handle of texture coordinate in shader's input layout?

    private int mTexSizeHandle;     // handle of fragment shader constants (size of texture)
    private int mTexInvHandle;      // to fix silly front camera inversion
	
    // double-buffered data from camera
    byte[][] mYUVData;
    
    // the buffer indices to be written (by camera) and read (by renderer) respectively
    int writeIndex, readIndex;
    
    // semaphore to control synchronization between camera thread and renderer thread
	Semaphore writeSema, readSema;
	
    boolean cameraStarted = false;	// gross hack :(
    
    boolean takeScreenShot = false;

	boolean bRunning;	// flag indicates that the app is in foreground
    
    // the constructor - only a little initialization can be done here - rest is done in onSurfaceCreated
	public MyOGLRenderer(Context context) 
	{
		// save the context
		mContext = context;
		
		// create RAW array of RGB to hold the raw pixel data
		mYUVData = new byte[2][maxArraySize * 4];

		writeIndex = readIndex = 0;
		writeSema = new Semaphore(0);
		readSema = new Semaphore(1);
		
		// create the vertex buffer and put our raw vertices in it
		mVertexBuffer = ByteBuffer.allocateDirect(mVertexData.length
                * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
		mVertexBuffer.put(mVertexData).position(0);
	
		
        
        // rest of the initialization is done inside onSurfaceCreated - 
		// as other resources can be only created after surfaces (backbuffer/flipchain?) have been created
	}
	
	/*
	// for performance counting
    long RenderFrames = 0;
    long RenderStartTime = 0;
	*/
	
	// the function that gets called every frame. Do all rendering here
	public void onDrawFrame(GL10 arg0) {
		if(!bRunning) return;	// to make sure we don't mess with the semaphores when app is not in foreground

		// clear render target and z-buffer
		GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear( GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

        // gross hack
        if(!cameraStarted) return;
        
        // acquire the write semaphore
        try {
			writeSema.acquire();
		} catch (InterruptedException e) {
			Log.e("SemaphoreIssue ", "For some reason rendering thread got interrupted!");
		}
        
        // put the data in texture
		int w = CartoonViewActivity.cameraWidth;
		int h = CartoonViewActivity.cameraHeight;
		GLES20.glTexImage2D(GL10.GL_TEXTURE_2D, 0, GL10.GL_LUMINANCE, w, h, 0, GL10.GL_LUMINANCE, GL10.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mYUVData[readIndex]));

		// swap the buffers
        readIndex = 1 - readIndex;
        
        // release the read semaphore
        readSema.release();


        if (CameraPreview.useFrontCamera)
        {
            GLES20.glUniform1i(mTexInvHandle, 1);
        }
        else
        {
            GLES20.glUniform1i(mTexInvHandle, 0);
        }

        // draw a full screen quad to perform edge-detection/cartoonization
        // Ankan - for testing
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // save the current backbuffer contents if the user has touched (for taking screenshot)
        if(takeScreenShot)
        {
			final int width = CartoonViewActivity.screenWidth, height = CartoonViewActivity.screenHeight;	// for the backbuffer
	        SavePNG(0,0,width, height);
			takeScreenShot = false;
        }
        
        
        // for performance counting : start
        /*
        if(RenderFrames == 0)
        {
        	RenderStartTime = System.nanoTime();
        }
        if(RenderFrames == 60)
        {
         	Log.e("FPS ", "Draw FPS: " + (RenderFrames * 1000000000.0 / (System.nanoTime() - RenderStartTime)));
         	RenderFrames = 0;
        }
        else
        {
            RenderFrames++;
        }
        */
        // for performance counting : end
        
	}

	
	// helper functions to take/save screenshot
   public static Bitmap SavePixels(int x, int y, int w, int h)
   {  
        int b[]=new int[w*h];
        int bt[]=new int[w*h];
        IntBuffer ib=IntBuffer.wrap(b);
        ib.position(0);
        GLES20.glReadPixels(x, y, w, h, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, ib);
        for(int i=0; i<h; i++)
        {//remember, that OpenGL bitmap is incompatible with Android bitmap
         //and so, some correction need.        
             for(int j=0; j<w; j++)
             {
                  int pix=b[i*w+j];
                  int pb=(pix>>16)&0xff;
                  int pr=(pix<<16)&0x00ff0000;
                  int pix1=(pix&0xff00ff00) | pr | pb;
                  bt[(h-i-1)*w+j]=pix1;
             }
        }                  
        Bitmap sb=Bitmap.createBitmap(bt, w, h, Bitmap.Config.RGB_565);
        return sb;
   }	
   
   public void SavePNG(int x, int y, int w, int h)
   {
           Bitmap bmp=SavePixels(x,y,w,h);
           try
           {
        	   // create a File object for the parent directory
        	   File captureDir = new File(Environment.getExternalStorageDirectory()
   	                + "/cartoonCapture/");
        	   
        	   // have the object build the directory structure, if needed.
        	   captureDir.mkdirs();
        	   
        	   // create a File object for the output file
        	   File outputFile = new File(captureDir, "image_" + Long.toHexString(System.currentTimeMillis()) + ".png");
        	   
        	   // now attach the OutputStream to the file object, instead of a String representation
	           FileOutputStream fos=new FileOutputStream(outputFile);
	           
	           // compress the Bitmap object and write it into the file
	           bmp.compress(CompressFormat.PNG, 100, fos);
	           fos.flush();
	           fos.close();

               // send media scan notification so that the file appears in gallery, etc
               scanFile(outputFile.getAbsolutePath());
           }
           catch (IOException e)
           {
        	   e.printStackTrace();
           }              
   }

    private void scanFile(String path) {

        MediaScannerConnection.scanFile(mContext,
                new String[] { path }, null,
                new MediaScannerConnection.OnScanCompletedListener() {

                    public void onScanCompleted(String path, Uri uri) {
                        Log.i("TAG", "Finished scanning " + path);
                    }
                });
    }
	
	public void onSurfaceChanged(GL10 arg0, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
	}

    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
    	// create all resources here
		
    	// 1. Create the shaders and program
		
    	// Note: it looks like openGL has a concept of program which is a collection of shaders (in this example 1 VS + 1 PS)
    	
    	String vs = getStringFromFile(R.raw.vertex);
    	String ps = getStringFromFile(R.raw.pixel);
    	
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, ps);

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, pixelShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        mProgram = program;
        
        // set the shader
        GLES20.glUseProgram(mProgram);

        // 2. Get the handles of the attributes in the vertex shader?
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mTexSizeHandle    = GLES20.glGetUniformLocation(mProgram, "uTexSize");
        mTexInvHandle     = GLES20.glGetUniformLocation(mProgram, "uTexInv");

		// 3. bind the vertex buffer and attributes?
        
        // position attribute
        mVertexBuffer.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        
        // tex cord attribute
        mVertexBuffer.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);        
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        
        
        // 4. load the texture
        
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        mTextureID = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID);

        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        // set identity matrix
        float []myMatrix = new float[16];;
        Matrix.setIdentityM(myMatrix, 0);
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, myMatrix, 0);

        // Ankan - screenSize or cameraSize ?
        GLES20.glUniform2f(mTexSizeHandle, CartoonViewActivity.screenWidth, CartoonViewActivity.screenHeight);
    };
    
	// util functions 

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    private String getStringFromFile(int resourceId) {
        // The InputStream opens the resourceId and sends it to the buffer
        InputStream is = mContext.getResources().openRawResource(resourceId);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String readLine = null;
        String fullString = "";
        try {
            // While the BufferedReader readLine is not null 
            while ((readLine = br.readLine()) != null) {
            	readLine += "\n";
            	fullString += readLine;
            }

	        // Close the InputStream and BufferedReader
	        is.close();
	        br.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
        return fullString;
	}
    

    // for performance counting: start
    /*
    int Cameraframes = 0;
    long CameraStartTime = 0;
    */
    // for performance counting: end    
    
	// will get the frame data here
	public void onPreviewFrame(byte[] data, Camera camera) 
	{
		if(!bRunning) return;	// to make sure we don't mess with the semaphores when app is not in foreground
		
		cameraStarted = true;	// gross hack
		
		// release the write semaphore (as data is available)
		writeSema.release();
		
		// acquire the read semaphore (before we can let camera use the other buffer)
		try {
			readSema.acquire();
		} catch (InterruptedException e) {
			Log.e("SemaphoreIssue ", "for some reason camera thread got interrupted");
		}
		
		writeIndex = 1 - writeIndex;
		camSurf.mCamera.addCallbackBuffer(mYUVData[writeIndex]);
        
		
		
        // for performance counting : start
		/*
        if(Cameraframes == 0)
        {
        	CameraStartTime = System.nanoTime();
        }
        if(Cameraframes == 60)
        {
        	Log.e("FPS ", "Camera FPS: " + (Cameraframes * 1000000000.0 / (System.nanoTime() - CameraStartTime)));
        	Cameraframes = 0;
        }
        else
        {
        	 Cameraframes++;
        }
        */
        // for performance counting : end

	}    
	
	// util function - not currently used - but might be useful for something later
	/*
    static public void decodeYUV420SPGrayscale(int[] rgb, byte[] yuv420sp, int width, int height)
    {
    	final int frameSize = width * height;
    	
    	for (int pix = 0; pix < frameSize; pix++)
    	{
    		int pixVal = (0xff & ((int) yuv420sp[pix]));
    		rgb[pix] = 0xff000000 | (pixVal << 16) | (pixVal << 8) | pixVal;
    	} // pix
    }
	
    static public void decodeYUV420SP(int[] rgb, byte[] yuv420sp, int width, int height) {
    	final int frameSize = width * height;
    	
    	for (int j = 0, yp = 0; j < height; j++) {
    		int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
    		for (int i = 0; i < width; i++, yp++) {
    			int y = (0xff & ((int) yuv420sp[yp])) - 16;
    			if (y < 0) y = 0;
    			if ((i & 1) == 0) {
    				v = (0xff & yuv420sp[uvp++]) - 128;
    				u = (0xff & yuv420sp[uvp++]) - 128;
    			}
    			
    			int y1192 = 1192 * y;
    			int r = (y1192 + 1634 * v);
    			int g = (y1192 - 833 * v - 400 * u);
    			int b = (y1192 + 2066 * u);
    			
    			if (r < 0) r = 0; else if (r > 262143) r = 262143;
    			if (g < 0) g = 0; else if (g > 262143) g = 262143;
    			if (b < 0) b = 0; else if (b > 262143) b = 262143;
    			
    			rgb[yp] = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
    		}
    	}
    }
	 */
}














// Fake camera preview surface - never displayed but needed :'(
class CameraPreview extends SurfaceView implements SurfaceHolder.Callback, OnTouchListener {
    public MyOGLSurfView oglSurfView;
	SurfaceHolder mHolder;
    Camera mCamera;
    MyOGLRenderer mRenderer;
    
    CameraPreview(Context context, MyOGLRenderer rend) {
        super(context);
        
        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = getHolder();
        mRenderer = rend;
        
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        createCamera();
    }

    void createCamera()
    {
        mCamera = Camera.open(useFrontCamera ? 1: 0);
        setCameraParams(useFrontCamera);
    }

    void destroyCamera()
    {
        mCamera.setPreviewCallback(null);
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        destroyCamera();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {

    }
    
    private void setCameraParams(boolean frontCamera)
    {
    	mCamera.setPreviewCallback(null);
        mCamera.stopPreview();
    	
    	Camera.Parameters parameters = mCamera.getParameters();

        Size previewSize = parameters.getPreviewSize();

        Log.e("camera ", "camera preview size: " + previewSize.width + ", " + previewSize.height);

        if (previewSize.width != CartoonViewActivity.screenWidth ||
            previewSize.height != CartoonViewActivity.screenHeight)
        {
            boolean foundMatch = false;
            // see if camera supports screen size
            for(int i =0; i< parameters.getSupportedPreviewSizes().size(); i++){
                Size size = parameters.getSupportedPreviewSizes().get(i);
                if (size.width == CartoonViewActivity.screenWidth &&
                    size.height == CartoonViewActivity.screenHeight)
                {
                    foundMatch = true;
                    break;
                }
            }

            if (foundMatch) {
                parameters.setPreviewSize(CartoonViewActivity.screenWidth, CartoonViewActivity.screenHeight);
                CartoonViewActivity.cameraWidth = CartoonViewActivity.screenWidth;
                CartoonViewActivity.cameraHeight = CartoonViewActivity.screenHeight;
                mCamera.setParameters(parameters);
            }
            else
            {
                CartoonViewActivity.cameraWidth = previewSize.width;
                CartoonViewActivity.cameraHeight = previewSize.height;
            }
        }

        if (MyOGLRenderer.maxArraySize < CartoonViewActivity.cameraWidth * CartoonViewActivity.cameraHeight)
            MyOGLRenderer.maxArraySize = CartoonViewActivity.cameraWidth * CartoonViewActivity.cameraHeight;

        Log.e("camera ", "camera preview size used: " + CartoonViewActivity.cameraWidth + ", " + CartoonViewActivity.cameraHeight);

		//parameters.setPreviewFrameRate(30);
		
		//parameters.setSceneMode(Camera.Parameters.SCENE_MODE_NIGHT);
		//parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);

        /*
		parameters.set("camera-sensor", 0);		
		if(frontCamera)
		{
			parameters.set("camera-sensor", 1);  // for switching to front facing camera!
		}
		*/

		//mCamera.setParameters(parameters);
	    
	    mCamera.startPreview();
	    try
	    {
	    	mCamera.setPreviewDisplay(mHolder);
	    }
        catch (IOException exception) {
            mCamera.release();
            mCamera = null;
        }
	    
	    mCamera.addCallbackBuffer(mRenderer.mYUVData[mRenderer.writeIndex]);
    	mCamera.setPreviewCallbackWithBuffer(mRenderer);
    }
    
    
    public static boolean useFrontCamera = false;
    
	@Override
	public boolean onTouch(View arg0, MotionEvent event) {
		
		if(event.getAction() == MotionEvent.ACTION_DOWN)
		{
			Log.e("touch ", "touched... at: " + event.getRawX() + ", " + event.getRawY());
		}
		else if(event.getAction() == MotionEvent.ACTION_UP)
		{
			Log.e("touch ", "released... at: " + event.getRawX() + ", " + event.getRawY());
			
			if(event.getRawX() < CartoonViewActivity.screenWidth / 3)
			{
				// toggle camera
				useFrontCamera = !useFrontCamera;

                /*
				// set sizes - renderer class will read these values
				if(useFrontCamera)
				{
					CartoonViewActivity.cameraWidth = 640;
					CartoonViewActivity.cameraHeight = 360;
				}
				else
				{
					CartoonViewActivity.cameraWidth = 800;
					CartoonViewActivity.cameraHeight = 480;
				}
				*/
				
				// reset the camera preview thing
                destroyCamera();
                createCamera();
			}
			else
			{
				// save image
				// just set the flag here - renderer class will do the rest
				mRenderer.takeScreenShot = true;
			}
		}
		return true;
	}	    
    
}

