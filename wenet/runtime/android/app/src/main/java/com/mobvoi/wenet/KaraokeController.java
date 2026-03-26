package com.mobvoi.wenet;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Manages karaoke-style highlighted playback text.
 * Owns WordSpan list, SpannableStringBuilder, and highlight state.
 */
public class KaraokeController {

  public static class WordSpan {
    public final String word;
    public final int startMs;
    public final int endMs;
    WordSpan(String word, int startMs, int endMs) {
      this.word = word; this.startMs = startMs; this.endMs = endMs;
    }
  }

  private List<WordSpan> wordSpans = null;
  private SpannableStringBuilder karaokeSSB = null;
  private int[] karaokeSpanStarts = null;
  private int[] karaokeSpanEnds = null;
  private int lastHighlightIndex = -1;
  private BackgroundColorSpan currentBgSpan = null;
  private ForegroundColorSpan currentFgSpan = null;

  /** Load word spans from result.json for the given recording. */
  public void load(Context context, String recordingName) {
    wordSpans = new ArrayList<>();
    try {
      String resultPath = RecordingManager.getResultPath(context, recordingName);
      File f = new File(resultPath);
      if (!f.exists()) return;
      FileInputStream fis = new FileInputStream(f);
      byte[] data = new byte[(int) f.length()];
      fis.read(data);
      fis.close();
      JSONArray arr = new JSONArray(new String(data, "UTF-8"));
      int lastEndMs = 0;
      for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        String word = obj.getString("w");
        int start = obj.getInt("s");
        int end = obj.getInt("e");
        if (start == 0 && end == 0 && i > 0) { start = lastEndMs; end = lastEndMs; }
        wordSpans.add(new WordSpan(word, start, end));
        if (end > lastEndMs) lastEndMs = end;
      }
    } catch (Exception e) {
      android.util.Log.e("KaraokeController", "load error: " + e.getMessage());
    }
  }

  /** Build SpannableStringBuilder and set on textView. */
  public void buildText(TextView textView, long startOfDayMs) {
    if (wordSpans == null || wordSpans.isEmpty()) return;

    karaokeSSB = new SpannableStringBuilder();
    karaokeSpanStarts = new int[wordSpans.size()];
    karaokeSpanEnds = new int[wordSpans.size()];
    lastHighlightIndex = -1;
    currentBgSpan = null;
    currentFgSpan = null;

    int sentenceStartMs = -1;
    boolean needNewLine = false;
    for (int i = 0; i < wordSpans.size(); i++) {
      WordSpan ws = wordSpans.get(i);
      if ("\n".equals(ws.word)) {
        if (sentenceStartMs != -1) { sentenceStartMs = -1; needNewLine = true; }
        karaokeSpanStarts[i] = karaokeSSB.length();
        karaokeSpanEnds[i] = karaokeSSB.length();
        continue;
      }
      if (sentenceStartMs == -1) {
        if (needNewLine) karaokeSSB.append("\n");
        sentenceStartMs = ws.startMs;
        karaokeSSB.append("[").append(formatTimeMs((int)(sentenceStartMs + startOfDayMs))).append("] ");
      }
      if ("\u2581".equals(ws.word)) {
        karaokeSpanStarts[i] = karaokeSSB.length();
        karaokeSSB.append(" ");
        karaokeSpanEnds[i] = karaokeSSB.length();
      } else {
        karaokeSpanStarts[i] = karaokeSSB.length();
        karaokeSSB.append(ws.word);
        karaokeSpanEnds[i] = karaokeSSB.length();
      }
    }
    textView.setText(karaokeSSB, TextView.BufferType.SPANNABLE);
  }

  /** Update highlight span in-place (no setText). Called ~50ms. */
  public void updateHighlight(int currentMs, TextView textView, ScrollView scrollView) {
    if (wordSpans == null || wordSpans.isEmpty()) return;
    CharSequence cs = textView.getText();
    if (!(cs instanceof android.text.Spannable)) return;
    android.text.Spannable spannable = (android.text.Spannable) cs;

    // Binary search: word where startMs <= currentMs < endMs
    int currentIndex = -1;
    int lo = 0, hi = wordSpans.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) / 2;
      WordSpan ws = wordSpans.get(mid);
      if (currentMs < ws.startMs) { hi = mid - 1; }
      else if (currentMs >= ws.endMs) { lo = mid + 1; }
      else { currentIndex = mid; break; }
    }
    // Fallback: last word with startMs <= currentMs
    if (currentIndex == -1 && currentMs > 0) {
      lo = 0; hi = wordSpans.size() - 1;
      while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (wordSpans.get(mid).startMs <= currentMs) { currentIndex = mid; lo = mid + 1; }
        else { hi = mid - 1; }
      }
    }

    if (currentIndex == lastHighlightIndex) return;

    // Skip invisible tokens (newline, space)
    if (currentIndex >= 0) {
      int orig = currentIndex;
      while (currentIndex >= 0 &&
          (karaokeSpanEnds[currentIndex] - karaokeSpanStarts[currentIndex] <= 0
           || "\u2581".equals(wordSpans.get(currentIndex).word))) {
        currentIndex--;
      }
      if (currentIndex < 0 && orig >= 0) currentIndex = -1;
    }

    if (currentIndex == lastHighlightIndex) return;
    if (currentBgSpan != null) spannable.removeSpan(currentBgSpan);
    if (currentFgSpan != null) spannable.removeSpan(currentFgSpan);

    if (currentIndex >= 0) {
      currentBgSpan = new BackgroundColorSpan(Color.parseColor("#FFFF00"));
      currentFgSpan = new ForegroundColorSpan(Color.BLACK);
      spannable.setSpan(currentBgSpan,
          karaokeSpanStarts[currentIndex], karaokeSpanEnds[currentIndex],
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      spannable.setSpan(currentFgSpan,
          karaokeSpanStarts[currentIndex], karaokeSpanEnds[currentIndex],
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      // Auto-scroll
      if (scrollView != null && textView.getLayout() != null) {
        int line = textView.getLayout().getLineForOffset(karaokeSpanStarts[currentIndex]);
        int y = textView.getLayout().getLineTop(line);
        scrollView.smoothScrollTo(0, Math.max(0, y - scrollView.getHeight() / 3));
      }
    } else {
      currentBgSpan = null;
      currentFgSpan = null;
    }
    lastHighlightIndex = currentIndex;
  }

  /** Binary search: word whose span contains the given char offset. */
  public int findWordAtCharOffset(int offset) {
    if (karaokeSpanStarts == null || karaokeSpanEnds == null) return -1;
    int lo = 0, hi = karaokeSpanStarts.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) / 2;
      if (karaokeSpanEnds[mid] <= offset) { lo = mid + 1; }
      else if (karaokeSpanStarts[mid] > offset) { hi = mid - 1; }
      else { return mid; }
    }
    return -1;
  }

  /** Build timestamped text from loaded word spans. */
  public String buildTimestampedText(long startOfDayMs) {
    return buildTimestampedTextFromSpans(wordSpans, startOfDayMs);
  }

  /** Build timestamped text from a WordSpan list. */
  public static String buildTimestampedTextFromSpans(List<WordSpan> spans, long startOfDayMs) {
    if (spans == null || spans.isEmpty()) return "";
    StringBuilder result = new StringBuilder();
    StringBuilder sentence = new StringBuilder();
    int sentenceStartMs = -1;
    for (WordSpan ws : spans) {
      if ("\n".equals(ws.word)) {
        if (sentence.toString().trim().length() > 0) {
          result.append("[").append(formatTimeMs((int)(sentenceStartMs + startOfDayMs))).append("] ")
              .append(sentence.toString().trim()).append("\n");
          sentence = new StringBuilder();
          sentenceStartMs = -1;
        }
        continue;
      }
      if (sentenceStartMs == -1) sentenceStartMs = ws.startMs;
      if ("\u2581".equals(ws.word)) sentence.append(" ");
      else sentence.append(ws.word);
    }
    if (sentence.toString().trim().length() > 0) {
      result.append("[").append(formatTimeMs((int)(sentenceStartMs + startOfDayMs))).append("] ")
          .append(sentence.toString().trim()).append("\n");
    }
    return result.toString().trim();
  }

  /** Build timestamped text from a timed-result JSON string. */
  public static String buildTimestampedTextFromJson(String timedJson, long startOfDayMs) {
    try {
      JSONArray arr = new JSONArray(timedJson);
      if (arr.length() == 0) return "";
      StringBuilder result = new StringBuilder();
      StringBuilder sentence = new StringBuilder();
      int sentenceStartMs = -1;
      for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        String w = obj.getString("w");
        int startMs = obj.getInt("s");
        if ("\n".equals(w)) {
          if (sentence.toString().trim().length() > 0) {
            result.append("[").append(formatTimeMs((int)(sentenceStartMs + startOfDayMs))).append("] ")
                .append(sentence.toString().trim()).append("\n");
            sentence = new StringBuilder();
            sentenceStartMs = -1;
          }
          continue;
        }
        if (sentenceStartMs == -1) sentenceStartMs = startMs;
        if ("\u2581".equals(w)) sentence.append(" ");
        else sentence.append(w);
      }
      if (sentence.toString().trim().length() > 0) {
        result.append("[").append(formatTimeMs((int)(sentenceStartMs + startOfDayMs))).append("] ")
            .append(sentence.toString().trim()).append("\n");
      }
      return result.toString().trim();
    } catch (Exception e) {
      android.util.Log.e("KaraokeController", "buildTimestampedTextFromJson error: " + e.getMessage());
      return "";
    }
  }

  public List<WordSpan> getWordSpans() { return wordSpans; }

  public static String formatTimeMs(int ms) {
    int totalSec = ms / 1000;
    int h = totalSec / 3600;
    int m = (totalSec % 3600) / 60;
    int s = totalSec % 60;
    return String.format("%02d:%02d:%02d", h, m, s);
  }
}
