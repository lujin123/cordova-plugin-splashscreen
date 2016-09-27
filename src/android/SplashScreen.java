/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/

package org.apache.cordova.splashscreen;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nordnetab.chcp.main.utils.URLConnectionHelper;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

public class SplashScreen extends CordovaPlugin {
    private static final String LOG_TAG = "SplashScreen";
    // Cordova 3.x.x has a copy of this plugin bundled with it (SplashScreenInternal.java).
    // Enable functionality only if running on 4.x.x.
    private static final boolean HAS_BUILT_IN_SPLASH_SCREEN = Integer.valueOf(CordovaWebView.CORDOVA_VERSION.split("\\.")[0]) < 4;
    private static final int DEFAULT_SPLASHSCREEN_DURATION = 3000;
    private static Dialog splashDialog;
    private static ProgressDialog spinnerDialog;
    private static boolean firstShow = true;
    private static boolean lastHideAfterDelay; // https://issues.apache.org/jira/browse/CB-9094

    private static final String PLUGIN_FOLDER = "cordova-plugin-splashscreen";

    private static final String SPLASH_NAME = "splash.png";

    private static final String SPLASH_JSON_NAME = "splash-android.json";

    /**
     * Displays the splash drawable.
     */
    private ImageView splashImageView;

    private Drawable screenDrawable = null;


    private JsonDownloader jsonDownloader;

    private String densityName = null;

    private Context context;

    /**
     * Remember last device orientation to detect orientation changes.
     */
    private int orientation;

    // Helper to be compile-time compatible with both Cordova 3.x and 4.x.
    private View getView() {
        try {
            return (View) webView.getClass().getMethod("getView").invoke(webView);
        } catch (Exception e) {
            return (View) webView;
        }
    }


    private Bitmap downloadSplashImage(String url) {
        if (url == null) {
            return null;
        }
        Bitmap bitmap = null;
        try {
            InputStream is = new URL(url).openStream();
            bitmap = BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bitmap;
    }

    private void saveSplashImage(Bitmap bitmap) throws IOException {
        String absPath = context.getFilesDir().getAbsolutePath();
        String pluginPath = getPath(absPath, PLUGIN_FOLDER);
        File pluginFile = new File(pluginPath);
        if (!pluginFile.exists()) {
            pluginFile.mkdirs();
        }
        File splashFile = new File(pluginPath, SPLASH_NAME);
        if (!splashFile.exists()) {
            splashFile.createNewFile();
        }

        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(splashFile));
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos);

