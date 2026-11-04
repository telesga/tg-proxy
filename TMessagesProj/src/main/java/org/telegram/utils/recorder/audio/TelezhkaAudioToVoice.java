/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.utils.recorder.audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * "Отправить голосовым": turns any audio file the phone can play (mp3, m4a, ogg, wav, flac…)
 * into a Telegram voice message — mono 48 kHz Ogg/Opus written by the same native encoder
 * the voice recorder uses (one encoder per call, so it never clashes with a recording).
 */
public final class TelezhkaAudioToVoice {

    public static final int SAMPLE_RATE = AudioCapture.SAMPLE_RATE;
    private static final long TIMEOUT_US = 10_000;
    private static final int CHUNK_SAMPLES = 960 * 20;

    public static final class Result {
        public final long durationMs;
        @Nullable
        public final byte[] waveform;

        Result(long durationMs, @Nullable byte[] waveform) {
            this.durationMs = durationMs;
            this.waveform = waveform;
        }
    }

    public interface Cancel {
        boolean isCanceled();
    }

    private TelezhkaAudioToVoice() {
    }

    /** Waveform of a ready Ogg/Opus file, the same one recorded voices get. */
    @Nullable
    public static byte[] waveformOf(@NonNull File opusFile) {
        return AudioRecorderNative.getWaveform(opusFile.getAbsolutePath());
    }

    /** Decodes {@code inputPath} and writes the voice message to {@code output}. Throws when the file has no playable audio. */
    @WorkerThread
    @NonNull
    public static Result convert(@NonNull String inputPath, @NonNull File output, @Nullable Cancel cancel) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        AudioRecorderNative encoder = null;
        boolean success = false;
        try {
            extractor.setDataSource(inputPath);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = trackFormat;
                    break;
                }
            }
            if (track < 0) {
                throw new IllegalArgumentException("No audio track in " + inputPath);
            }
            extractor.selectTrack(track);

            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();

            encoder = AudioRecorderNative.create(output, SAMPLE_RATE, null, 0, 0);
            if (encoder == null) {
                throw new IllegalStateException("Unable to initialize native Opus encoder");
            }

            Pcm pcm = new Pcm(encoder);
            pcm.setInput(
                    format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SAMPLE_RATE,
                    format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (cancel != null && cancel.isCanceled()) {
                    throw new InterruptedException("Canceled");
                }
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuffer = decoder.getInputBuffer(inIndex);
                        int size = inBuffer == null ? -1 : extractor.readSampleData(inBuffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = decoder.getOutputFormat();
                    int encoding = AudioFormat.ENCODING_PCM_16BIT;
                    if (Build.VERSION.SDK_INT >= 24 && outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    pcm.setInput(
                            outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : pcm.inputRate,
                            outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : pcm.channels,
                            encoding
                    );
                } else if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuffer = decoder.getOutputBuffer(outIndex);
                        if (outBuffer != null) {
                            outBuffer.position(info.offset);
                            outBuffer.limit(info.offset + info.size);
                            pcm.push(outBuffer.slice().order(ByteOrder.nativeOrder()));
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            pcm.flush();
            if (pcm.written == 0) {
                throw new IllegalArgumentException("No audio decoded from " + inputPath);
            }
            AudioRecorderNative finishing = encoder;
            encoder = null;
            if (!finishing.finish()) {
                throw new IllegalStateException("Native Opus encoder failed to finish");
            }
            long durationMs = pcm.written * 1000L / SAMPLE_RATE;
            byte[] waveform = AudioRecorderNative.getWaveform(output.getAbsolutePath());
            success = true;
            return new Result(durationMs, waveform);
        } finally {
            if (encoder != null) {
                encoder.recycle();
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignore) {
                }
                try {
                    decoder.release();
                } catch (Exception ignore) {
                }
            }
            try {
                extractor.release();
            } catch (Exception ignore) {
            }
            if (!success) {
                //noinspection ResultOfMethodCallIgnored
                output.delete();
            }
        }
    }

    /** Mixes decoded PCM down to mono, resamples it to 48 kHz (linear) and feeds the encoder in chunks. */
    private static final class Pcm {
        private final AudioRecorderNative encoder;
        private final ByteBuffer chunk = ByteBuffer.allocateDirect(CHUNK_SAMPLES * 2).order(ByteOrder.nativeOrder());
        private int inputRate = SAMPLE_RATE;
        private int channels = 1;
        private int encoding = AudioFormat.ENCODING_PCM_16BIT;
        private double step = 1;
        // input-sample time of the next output sample, index of the last input sample and its value
        private double next = 0;
        private long index = -1;
        private float previous;
        long written;

        Pcm(AudioRecorderNative encoder) {
            this.encoder = encoder;
        }

        void setInput(int rate, int channelCount, int pcmEncoding) {
            inputRate = rate > 0 ? rate : SAMPLE_RATE;
            channels = Math.max(1, channelCount);
            encoding = pcmEncoding;
            step = inputRate / (double) SAMPLE_RATE;
        }

        void push(ByteBuffer buffer) throws Exception {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                FloatBuffer floats = buffer.asFloatBuffer();
                int frames = floats.remaining() / channels;
                for (int f = 0; f < frames; f++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) {
                        sum += floats.get();
                    }
                    resample(sum / channels);
                }
            } else {
                ShortBuffer shorts = buffer.asShortBuffer();
                int frames = shorts.remaining() / channels;
                for (int f = 0; f < frames; f++) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) {
                        sum += shorts.get();
                    }
                    resample(sum / (channels * 32768f));
                }
            }
        }

        private void resample(float current) throws Exception {
            index++;
            if (index == 0) {
                previous = current;
            }
            while (next <= index) {
                double fraction = next - (index - 1);
                emit((float) (previous + (current - previous) * fraction));
                next += step;
            }
            previous = current;
        }

        private void emit(float value) throws Exception {
            if (value > 1f) {
                value = 1f;
            } else if (value < -1f) {
                value = -1f;
            }
            chunk.putShort((short) (value * 32767f));
            written++;
            if (!chunk.hasRemaining()) {
                write();
            }
        }

        void flush() throws Exception {
            if (chunk.position() > 0) {
                write();
            }
        }

        private void write() throws Exception {
            int bytes = chunk.position();
            chunk.position(0);
            chunk.limit(bytes);
            if (encoder.writeFrame(chunk, bytes) == 0) {
                throw new IllegalStateException("Native Opus encoding failed");
            }
            chunk.clear();
        }
    }
}
