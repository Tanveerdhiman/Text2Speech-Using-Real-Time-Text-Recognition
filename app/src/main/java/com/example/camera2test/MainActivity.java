package com.example.camera2test;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.text.FirebaseVisionText;

public class MainActivity extends AppCompatActivity implements SpellCheckerSession.SpellCheckerSessionListener {

    private static final String TAG = "txtProcessor";
    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private AutoFitTextureView mTextureView;

    private final int mPreviewWidth = 1280;
    private final int mPreviewHeight = 720;

    public int elementIndex = 0;
    public int graphicIndex = 0;
    public TextServicesManager tsm;


    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            setupCamera(mPreviewWidth, mPreviewHeight);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) { //
            mCameraDevice = camera;
            startPreview();
            // Toast.makeText(getApplicationContext(), "Camera Connection Made!", Toast.LENGTH_SHORT).show();

        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            mCameraDevice = null;

        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;

        }
    };

    private ImageReader mImageReader;

    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private String mCameraId;
    private Size mPreviewSize;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size o1, Size o2) {
            return Long.signum((long) o1.getWidth() * o1.getHeight() /
                    (long) o2.getWidth() * o2.getHeight());
        }
    }

    public SpellCheckerSession session;
    Boolean isTTSComplete;
    int ttsCount = 0, prevTTSCount = 0;
    int indexSearched;
    List<TextGraphic> graphicsList = new ArrayList<>();
    int tapCount = 0;
    private TextToSpeech tts;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;
    private StringBuilder sb = new StringBuilder();
    private GraphicOverlay<TextGraphic> mGraphicOverlay;
    private TextRecognizer mTextRecognizer = new TextRecognizer();
    private int rotation;
    private final ImageReader.OnImageAvailableListener onImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireNextImage();

            /** here i can obtain a ByteBuffer directly using image.getPlanes[0].getBuffer but i have to use this method as the textRecognizer only recognises half part of the image when used with that method.
             * This can be due to 3 different planes as this method uses all the 3 planes but if we use the direct method we only use one plane**/
            ByteBuffer byteBuffer = imageToByteBuffer(image);
            //byte[] bytes = new byte[byteBuffer.capacity()];

            try {
                rotation = getRotationCompensation(mCameraId);
            } catch (CameraAccessException e) {
                Log.d(TAG, "onImageAvailable: CameraAccessException");
                e.printStackTrace();
            }

            mTextRecognizer.setupResolution(mPreviewWidth, mPreviewHeight);
            mTextRecognizer.imageFromByteBuffer(byteBuffer, rotation);
            fetchSuggestionsFor(mTextRecognizer.textOutput);


            image.close();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTextureView = findViewById(R.id.textureView);

        mGraphicOverlay = findViewById(R.id.graphicOverlay);

        mTextRecognizer.mGraphicOverlay = mGraphicOverlay;

        tsm = (TextServicesManager) getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        session = tsm.newSpellCheckerSession(Bundle.EMPTY, Locale.ENGLISH, this, true);

        gestureDetector = new GestureDetector(this, new CaptureGestureListener());
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());



        TextToSpeech.OnInitListener listener = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    Log.d("TTS", "Text to speech engine started successfully.");
                    tts.setLanguage(Locale.US);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            isTTSComplete = false;
                            Log.d("graphicText", "onDone:mProgress is Starting");

                            /**What if we can just find the elements of the Line That been spoken and once we got the elements we will make a loop or a function which will add them after a couple of Seconds.
                             * We can use the index of the elements to draw them after some seconds */


                            Timer t = new Timer();
                            TimerTask tt = new TimerTask() {
                                int index = 0;

                                @Override
                                public void run() {
                                    /** here we scan through all the text in the textList and we keep drawing textboxes as the TTS engine speaks
                                     * We may also control whether to draw lines or single text elements as we scan through the words in the list*/

                                    if (mGraphicOverlay.isTapValid){
                                        mGraphicOverlay.desiredGraphic = mGraphicOverlay.graphicsBox.get(graphicIndex);

                                    if (mGraphicOverlay.desiredGraphic != null) {

                                        if (index < mGraphicOverlay.desiredGraphic.getTextBlock().getElements().size()) {
                                            FirebaseVisionText.Element textElement;

                                            // lineElement = mGraphicOverlay.desiredGraphic.get

                                            /**here we can directly access the elements by desiredGraphics.getElements but if we feed individual Elements to the TTS Engine
                                             * instead of Lines, then the TTS Engine speaks text word by word and doesnot speak like lines.
                                             * therefore we are obtaining elements from lines and going through all the elements by updating the index*/

                                        //    textElement = mGraphicOverlay.desiredGraphic.getTextBlock().getElements().get(index); // we are obtaing the elements from the line so that we scan through all the elements
                                        //    Log.d("LineLength", "element:" + textElement.getText() + " Index: " + index);
                                         //   TextGraphic textGraphic = new TextGraphic(mGraphicOverlay, textElement);
                                        //    mGraphicOverlay.graphicElement = textGraphic;
                                        }

                                            FirebaseVisionText.Line lineElement;
                                            lineElement = mGraphicOverlay.desiredGraphic.getTextBlock();
                                            TextGraphic textGraphic = new TextGraphic(mGraphicOverlay, lineElement);
                                            mGraphicOverlay.graphicElement = textGraphic;

                                      //  index++;
                                    }
                                }
                                }
                            };
                            t.scheduleAtFixedRate(tt, 0, 250);

