package cn.projectb.terminal;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera acquisition only. Image recognition and protocol handling execute in C. */
@SuppressWarnings("deprecation")
public final class CameraScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    static { System.loadLibrary("wms_protocol"); }
    public static native byte[] nativeDecodeQr(byte[] luma, int width, int height);
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private Camera camera;
    private SurfaceView preview;
    private TextView status;
    private volatile boolean active, completed;
    private volatile int generation;
    private int width, height;
    private long lastFrame;
    private boolean permissionRequested;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(18,62,74));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(18,62,74));
        status = new TextView(this);
        status.setTextColor(Color.WHITE); status.setTextSize(17);
        status.setPadding(24,24,24,24);
        status.setText("将电脑上的测试二维码对准画面\n识别后返回并保存一条扫码；不自动确认收货");
        layout.addView(status);
        preview = new SurfaceView(this);
        preview.getHolder().addCallback(this);
        preview.setOnClickListener(v -> focus());
        layout.addView(preview, new LinearLayout.LayoutParams(-1,0,1));
        Button cancel = new Button(this); cancel.setText("取消扫码，返回终端");
        cancel.setOnClickListener(v -> finish()); layout.addView(cancel);
        setContentView(layout);
    }
    @Override protected void onResume() {
        super.onResume(); active = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested=true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 201);
            }
        } else openCamera();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 201) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) openCamera();
            else status.setText("未获得摄像头权限，可返回后手动输入标签。需要扫码时请允许本应用使用相机。");
        }
    }
    @Override protected void onPause() {
        active = false; generation++; closeCamera(); super.onPause();
    }
    @Override protected void onDestroy() { decoder.shutdownNow(); super.onDestroy(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { openCamera(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { generation++; closeCamera(); }

    private void openCamera() {
        if (!active || completed || camera != null || !preview.getHolder().getSurface().isValid()
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int id = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i=0;i<Camera.getNumberOfCameras();i++) {
                Camera.getCameraInfo(i,info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id=i;break; }
            }
            if (id < 0) throw new IllegalStateException("没有可用后置摄像头");
            camera = Camera.open(id);
            Camera.getCameraInfo(id,info);
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = p.getPreviewSize();
            long score = Long.MAX_VALUE;
            for (Camera.Size candidate:p.getSupportedPreviewSizes()) {
                if (candidate.width>1280 || candidate.height>1280 || candidate.width<320 || candidate.height<240) continue;
                long value = Math.abs((long)candidate.width*candidate.height-640L*480);
                if (value<score) { best=candidate;score=value; }
            }
            width=best.width; height=best.height;
            p.setPreviewSize(width,height); p.setPreviewFormat(ImageFormat.NV21);
            List<String> modes=p.getSupportedFocusModes();
            if (modes!=null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if (modes!=null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(p);
            int rotation=getWindowManager().getDefaultDisplay().getRotation();
            int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            camera.setDisplayOrientation((info.orientation-degrees+360)%360);
            camera.setPreviewDisplay(preview.getHolder());
            camera.setPreviewCallback(this); camera.startPreview(); focus();
        } catch (Exception e) {
            closeCamera(); status.setText("相机启动失败："+e.getMessage()+"\n请关闭其他使用相机的应用后重试。");
        }
    }
    private void focus() {
        if (camera==null) return;
        try { camera.cancelAutoFocus();camera.autoFocus((success,c)->{}); } catch (RuntimeException ignored) { }
    }
    private void closeCamera() {
        if (camera==null) return;
        Camera old=camera;camera=null;
        try { old.setPreviewCallback(null);old.stopPreview(); } catch (RuntimeException ignored) { }
        old.release();
    }
    static String supportedLabel(byte[] bytes) {
        if (bytes==null || bytes.length<1 || bytes.length>96) return null;
        for (byte b:bytes) if (b<32 || b>126) return null;
        return new String(bytes,StandardCharsets.US_ASCII);
    }
    @Override public void onPreviewFrame(byte[] frame, Camera source) {
        long now=SystemClock.elapsedRealtime();
        if (!active || completed || frame==null || now-lastFrame<200 || !busy.compareAndSet(false,true)) return;
        lastFrame=now;
        final int w=width,h=height,g= generation;
        if (frame.length<w*h) { busy.set(false);return; }
        final byte[] luma=Arrays.copyOf(frame,w*h);
        try { decoder.execute(()->{
            byte[] bytes=null;
            try { bytes=nativeDecodeQr(luma,w,h); } finally { busy.set(false); }
            if (bytes==null) return;
            final String label=supportedLabel(bytes);
            runOnUiThread(()->{
                if (!active || completed || generation!=g) return;
                if (label==null) {
                    status.setText("二维码已识别，但标签需为1–96位可打印ASCII字符。请使用提供的测试二维码。");
                    return;
                }
                completed=true;closeCamera();
                setResult(RESULT_OK,new Intent().putExtra("SCAN_TEXT",label));finish();
            });
        }); } catch (RejectedExecutionException e) { busy.set(false); }
    }
}
