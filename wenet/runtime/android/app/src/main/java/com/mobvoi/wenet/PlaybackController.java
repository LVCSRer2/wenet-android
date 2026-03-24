package com.mobvoi.wenet;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Wraps OggStreamPlayer with playback state management.
 * prepare() is async; all Listener callbacks are delivered on the main thread.
 */
public class PlaybackController {

  private static final String TAG = "PlaybackController";

  public interface Listener {
    void onReady(long durationMs);
    void onPositionMs(int ms);
    void onPlaybackComplete();
    void onError(String msg);
  }

  private OggStreamPlayer player;
  private String audioPath;
  private long durationMs;
  private volatile long positionMs;
  private boolean playing;
  private boolean userSeeking;
  private Listener listener;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  /** Async: prepares OggStreamPlayer off main thread, calls listener.onReady on success. */
  public void prepare(String path, Listener l) {
    this.listener = l;
    this.audioPath = path;
    new Thread(() -> {
      OggStreamPlayer p = new OggStreamPlayer(path);
      if (!p.prepare()) {
        mainHandler.post(() -> { if (listener != null) listener.onError("오디오 열기 실패"); });
        return;
      }
      long dur = p.getTotalDurationMs();
      p.setListener(new OggStreamPlayer.Listener() {
        @Override public void onPositionMs(int ms) {
          if (!userSeeking && playing) {
            positionMs = ms;
            if (listener != null) listener.onPositionMs(ms);
          }
        }
        @Override public void onPlaybackComplete() {
          playing = false;
          positionMs = 0;
          if (listener != null) listener.onPlaybackComplete();
        }
      });
      mainHandler.post(() -> {
        if (player != null) player.release();
        player = p;
        durationMs = dur;
        positionMs = 0;
        playing = false;
        if (listener != null) listener.onReady(dur);
      });
    }).start();
  }

  /** Resume playback from current positionMs. */
  public void resume() {
    if (player == null) return;
    playing = true;
    player.resume(positionMs);
  }

  /** Pause playback. Returns current position in ms. */
  public long pause() {
    if (player == null) return positionMs;
    positionMs = player.pause();
    playing = false;
    return positionMs;
  }

  /** Seek to ms, restarting playback if currently playing. */
  public void seekTo(long ms) {
    if (ms < 0) ms = 0;
    if (ms > durationMs) ms = durationMs;
    boolean wasPlaying = playing;
    if (wasPlaying) { positionMs = player.pause(); playing = false; }
    positionMs = ms;
    if (wasPlaying) { playing = true; player.resume(positionMs); }
  }

  /** Release all resources. */
  public void release() {
    if (player != null) { player.release(); player = null; }
    playing = false;
    positionMs = 0;
    durationMs = 0;
    audioPath = null;
  }

  public boolean isReady()             { return player != null; }
  public boolean isPlaying()           { return playing; }
  public long getPositionMs()          { return positionMs; }
  public long getDurationMs()          { return durationMs; }
  public String getAudioPath()         { return audioPath; }
  public void setPositionMs(long ms)   { positionMs = ms; }
  public void setUserSeeking(boolean b){ userSeeking = b; }
  public boolean isUserSeeking()       { return userSeeking; }
}
