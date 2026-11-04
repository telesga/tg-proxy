/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import static org.telegram.messenger.LocaleController.getString;

import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.utils.recorder.audio.TelezhkaAudioToVoice;

import java.io.File;
import java.util.Locale;

/**
 * "Отправить кружком" and "Отправить голосовым" in the send menu of the attach sheet (hold the send button):
 * - a video from the gallery or files goes out as a round message: centre square, up to a minute,
 *   through the usual video converter (a crop matrix, like stories do), so it is cut and squeezed on the phone;
 * - an audio file goes out as a voice message: decoded and re-encoded to Ogg/Opus with a waveform.
 */
public class TelezhkaMediaAs {

    public static final long ROUND_MAX_MS = 60_000;

    private static final String[] AUDIO_EXTENSIONS = {"mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3ga", "mka", "weba"};
    private static final String[] VIDEO_EXTENSIONS = {"mp4", "m4v", "mov", "3gp", "mkv", "webm"};

    private static DispatchQueue queue;

    private static DispatchQueue queue() {
        if (queue == null) {
            queue = new DispatchQueue("telezhkaMediaAs");
        }
        return queue;
    }

    public static boolean isAudioPath(String path) {
        return hasExtension(path, AUDIO_EXTENSIONS);
    }

    public static boolean isVideoPath(String path) {
        return hasExtension(path, VIDEO_EXTENSIONS);
    }

