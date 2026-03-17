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

  public static String getPcmAudioPath(Context context, String name) {
    return getAudioPath(context, name);
  }

  public static String getResultPath(Context context, String name) {
    return new File(new File(getRecordingsRoot(context), name), "result.json").getAbsolutePath();
  }

  public static String getOpusPath(Context context, String name) {
    return new File(new File(getRecordingsRoot(context), name), "audio.ogg").getAbsolutePath();
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
    public final long durationMs; // 0 if unknown
    public SearchResult(String name, String preview, long durationMs) {
      this.name = name;
      this.preview = preview;
      this.durationMs = durationMs;
    }
    /** Legacy compat */
    public SearchResult(String name, String preview) {
      this(name, preview, 0);
    }
  }

  /** Search recordings by keyword. Returns all if keyword is empty.
   *  Also loads duration from PCM file size or OGG metadata. Safe to call on a background thread. */
  public static List<SearchResult> searchRecordings(Context context, String keyword) {
    List<String> all = listRecordings(context);
    List<SearchResult> results = new ArrayList<>();
    String kw = (keyword != null) ? keyword.trim().toLowerCase(Locale.ROOT) : "";
    for (String name : all) {
      String text = loadResultText(context, name);
      boolean matches = kw.isEmpty() || text.toLowerCase(Locale.ROOT).contains(kw);
      if (!matches) continue;

      String preview;
      if (kw.isEmpty()) {
        preview = text.length() > 60 ? text.substring(0, 60) + "..." : text;
        if (preview.isEmpty()) preview = "(no text)";
      } else {
        int idx = text.toLowerCase(Locale.ROOT).indexOf(kw);
        int start = Math.max(0, idx - 20);
        int end = Math.min(text.length(), idx + kw.length() + 40);
        preview = (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
      }

      long durationMs = loadDurationMs(context, name);
      results.add(new SearchResult(name, preview, durationMs));
    }
    return results;
  }

  /** Returns duration in ms. Tries PCM file size first, then OGG metadata. */
  public static long loadDurationMs(Context context, String name) {
    File pcm = new File(getAudioPath(context, name));
    if (pcm.exists() && pcm.length() > 0) {
      // 8000 Hz mono 16-bit = 16000 bytes/sec
      return pcm.length() * 1000L / 16000L;
    }
    File ogg = new File(getOpusPath(context, name));
    if (ogg.exists()) {
      try {
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        mmr.setDataSource(ogg.getAbsolutePath());
        String durStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
        mmr.release();
        if (durStr != null) return Long.parseLong(durStr);
      } catch (Exception ignored) {}
    }
    return 0;
  }

  /** Rename a recording directory. Returns new name on success, null on failure. */
  public static String renameRecording(Context context, String oldName, String newName) {
    File oldDir = new File(getRecordingsRoot(context), oldName);
    File newDir = new File(getRecordingsRoot(context), newName);
    if (!oldDir.exists() || newDir.exists()) return null;
    return oldDir.renameTo(newDir) ? newName : null;
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