        bos.flush();
        bos.close();
    }

    /**
     * 保存splash-android.json文件
     *
     * @param splashJson String
     * @throws IOException
     */
    private void saveSplashFile(String splashJson) throws IOException {
        FileOutputStream fos = context.openFileOutput(SPLASH_JSON_NAME, Context.MODE_PRIVATE);
        fos.write(splashJson.getBytes());
        fos.flush();
        fos.close();
    }

    /**
     * 读取splash-android.json文件
     *
     * @return String
     * @throws IOException
     */
    private String getSplashFile() throws IOException {
        FileInputStream fis = context.openFileInput(SPLASH_JSON_NAME);//获得输入流
        //用来获得内存缓冲区的数据，转换成字节数组
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = fis.read(buffer)) != -1) {
            stream.write(buffer, 0, length);//获取内存缓冲区中的数据
        }
        stream.close(); //关闭
        fis.close();
        return stream.toString();

    }

    /**
     * 比较两个json文件的对应的选项是否有不同
     *
     * @param newFile JsonNode
     * @param oldFile JsonNode
     * @return boolean: 不同返回true,否则返回false
     */
    private boolean diffSplashFile(JsonNode newFile, JsonNode oldFile) {
        boolean flag = false;
        String newImgUrl = getDensityUrl(newFile, densityName);
        String oldImgUrl = getDensityUrl(oldFile, densityName);
        if (oldImgUrl != null && !oldImgUrl.equals(newImgUrl)) {
            flag = true;
        }

        return flag;
    }

    /**
     * 解析json文件
     * @param json json内容
     * @param densityName 待取字段
     * @return string 字段中的内容
     */
    public static String getDensityUrl(JsonNode json, String densityName) {
        try {
            JsonNode contents = json.get("contents");
            JsonNode content = contents.get(0);
            JsonNode params = content.get("params");
            return params.get(densityName).asText();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 走一个线程，用于处理耗时操作，这里用于下载图片
     *
     * @param jsonUrl String
     */
    private void runThread(final String jsonUrl) {
        new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    if (densityName == null) {
                        densityName = getDensityName(context);
                        Log.d(LOG_TAG, "run: densityName=" + densityName);
                    }
                    if (jsonDownloader == null) {
                        jsonDownloader = new JsonDownloader(jsonUrl, null);
                    }

                    String jsonContent = jsonDownloader.downloadJson();

                    JsonNode json = new ObjectMapper().readTree(jsonContent);
                    boolean flag = true;
                    try {
                        String oldJson = getSplashFile();
                        JsonNode oldJsonNode = new ObjectMapper().readTree(oldJson);
                        flag = diffSplashFile(json, oldJsonNode);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    Log.d(LOG_TAG, "pluginInitialize flag: " + flag);

                    if (flag) {
                        saveSplashFile(jsonContent);
                        String imgUrl = SplashScreen.getDensityUrl(json, densityName);
                        Bitmap bitmap = downloadSplashImage(imgUrl);
                        if (bitmap != null) {
                            saveSplashImage(bitmap);
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }


    @Override
    protected void pluginInitialize() {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // Make WebView invisible while loading URL
        getView().setVisibility(View.INVISIBLE);
        int drawableId = preferences.getInteger("SplashDrawableId", 0);
        if (drawableId == 0) {
            context = cordova.getActivity();
            String jsonUrl = preferences.getString("SplashScreenContentUrl", null);
            String splashResource = preferences.getString("SplashScreen", "screen");

            if (jsonUrl != null && splashResource != null) {
                runThread(jsonUrl);

                String absPath = context.getFilesDir().getAbsolutePath();
                String splashPath = getPath(absPath, PLUGIN_FOLDER, SPLASH_NAME);

                File splashFile = new File(splashPath);
                if (splashFile.exists()) {
                    screenDrawable = getDrawableFromPath(splashPath);
                }

                if (densityName == null) {
                    densityName = getDensityName(context);
                }

                String packageName = context.getClass().getPackage().getName();
                drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable", packageName);
                if (drawableId == 0) {
                    packageName = context.getPackageName();
                    drawableId = cordova.getActivity().getResources().getIdentifier(splashResource, "drawable", packageName);
                }
                preferences.set("SplashDrawableId", drawableId);
            }
        }

        // Save initial orientation.
        orientation = cordova.getActivity().getResources().getConfiguration().orientation;

        if (firstShow) {
            boolean autoHide = preferences.getBoolean("AutoHideSplashScreen", true);
            showSplashScreen(autoHide);
        }

        if (preferences.getBoolean("SplashShowOnlyFirstTime", true)) {
            firstShow = false;
        }
    }


    private Drawable getDrawableFromPath(String imgPath) {
        Bitmap screenImg = BitmapFactory.decodeFile(imgPath);
        return new BitmapDrawable(screenImg).getCurrent();
    }

    private String getDensityName(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        if (density >= 4.0) {
            return "xxxhdpi";
        } else if (density >= 3.0) {
            return "xxhdpi";
        } else if (density >= 2.0) {
            return "xhdpi";
        } else if (density >= 1.5) {
            return "hdpi";
        } else if (density >= 1.0) {
            return "mdpi";
        } else {
            return "ldpi";
        }
    }

    /**
     * Shorter way to check value of "SplashMaintainAspectRatio" preference.
     */
    private boolean isMaintainAspectRatio() {
        return preferences.getBoolean("SplashMaintainAspectRatio", false);
    }

    private int getFadeDuration() {
        int fadeSplashScreenDuration = preferences.getBoolean("FadeSplashScreen", true) ?
            preferences.getInteger("FadeSplashScreenDuration", DEFAULT_SPLASHSCREEN_DURATION) : 0;

        if (fadeSplashScreenDuration < 30) {
            // [CB-9750] This value used to be in decimal seconds, so we will assume that if someone specifies 10
            // they mean 10 seconds, and not the meaningless 10ms
            fadeSplashScreenDuration *= 1000;
        }

        return fadeSplashScreenDuration;
    }

    @Override
    public void onPause(boolean multitasking) {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // hide the splash screen to avoid leaking a window
        this.removeSplashScreen(true);
    }

    @Override
    public void onDestroy() {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return;
        }
        // hide the splash screen to avoid leaking a window
        this.removeSplashScreen(true);
        // If we set this to true onDestroy, we lose track when we go from page to page!
        //firstShow = true;
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("hide")) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.postMessage("splashscreen", "hide");
                }
            });
        } else if (action.equals("show")) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    webView.postMessage("splashscreen", "show");
                }
            });
        } else {
            return false;
        }

        callbackContext.success();
        return true;
    }

    @Override
    public Object onMessage(String id, Object data) {
        if (HAS_BUILT_IN_SPLASH_SCREEN) {
            return null;
        }
        if ("splashscreen".equals(id)) {
            if ("hide".equals(data.toString())) {
                this.removeSplashScreen(false);
            } else {
                this.showSplashScreen(false);
            }
        } else if ("spinner".equals(id)) {
            if ("stop".equals(data.toString())) {
                getView().setVisibility(View.VISIBLE);
            }
        } else if ("onReceivedError".equals(id)) {
            this.spinnerStop();
        }
        return null;
    }

    // Don't add @Override so that plugin still compiles on 3.x.x for a while
    public void onConfigurationChanged(Configuration newConfig) {
        if (newConfig.orientation != orientation) {
            orientation = newConfig.orientation;

            // Splash drawable may change with orientation, so reload it.
            if (splashImageView != null) {
                // TODO: 2016/8/28 custom
                if (screenDrawable != null) {
                    splashImageView.setImageDrawable(screenDrawable);
                } else {
                    int drawableId = preferences.getInteger("SplashDrawableId", 0);
                    if (drawableId != 0) {
                        splashImageView.setImageDrawable(cordova.getActivity().getResources().getDrawable(drawableId));
                    }
                }
            }
        }
    }

    private void removeSplashScreen(final boolean forceHideImmediately) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                if (splashDialog != null && splashDialog.isShowing()) {
                    final int fadeSplashScreenDuration = getFadeDuration();
                    // CB-10692 If the plugin is being paused/destroyed, skip the fading and hide it immediately
                    if (fadeSplashScreenDuration > 0 && forceHideImmediately == false) {
                        AlphaAnimation fadeOut = new AlphaAnimation(1, 0);
                        fadeOut.setInterpolator(new DecelerateInterpolator());
                        fadeOut.setDuration(fadeSplashScreenDuration);

                        splashImageView.setAnimation(fadeOut);
                        splashImageView.startAnimation(fadeOut);

                        fadeOut.setAnimationListener(new Animation.AnimationListener() {
                            @Override
                            public void onAnimationStart(Animation animation) {
                                spinnerStop();
                            }

                            @Override
                            public void onAnimationEnd(Animation animation) {
                                if (splashDialog != null && splashDialog.isShowing()) {
                                    splashDialog.dismiss();
                                    splashDialog = null;
                                    splashImageView = null;
                                }
                            }

                            @Override
                            public void onAnimationRepeat(Animation animation) {
                            }
                        });
                    } else {
                        spinnerStop();
                        splashDialog.dismiss();
                        splashDialog = null;
                        splashImageView = null;
                    }
                }
            }
        });
    }

    /**
     * Shows the splash screen over the full Activity
     */
    @SuppressWarnings("deprecation")
    private void showSplashScreen(final boolean hideAfterDelay) {
        final int splashscreenTime = preferences.getInteger("SplashScreenDelay", DEFAULT_SPLASHSCREEN_DURATION);
        final int drawableId = preferences.getInteger("SplashDrawableId", 0);

        final int fadeSplashScreenDuration = getFadeDuration();
        final int effectiveSplashDuration = Math.max(0, splashscreenTime - fadeSplashScreenDuration);

        lastHideAfterDelay = hideAfterDelay;

        // If the splash dialog is showing don't try to show it again
        if (splashDialog != null && splashDialog.isShowing()) {
            return;
        }
        if (drawableId == 0 || (splashscreenTime <= 0 && hideAfterDelay)) {
            return;
        }

        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                // Get reference to display
                Display display = cordova.getActivity().getWindowManager().getDefaultDisplay();
                Context context = webView.getContext();

                // Use an ImageView to render the image because of its flexible scaling options.
                splashImageView = new ImageView(context);
                // TODO: 2016/8/28
                if (screenDrawable != null) {
                    splashImageView.setImageDrawable(screenDrawable);
                } else {
                    splashImageView.setImageResource(drawableId);
                }
                LayoutParams layoutParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                splashImageView.setLayoutParams(layoutParams);

                splashImageView.setMinimumHeight(display.getHeight());
                splashImageView.setMinimumWidth(display.getWidth());

                // TODO: Use the background color of the webView's parent instead of using the preference.
                splashImageView.setBackgroundColor(preferences.getInteger("backgroundColor", Color.BLACK));

                if (isMaintainAspectRatio()) {
                    // CENTER_CROP scale mode is equivalent to CSS "background-size:cover"
                    splashImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                } else {
                    // FIT_XY scales image non-uniformly to fit into image view.
                    splashImageView.setScaleType(ImageView.ScaleType.FIT_XY);
                }

                // Create and show the dialog
                splashDialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
                // check to see if the splash screen should be full screen
                if ((cordova.getActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    == WindowManager.LayoutParams.FLAG_FULLSCREEN) {
                    splashDialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
                splashDialog.setContentView(splashImageView);
                splashDialog.setCancelable(false);
                splashDialog.show();

                if (preferences.getBoolean("ShowSplashScreenSpinner", true)) {
                    spinnerStart();
                }

                // Set Runnable to remove splash screen just in case
                if (hideAfterDelay) {
                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            if (lastHideAfterDelay) {
                                removeSplashScreen(false);
                            }
                        }
                    }, effectiveSplashDuration);
                }
            }
        });
    }

    // Show only spinner in the center of the screen
    private void spinnerStart() {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                spinnerStop();

                spinnerDialog = new ProgressDialog(webView.getContext());
                spinnerDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        spinnerDialog = null;
                    }
                });

                spinnerDialog.setCancelable(false);
                spinnerDialog.setIndeterminate(true);

                RelativeLayout centeredLayout = new RelativeLayout(cordova.getActivity());
                centeredLayout.setGravity(Gravity.CENTER);
                centeredLayout.setLayoutParams(new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

                ProgressBar progressBar = new ProgressBar(webView.getContext());
                RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                progressBar.setLayoutParams(layoutParams);

                centeredLayout.addView(progressBar);

                spinnerDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                spinnerDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

                spinnerDialog.show();
                spinnerDialog.setContentView(centeredLayout);
            }
        });
    }

    private void spinnerStop() {
        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                if (spinnerDialog != null && spinnerDialog.isShowing()) {
                    spinnerDialog.dismiss();
                    spinnerDialog = null;
                }
            }
        });
    }

    /**
     * Construct path from the given set of paths.
     *
     * @param paths list of paths to concat
     * @return resulting path
     */
    public static String getPath(String... paths) {
        StringBuilder builder = new StringBuilder();
        for (String path : paths) {
            builder.append(normalizeDashes(path));
        }

        return builder.toString();
    }

    private static String normalizeDashes(String path) {
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

}

class JsonDownloader {
    private final String downloadUrl;
    private final Map<String, String> requestHeaders;

    public JsonDownloader(final String url, final Map<String, String> requestHeaders) {
        this.downloadUrl = url;
        this.requestHeaders = requestHeaders;
    }

    public String downloadJson() throws Exception {
        final StringBuilder jsonContent = new StringBuilder();
        final URLConnection urlConnection = URLConnectionHelper.createConnectionToURL(downloadUrl, requestHeaders);
        final InputStreamReader streamReader = new InputStreamReader(urlConnection.getInputStream());
        final BufferedReader bufferedReader = new BufferedReader(streamReader);

        final char data[] = new char[1024];
        int count;
        while ((count = bufferedReader.read(data)) != -1) {
            jsonContent.append(data, 0, count);
        }
        bufferedReader.close();

        return jsonContent.toString();
    }
}