    private static boolean hasExtension(String path, String[] extensions) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return false;
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : extensions) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    // Round videos and voices share the "voice messages" privacy setting of the other person
    private static boolean forbiddenByPrivacy(ChatActivity fragment) {
        TLRPC.UserFull userFull = fragment.getCurrentUserInfo();
        return userFull != null && userFull.voice_messages_forbidden;
    }

    public static boolean canSendRound(ChatActivity fragment) {
        return fragment != null && ChatObject.canSendRoundVideo(fragment.getCurrentChat()) && !forbiddenByPrivacy(fragment);
    }

    public static boolean canSendVoice(ChatActivity fragment) {
        return fragment != null && ChatObject.canSendVoice(fragment.getCurrentChat()) && !forbiddenByPrivacy(fragment);
    }

    // ---------------- Video -> round ----------------

    /** {@code edited} is what the user did to the video in the editor: only the trimming is taken. */
    public static void sendVideoAsRound(ChatActivity fragment, String path, VideoEditedInfo edited, boolean notify, int scheduleDate) {
        if (fragment == null || TextUtils.isEmpty(path)) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final long editedStart = edited != null ? edited.startTime : -1;
        final long editedEnd = edited != null ? edited.endTime : -1;
        queue().postRunnable(() -> {
            boolean[] trimmed = new boolean[1];
            VideoEditedInfo info = buildRoundInfo(account, path, editedStart, editedEnd, trimmed);
            AndroidUtilities.runOnUIThread(() -> {
                if (info == null) {
                    BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.TelezhkaRoundFailed)).show();
                    return;
                }
                fragment.telezhkaSendRound(path, info, notify, scheduleDate);
                if (trimmed[0]) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, getString(R.string.TelezhkaRoundTrimmed)).show();
                }
            });
        });
    }

    private static VideoEditedInfo buildRoundInfo(int account, String path, long editedStartUs, long editedEndUs, boolean[] trimmed) {
        int width, height, rotation, sourceBitrate;
        long durationMs;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            width = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            rotation = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            sourceBitrate = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            durationMs = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
        if (width <= 0 || height <= 0 || durationMs <= 0) {
            return null;
        }
        // The decoder hands over frames already turned upright, so the frame is measured upright too
        if (rotation == 90 || rotation == 270) {
            int swap = width;
            width = height;
            height = swap;
        }

        long startUs = editedStartUs > 0 ? editedStartUs : 0;
        long endUs = durationMs * 1000L;
        if (editedEndUs > 0 && editedEndUs < endUs) {
            endUs = editedEndUs;
        }
        if (endUs <= startUs) {
            return null;
        }
        if (endUs - startUs > ROUND_MAX_MS * 1000L) {
            endUs = startUs + ROUND_MAX_MS * 1000L;
            trimmed[0] = true;
        }

        MessagesController controller = MessagesController.getInstance(account);
        int side = Math.max(240, Math.round(controller.roundVideoSize / 16f) * 16);
        int bitrate = Math.max(300, controller.roundVideoBitrate) * 1024;

        // Fill the square: scale the upright frame to cover it and keep the centre
        float scale = Math.max(side / (float) width, side / (float) height);
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postTranslate((side - width * scale) / 2f, (side - height * scale) / 2f);

        VideoEditedInfo info = new VideoEditedInfo();
        info.roundVideo = true;
        info.originalPath = path;
        info.originalWidth = width;
        info.originalHeight = height;
        info.resultWidth = side;
        info.resultHeight = side;
        info.rotationValue = 0;
        info.framerate = 30;
        info.bitrate = bitrate;
        info.originalBitrate = sourceBitrate > 0 ? sourceBitrate : -1;
        info.originalDuration = durationMs * 1000L;
        info.startTime = startUs;
        info.endTime = endUs;
        info.estimatedDuration = (endUs - startUs) / 1000L;
        info.estimatedSize = Math.max(1, (long) (info.estimatedDuration / 1000f * (bitrate + 64 * 1024) / 8));
        info.muted = false;
        info.cropState = new MediaController.CropState();
        info.cropState.useMatrix = matrix;
        info.cropState.transformWidth = side;
        info.cropState.transformHeight = side;
        return info;
    }

    // ---------------- Audio -> voice ----------------

    public static void sendAudioAsVoice(ChatActivity fragment, String path, boolean notify, int scheduleDate) {
        if (fragment == null || TextUtils.isEmpty(path) || !new File(path).exists()) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.voice_mini, getString(R.string.TelezhkaVoicePreparing)).show();
        queue().postRunnable(() -> {
            TLRPC.TL_document document = new TLRPC.TL_document();
            document.file_reference = new byte[0];
            document.dc_id = Integer.MIN_VALUE;
            document.id = SharedConfig.getLastLocalId();
            document.user_id = UserConfig.getInstance(account).getClientUserId();
            document.mime_type = "audio/ogg";
            SharedConfig.saveConfig();

            File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO);
            if (dir == null || !dir.exists() && !dir.mkdirs()) {
                dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            }
            File output = new File(dir, System.currentTimeMillis() + "_" + FileLoader.getAttachFileName(document));

            long durationMs;
            byte[] waveform;
            try {
                if (MediaController.isOpusFile(path) == 1 && AndroidUtilities.copyFile(new File(path), output)) {
                    // already a voice-style Ogg/Opus file: send a copy as it is
                    durationMs = opusDurationMs(output);
                    waveform = TelezhkaAudioToVoice.waveformOf(output);
                } else {
                    TelezhkaAudioToVoice.Result result = TelezhkaAudioToVoice.convert(path, output, null);
                    durationMs = result.durationMs;
                    waveform = result.waveform;
                }
            } catch (Throwable e) {
                FileLog.e(e);
                output.delete();
                AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.TelezhkaVoiceConvertFailed)).show());
                return;
            }

            final long finalDuration = durationMs;
            final byte[] finalWaveform = waveform;
            AndroidUtilities.runOnUIThread(() -> {
                document.date = ConnectionsManager.getInstance(account).getCurrentTime();
                document.size = output.length();
                TLRPC.TL_documentAttributeAudio attribute = new TLRPC.TL_documentAttributeAudio();
                attribute.voice = true;
                attribute.waveform = finalWaveform;
                if (finalWaveform != null) {
                    attribute.flags |= 4;
                }
                attribute.duration = finalDuration / 1000.0;
                document.attributes.add(attribute);
                fragment.telezhkaSendVoice(document, output.getAbsolutePath(), notify, scheduleDate);
            });
        });
    }

    private static long opusDurationMs(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return Math.max(0, parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
    }

    private static int parseInt(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return (int) Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(value.trim());
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }
    }
}
