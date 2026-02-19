package com.mobvoi.wenet;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

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

  /** Load recognition text from result.json (words joined). */
  public static String loadResultText(Context context, String name) {
    try {
      File f = new File(getResultPath(context, name));
      if (!f.exists()) return "";
      FileInputStream fis = new FileInputStream(f);
      byte[] data = new byte[(int) f.length()];
      fis.read(data);
      fis.close();
      JSONArray arr = new JSONArray(new String(data, "UTF-8"));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < arr.length(); i++) {
        String w = arr.getJSONObject(i).getString("w");
        if ("\u2581".equals(w)) {
          sb.append(" ");
        } else {
          sb.append(w);
        }
      }
      return sb.toString().trim();
    } catch (Exception e) {
      return "";
    }
  }

  public static class SearchResult {
    public final String name;
    public final String preview;
    public SearchResult(String name, String preview) {
      this.name = name;
      this.preview = preview;
    }
  }

  /** Search recordings by keyword. Returns all if keyword is empty. */
  public static List<SearchResult> searchRecordings(Context context, String keyword) {
    List<String> all = listRecordings(context);
    List<SearchResult> results = new ArrayList<>();
    String kw = (keyword != null) ? keyword.trim().toLowerCase(Locale.ROOT) : "";
    for (String name : all) {
      String text = loadResultText(context, name);
      if (kw.isEmpty()) {
        String preview = text.length() > 60 ? text.substring(0, 60) + "..." : text;
        results.add(new SearchResult(name, preview.isEmpty() ? "(no text)" : preview));
      } else if (text.toLowerCase(Locale.ROOT).contains(kw)) {
        int idx = text.toLowerCase(Locale.ROOT).indexOf(kw);
        int start = Math.max(0, idx - 20);
        int end = Math.min(text.length(), idx + kw.length() + 40);
        String preview = (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
        results.add(new SearchResult(name, preview));
      }
    }
    return results;
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
