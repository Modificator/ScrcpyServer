package com.genymobile.scrcpy;

import android.os.*;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ScreenEncoder implements Device.RotationListener {

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
    protected Handler handler;

    {
        HandlerThread handlerThread = new HandlerThread("ScreenEncoder");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    public ScreenEncoder(boolean sendFrameMeta, int bitRate, int maxFps, List<CodecOption> codecOptions, String encoderName) {
        this.sendFrameMeta = sendFrameMeta;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.codecOptions = codecOptions;
        this.encoderName = encoderName;
    }

    @Override
    public void onRotationChanged(int rotation) {
        rotationChanged.set(true);
    }

    public boolean consumeRotationChange() {
        return rotationChanged.getAndSet(false);
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

    protected abstract void internalStreamScreen(Device device, FileDescriptor fd) throws IOException;

    private static MediaCodecInfo[] listEncoders() {
        List<MediaCodecInfo> result = new ArrayList<>();
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo codecInfo : list.getCodecInfos()) {
            if (codecInfo.isEncoder() && Arrays.asList(codecInfo.getSupportedTypes()).contains(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                result.add(codecInfo);
            }
        }
        return result.toArray(new MediaCodecInfo[result.size()]);
    }

    private static MediaCodec createCodec(String encoderName) throws IOException {
        if (encoderName != null) {
            Ln.d("Creating encoder by name: '" + encoderName + "'");
            try {
                return MediaCodec.createByCodecName(encoderName);
            } catch (IllegalArgumentException e) {
                MediaCodecInfo[] encoders = listEncoders();
                throw new InvalidEncoderException(encoderName, encoders);
            }
        }
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        Ln.d("Using encoder: '" + codec.getName() + "'");
        return codec;
    }

    private static void setCodecOption(MediaFormat format, CodecOption codecOption) {
        String key = codecOption.getKey();
        Object value = codecOption.getValue();

        if (value instanceof Integer) {
            format.setInteger(key, (Integer) value);
        } else if (value instanceof Long) {
            format.setLong(key, (Long) value);
        } else if (value instanceof Float) {
            format.setFloat(key, (Float) value);
        } else if (value instanceof String) {
            format.setString(key, (String) value);
        }

        Ln.d("Codec option set: " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
    }

    protected MediaFormat createFormat(int bitRate, int maxFps, List<CodecOption> codecOptions) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
        // display the very first frame, and recover from bad quality when no new frames
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US); // µs
        if (maxFps > 0) {
            // The key existed privately before Android 10:
            // <https://android.googlesource.com/platform/frameworks/base/+/625f0aad9f7a259b6881006ad8710adce57d1384%5E%21/>
            // <https://github.com/Genymobile/scrcpy/issues/488#issuecomment-567321437>
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }

        if (codecOptions != null) {
            for (CodecOption option : codecOptions) {
                setCodecOption(format, option);
            }
        }

        return format;
    }

    protected IBinder createDisplay() {
        // Since Android 12 (preview), secure displays could not be created with shell permissions anymore.
        // On Android 12 preview, SDK_INT is still R (not S), but CODENAME is "S".
        boolean secure = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S"
                .equals(Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void setSize(MediaFormat format, int width, int height) {
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
    }

    protected void setDisplaySurface(IBinder display, Surface surface, int orientation, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, orientation, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    protected void destroyDisplay(IBinder display) {
        SurfaceControl.destroyDisplay(display);
    }
}
