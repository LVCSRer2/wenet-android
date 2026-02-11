package com.mobvoi.wenet;

import android.content.Context;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingManager {

  private static final String RECORDINGS_DIR = "recordings";

  private static File getRecordingsRoot(Context context) {
    File root = new File(context.getFilesDir(), RECORDINGS_DIR);
    if (!root.exists()) {
      root.mkdirs();
    }
    return root;
  }

  /** Create a new recording directory with timestamp name, return the directory name. */
  public static String createRecordingDir(Context context) {
    String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    File dir = new File(getRecordingsRoot(context), name);
    dir.mkdirs();
    return name;
  }

  /** List recording names sorted newest first. */
  public static List<String> listRecordings(Context context) {
    File root = getRecordingsRoot(context);
    String[] names = root.list();
    if (names == null || names.length == 0) {
      return Collections.emptyList();
    }
    List<String> list = new ArrayList<>(Arrays.asList(names));
    Collections.sort(list, Collections.reverseOrder());
    return list;
  }

  public static String getAudioPath(Context context, String name) {
    return new File(new File(getRecordingsRoot(context), name), "audio.pcm").getAbsolutePath();
  }

  public static String getResultPath(Context context, String name) {
    return new File(new File(getRecordingsRoot(context), name), "result.json").getAbsolutePath();
  }

  public static void deleteRecording(Context context, String name) {
    File dir = new File(getRecordingsRoot(context), name);
    if (dir.exists() && dir.isDirectory()) {
      File[] files = dir.listFiles();
      if (files != null) {
        for (File f : files) {
          f.delete();
        }
      }
      dir.delete();
    }
  }
}
