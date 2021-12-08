package com.genymobile.scrcpy;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.*;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenImageEncoder extends ScreenEncoder {

    private static final int DEFAULT_I_FRAME_INTERVAL = 10; // seconds
    private static final int REPEAT_FRAME_DELAY_US = 100_000; // repeat after 100ms
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private static final int NO_PTS = -1;

    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    private String encoderName;
    private List<CodecOption> codecOptions;
    private int bitRate;
    private int maxFps;
    private boolean sendFrameMeta;
    private long ptsOrigin;

    public ScreenImageEncoder(boolean sendFrameMeta, int bitRate, int maxFps, List<CodecOption> codecOptions, String encoderName) {
        super(sendFrameMeta, bitRate, maxFps, codecOptions, encoderName);
    }

    public void streamScreen(Device device, FileDescriptor fd) throws IOException {
        Workarounds.prepareMainLooper();
        if (Build.BRAND.equalsIgnoreCase("meizu")) {
            // <https://github.com/Genymobile/scrcpy/issues/240>
            // <https://github.com/Genymobile/scrcpy/issues/2656>
            Workarounds.fillAppInfo();
        }

        internalStreamScreen(device, fd);
    }

    @Override
    public void internalStreamScreen(Device device, FileDescriptor fd) throws IOException {
        device.setRotationListener(this);
        try {
            IBinder display = createDisplay();
            ScreenInfo screenInfo = device.getScreenInfo();
            Rect contentRect = screenInfo.getContentRect();
            // include the locked video orientation
            Rect videoRect = screenInfo.getVideoSize().toRect();
            // does not include the locked video orientation
            Rect unlockedVideoRect = screenInfo.getUnlockedVideoSize().toRect();
            int videoRotation = screenInfo.getVideoRotation();
            int layerStack = device.getLayerStack();
            ImageReader reader = ImageReader.newInstance(videoRect.width(), videoRect.height(), ImageFormat.JPEG, 1);
            setDisplaySurface(display,reader.getSurface(),videoRotation,contentRect,unlockedVideoRect,layerStack);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                byte[] framePrefix = new byte[]{(byte) 0xFF, 0, (byte) 0xFF, 0, 0, (byte) 0xFF, 0, 0, 0, (byte) 0xFF, 0, 0, 0, 0, 0, (byte) 0xFF,};

                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    Image.Plane[] planes = image.getPlanes();
                    if (planes != null && planes.length > 0) {
                        ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * image.getWidth();
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        Bitmap sourceBitmap = Bitmap.createBitmap(videoRect.width() + rowPadding / pixelStride, videoRect.height(),
                                Bitmap.Config.ARGB_8888);
                        sourceBitmap.copyPixelsFromBuffer(buffer);
                        Bitmap cropBitmap = Bitmap.createBitmap(sourceBitmap, 0, 0, videoRect.width(), videoRect.height());
                        cropBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                        sourceBitmap.recycle();
                        cropBitmap.recycle();
                        //bitmap size + frame header + width + height
                        ByteBuffer codecBuffer = ByteBuffer.allocate(outputStream.size()+framePrefix.length+16);
                        codecBuffer.put(framePrefix);
                        codecBuffer.putLong(cropBitmap.getWidth());
                        codecBuffer.putLong(cropBitmap.getHeight());
                        //bitmap copyPixelsToBuffer unsupport pos > 0
                        codecBuffer.put(outputStream.toByteArray());
                    }
                }
            }, handler);
        } finally {
            device.setRotationListener(null);
        }
    }

}
