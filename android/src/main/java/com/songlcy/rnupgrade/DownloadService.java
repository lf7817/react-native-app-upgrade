package com.songlcy.rnupgrade;

import com.songlcy.rnupgrade.R;
import android.app.IntentService;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.app.ActivityManager;
import android.support.v4.NotificationCompat.Builder;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class DownloadService extends IntentService {

    private static final int BUFFER_SIZE = 10 * 1024; // 8k ~ 32K
    private static final String TAG = "DownloadService";

    private static final int NOTIFICATION_ID = 0;
    private static final int DOWNLOAD_SUCCESS_NOTIFICATION_ID = 1;
    
    private NotificationManager mNotifyManager;
    private Builder mBuilder;
    private Builder mDownLoadSuccessBuilder;

    public DownloadService() {
        super("DownloadService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        mNotifyManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mBuilder = new Builder(this, "downLoadChannelId");
            NotificationChannel channel = new NotificationChannel("downLoadChannelId", "downLoadChannel", NotificationManager.IMPORTANCE_LOW);
            mNotifyManager.createNotificationChannel(channel);
        } else {
            mBuilder = new Builder(this);
        }
        
        String appName = getString(getApplicationInfo().labelRes);
        int icon = getApplicationInfo().icon;
        mBuilder.setContentTitle(appName).setSmallIcon(icon);

        String urlStr = intent.getStringExtra(Constants.APK_DOWNLOAD_URL);
        InputStream in = null;
        FileOutputStream out = null;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            urlConnection.setRequestMethod("GET");
            urlConnection.setDoOutput(false);
            urlConnection.setConnectTimeout(10 * 1000);
            urlConnection.setReadTimeout(10 * 1000);
            urlConnection.setRequestProperty("Connection", "Keep-Alive");
            urlConnection.setRequestProperty("Charset", "UTF-8");
            urlConnection.setRequestProperty("Accept-Encoding", "gzip, deflate");

            urlConnection.connect();
            long bytetotal = urlConnection.getContentLength();
            long bytesum = 0;
            int byteread = 0;
            in = urlConnection.getInputStream();
            File dir = StorageUtils.getCacheDirectory(this);
            String apkName = urlStr.substring(urlStr.lastIndexOf("/") + 1, urlStr.length());
            File apkFile = new File(dir, apkName);
            out = new FileOutputStream(apkFile);
            byte[] buffer = new byte[BUFFER_SIZE];

            int oldProgress = 0;

            while ((byteread = in.read(buffer)) != -1) {
                bytesum += byteread;
                out.write(buffer, 0, byteread);

                int progress = (int) (bytesum * 100L / bytetotal);
                // 如果进度与之前进度相等，则不更新，如果更新太频繁，否则会造成界面卡顿
                if (progress != oldProgress) {
                    updateProgress(progress);
                }
                oldProgress = progress;
            }

            // 下载完成
            installAPk(apkFile, appName, icon);
            mNotifyManager.cancel(NOTIFICATION_ID);

        } catch (Exception e) {
            Log.e(TAG, "download apk file error");
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {

                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {

                }
            }
        }
    }

    private void updateProgress(int progress) {
        // 将进度回传给RN
        UpgradeModule.sendProgress(progress);
        //"正在下载:" + progress + "%"
        mBuilder.setContentText(this.getString(R.string.android_auto_update_download_progress, progress)).setProgress(100, progress, false);
        //setContentInent如果不设置在4.0+上没有问题，在4.0以下会报异常
        PendingIntent pendingintent = PendingIntent.getActivity(this, 0, new Intent(), PendingIntent.FLAG_CANCEL_CURRENT);
        mBuilder.setContentIntent(pendingintent);
        mNotifyManager.notify(NOTIFICATION_ID, mBuilder.build());
    }

    private void installAPk(File apkFile, String appName, int icon) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String authority = getPackageName() + ".updateFileProvider";
            Uri apkUri = FileProvider.getUriForFile(this, authority, apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        } else {
            // 如果没有设置SDCard写权限，或者没有sdcard,apk文件保存在内存中，需要授予权限才能安装
            try {
                String[] command = {"chmod", "777", apkFile.toString()};
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.start();
            } catch (IOException ignored) {
            }
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        }

        if (isAppRunningForeground()) {
            startActivity(intent);
        } else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sendDownLoadSuccessNotification(apkFile, appName, icon);
        } else {
            moveAppToFront();
            startActivity(intent);
        }
    }
    
    // 判断当前App是否处于前台
    private boolean isAppRunningForeground() {
        ActivityManager activityManager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcessList = activityManager.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo it: runningAppProcessList) {
            if (it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    && getApplicationInfo().processName.equals(it.processName)
            ) {
                return true;
            }
        }
        return false;
    }

    // 切换到前台
    private void moveAppToFront() {
        //获取ActivityManager
        ActivityManager mAm = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        //获得当前运行的task
        List<ActivityManager.RunningTaskInfo> taskList = mAm.getRunningTasks(100);
        for (ActivityManager.RunningTaskInfo rti : taskList) {
            //找到当前应用的task，并启动task的栈顶activity，达到程序切换到前台
            if (rti.topActivity.getPackageName().equals(getPackageName())) {
                mAm.moveTaskToFront(rti.id, 0);
                return;
            }
        }
    }
    
    /**
     * 下载成功， 发送 Notification
     */
    private void sendDownLoadSuccessNotification(File apkFile, String appName, int icon) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mDownLoadSuccessBuilder = new Builder(this, "downLoadSuccessChannelId");
            NotificationChannel channel = new NotificationChannel("downLoadSuccessChannelId", "downLoadSuccessChannel", NotificationManager.IMPORTANCE_LOW);
            mNotifyManager.createNotificationChannel(channel);
        } else {
            mDownLoadSuccessBuilder = new Builder(this);
        }

        Intent intent = new Intent(this, ApkDonLoadSuccessReceiver.class);
        intent.putExtra("apkFile", apkFile);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, DOWNLOAD_SUCCESS_NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mDownLoadSuccessBuilder
                .setContentTitle(appName)
                .setSmallIcon(icon)
                .setContentText("下载完成")
                .setAutoCancel(true);
        mDownLoadSuccessBuilder.setContentIntent(pendingIntent);
        mNotifyManager.notify(DOWNLOAD_SUCCESS_NOTIFICATION_ID, mDownLoadSuccessBuilder.build());
    }
}