//                            } else {
//                                Log.d("LineLength", "DesiredGraphic is null");
//                            }

                            if (graphicsList.size() > mGraphicOverlay.desiredGraphic.getTextBlock().getElements().size()) {
                                graphicsList.clear();
                            }
                            Log.d("graphicText", "onDone:Text:" + mGraphicOverlay.desiredGraphic.getTextBlock().getText() + " | Index" + graphicIndex);
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            isTTSComplete = true;
                            Log.d("graphicText", "onDone:mProgress is done");

                            graphicIndex++;
                            //index++;
//                            Log.d("graphicText", "Next Text:" + mGraphicOverlay.graphicsBox.get(graphicIndex).getTextBlock().getText() + " | index: " + graphicIndex);
                        }

                        @Override
                        public void onError(String utteranceId) {
                        }
                    });
                } else {
                    Log.d("TTS", "Error starting the text to speech engine ");
                }
            }
        };
        tts = new TextToSpeech(this.getApplicationContext(), listener);
        tts.setSpeechRate(2f);

    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean b = scaleGestureDetector.onTouchEvent(e);

        boolean c = gestureDetector.onTouchEvent(e);

        return b || c || super.onTouchEvent(e);
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
    }

    @Override
    public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
        final StringBuffer sb = new StringBuffer();
        for (SentenceSuggestionsInfo result : results) {
            int n = result.getSuggestionsCount();
            for (int i = 0; i < n; i++) {
                int m = result.getSuggestionsInfoAt(i).getSuggestionsCount();
                Log.d("spell", "no of suggestion:  " + m);
                int confidence = result.getSuggestionsInfoAt(i).getSuggestionsAttributes();
                Log.d("confidence", " confidence: " + confidence);
                for (int k = 0; k < m; k++) {
                    sb.append(result.getSuggestionsInfoAt(i).getSuggestionAt(k)); //TODO:Here i May Have To Use the getSuggestionAttribute() to check the confidence of spellchecker if the confidence is high we will replace the word

                }
                sb.append("\n");
                mTextRecognizer.No_Of_Suggestions = m;
                if (m <= 0) { // Meaning the word is correct
                    Log.d("spell", "Word is correct");

                } else {
                    Log.d("spell", "Suggestions:  " + sb.toString());
                }
            }
        }
    }


    public void fetchSuggestionsFor(String input) {

        if (session != null) {

            if (input != null) {
                session.getSentenceSuggestions(new TextInfo[]
                        {new TextInfo(input)}, 4);
            } else {
                Log.d("spell", " input is Null");
            }

        } else {
            Log.d("spell", "SpellCheckerSession is NULL");
        }

    }

    @Override
    public void onWindowFocusChanged(boolean hasFocas) { /** This is for fullscreen view**/
        super.onWindowFocusChanged(hasFocas);
        View decorView = getWindow().getDecorView();
        if (hasFocas) {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    /**
     * Checks whether the text that is being spoken matches with the word being displayed
     */
    void isTextMatchingWithGraphic(int indexOfTextToBeCompared) {
        String textToBeCompared = mTextRecognizer.textList.get(indexOfTextToBeCompared);
        String graphicText = mGraphicOverlay.graphicsBox.get(graphicIndex).getTextBlock().getText();
        if (graphicText.equals(textToBeCompared)) {
            Log.d("TTS", "Text Matches With The Spoken Word");
        } else {
            Log.d("TTS", "Graphic and SpokenText Not Matching. \n textSpoken:" + textToBeCompared + " | graphicText:" + graphicText);
            graphicIndex = graphicFinder(textToBeCompared);
        }
    }

    //     what if we can check that whether  a particular textBox exists at a location or not. we will first check its previous location and then compare its location with the new updated one.
    private boolean onTap(float rawX, float rawY) {

        /**  This is to protect the value of graphicIndex to Change in a loop.If this was not implemented the value of graphicIndex changes as the loop continues
         * So the intial value of the graphicIndex is only assigned when there is a new tap*/
        boolean newTap = true;

        tapCount++;
        TextGraphic graphic = mGraphicOverlay.getGraphicAtLocation(rawX, rawY);
        FirebaseVisionText.Line text = null;

        String textForSpeech = "";

        if (graphic != null) {
            Log.d("LineLength", "Graphic is Not Null ");

            text = graphic.getTextBlock();

            indexSearched = textFinder(text.getText()); // here we are searching for the text in the textList so that we can get the index of the text that we tapped on.

            /**sets isTapValid value for text Rellocation mechanism*/

                       mGraphicOverlay.isTapValid = true;
                       Log.d("isTapV", "onTap: tap  valid ");



            if (graphic != null && text.getText() != null) {
                Log.d("TTS", "text data is being spoken! " + text.getText());


                while (indexSearched <= mGraphicOverlay.totalWords && mGraphicOverlay.totalWords != 0) { // i think the first condition is failing

                    if (mTextRecognizer.textList.size() == indexSearched || indexSearched > mTextRecognizer.textList.size()) {
                        Log.d("TTS", "No Words Found In textList or there a problem in textList. | indexSearched:" + indexSearched + "textList: Size" + mTextRecognizer.textList.size());
                        break;
                    }


                    Log.d("TTS", "Next Text:" + mTextRecognizer.textList.get(indexSearched));

                    if (newTap) {
                        graphicIndex = graphicFinder(mTextRecognizer.textList.get(indexSearched));
                    }

                    // There should be a function which will regularly checks that whether the text written is equal to the text spoken.
                    tts.speak(mTextRecognizer.textList.get(indexSearched), TextToSpeech.QUEUE_ADD, null, "DEFAULT");


            //        Log.d("TTS", "onTap: indexSearched:" + indexSearched + " | Word:" + mTextRecognizer.textList.get(indexSearched) + " | textList Length:" + mTextRecognizer.textList.size() + " | Total Words:" + mGraphicOverlay.totalWords);
             //       Log.d("TTS", "textSpoken:" + mTextRecognizer.textList.get(indexSearched) + " | graphicText:" + mGraphicOverlay.graphicsBox.get(graphicIndex).getTextBlock().getText());

                    indexSearched++;

                    newTap = false;

                }

                if (!tts.isSpeaking()) {
                    Log.d("TTS", "Text To Speech Engine Is Not Busy");
                }
                Log.d("TTS", "Text:" + textForSpeech);
                Log.d("TTS", "Done with speaking");

            } else {
                Log.d("TTS", "text data is null ");
            }


        } else {
            Log.d("TTS", "No Text Detected(graphic is null) ");
        }

        return text != null;

    }

    private int textFinder(String textToBeSearched) {

        for (int i = 0; i < mTextRecognizer.textList.size(); i++) {
            if (textToBeSearched.equals(mTextRecognizer.textList.get(i))) {
                Log.d("textFinder", "textFinder: text Is Found. | TextFound:" + mTextRecognizer.textList.get(i));
                return i;
            }
            Log.d("textFinder", "textFinder: text Is not Found. | TextToBeSearched:" + textToBeSearched + " | Text At Current Index:" + mTextRecognizer.textList.get(i) + " | finderCount:" + i);
        }
        return 0;
    }

    private int graphicFinder(String graphicToBeSearched) {

        for (int i = 0; i < mGraphicOverlay.graphicsBox.size(); i++) {

            if (graphicToBeSearched.equals(mGraphicOverlay.graphicsBox.get(i).getTextBlock().getText())) {
                Log.d("graphicText", "graphicText:" + graphicToBeSearched);
                return i;
            }

            /** if (graphicToBeSearched.equals(mGraphicOverlay.graphicsBox.get(i))) {
             Log.d("graphicText", "graphicText:" + graphicToBeSearched.getTextBlock().getText());
             return i;
             }*/
            Log.d("graphicText", "graphicText:Not Found");

        }
        return 0;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(), "Application Wont run without camera service", Toast.LENGTH_SHORT).show();

            }
        }
    }



    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        //tts.stop();

        if (mTextureView.isAvailable()) {
            setupCamera(mPreviewWidth, mPreviewHeight);
            connectCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }

        mTextRecognizer.currentCount = 0;
        tapCount = 0;
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeCamera();
        stopBackgroundThread();
        tts.stop();
       // tts.shutdown();

    }

 /*   @Override
    protected void onStop() {

        super.onStop();
        tts.stop();
        tts.shutdown();
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();
        tts.stop();
        tts.shutdown();
    }
*/



    private int getRotationCompensation(String cameraId)
            throws CameraAccessException {
        // Get the device's current rotation relative to its "native" orientation.
        // Then, from the ORIENTATIONS table, look up the angle the image must be
        // rotated to compensate for the device's rotation.
        int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
        int rotationCompensation = ORIENTATIONS.get(deviceRotation);

        // On most devices, the sensor orientation is 90 degrees, but for some
        // devices it is 270 degrees. For devices with a sensor orientation of
        // 270, rotate the image an additional 180 ((270 + 270) % 360) degrees.
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        int sensorOrientation;
        sensorOrientation = cameraManager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION);
        rotationCompensation = (rotationCompensation + sensorOrientation + 270) % 360;

        // Return the corresponding FirebaseVisionImageMetadata rotation value.
        int result;
        switch (rotationCompensation) {
            case 0:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                break;
            case 90:
                result = FirebaseVisionImageMetadata.ROTATION_90;
                break;
            case 180:
                result = FirebaseVisionImageMetadata.ROTATION_180;
                break;
            case 270:
                result = FirebaseVisionImageMetadata.ROTATION_270;
                break;
            default:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                Log.e(TAG, "Bad rotation value: " + rotationCompensation);
        }
        return result;
    }

    private ByteBuffer imageToByteBuffer(final Image image) {
        final Rect crop = image.getCropRect();
        final int width = crop.width();
        final int height = crop.height();

        final Image.Plane[] planes = image.getPlanes();
        final byte[] rowData = new byte[planes[0].getRowStride()];
        final int bufferSize = mPreviewSize.getWidth() * mPreviewSize.getHeight() * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;
        final ByteBuffer output = ByteBuffer.allocateDirect(bufferSize);

        int channelOffset = 0;
        int outputStride = 0;

        for (int planeIndex = 0; planeIndex < 3; planeIndex++) {
            if (planeIndex == 0) {
                channelOffset = 0;
                outputStride = 1;
            } else if (planeIndex == 1) {
                channelOffset = width * height + 1;
                outputStride = 2;
            } else if (planeIndex == 2) {
                channelOffset = width * height;
                outputStride = 2;
            }

            final ByteBuffer buffer = planes[planeIndex].getBuffer();
            final int rowStride = planes[planeIndex].getRowStride();
            final int pixelStride = planes[planeIndex].getPixelStride();

            final int shift = (planeIndex == 0) ? 0 : 1;
            final int widthShifted = width >> shift;
            final int heightShifted = height >> shift;

            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));

            for (int row = 0; row < heightShifted; row++) {
                final int length;

                if (pixelStride == 1 && outputStride == 1) {
                    length = widthShifted;
                    buffer.get(output.array(), channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (widthShifted - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);

                    for (int col = 0; col < widthShifted; col++) {
                        output.array()[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }

                if (row < heightShifted - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
        }

        return output;
    }

    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : cameraManager.getCameraIdList()) { // here we are getting the list of camera the device have
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);//Defines the properties of a camera
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP); // this contains list of all the supported camera resoultions
                /** Size largest = Collections.max(
                 Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888))
                 , new CompareSizeByArea());  // this is similar to the camera.parameters get supported preview method**/ //this variable is only for high resolution captures

                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                int totalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    rotatedWidth = height; // i think this is creating problems with the landscape rotation as it is flips the rotation and we have already setup the location
                    rotatedHeight = width;
                }
                // mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                mPreviewSize = new Size(rotatedWidth, rotatedHeight);

                mImageReader = ImageReader.newInstance(mPreviewWidth, mPreviewHeight, ImageFormat.YUV_420_888, 1);
                mImageReader.setOnImageAvailableListener(onImageAvailableListener, mBackgroundHandler);

                // mPreviewSize = getPreferredPreviewSize(map.getOutputSizes(SurfaceTexture.class),mWidth,mHeight);
                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
                } else {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Toast.makeText(this, "This app requires access to camera", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION_RESULT);

                }
            } else {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private class CaptureGestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return onTap(e.getRawX(), e.getRawY()) || super.onSingleTapConfirmed(e);
        }
    }

    private class ScaleListener implements ScaleGestureDetector.OnScaleGestureListener {

        /**
         * Responds to scaling events for a gesture in progress.
         * Reported by pointer motion.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         * @return Whether or not the detector should consider this event
         * as handled. If an event was not handled, the detector
         * will continue to accumulate movement until an event is
         * handled. This can be useful if an application, for example,
         * only wants to update scaling factors if the change is
         * greater than 0.01.
         */
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            return false;
        }

        /**
         * Responds to the beginning of a scaling gesture. Reported by
         * new pointers going down.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         * @return Whether or not the detector should continue recognizing
         * this gesture. For example, if a gesture is beginning
         * with a focal point outside of a region where it makes
         * sense, onScaleBegin() may return false to ignore the
         * rest of the gesture.
         */
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        /**
         * Responds to the end of a scale gesture. Reported by existing
         * pointers going up.
         * <p/>
         * Once a scale has ended, {@link ScaleGestureDetector#getFocusX()}
         * and {@link ScaleGestureDetector#getFocusY()} will return focal point
         * of the pointers remaining on the screen.
         *
         * @param detector The detector reporting the event - use this to
         *                 retrieve extended info about event state.
         */
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
        }
    }

    private void startPreview() {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        Surface mImageSurface = mImageReader.getSurface();
        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            mCaptureRequestBuilder.addTarget(mImageSurface);// for image reader

            mCaptureRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        session.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                null, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(getApplicationContext(), "Unable to setup camera preview", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("Camera2Api");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation + 360) % 360;
    }


}
