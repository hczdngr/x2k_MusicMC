package com.xkitme.onlinemusic;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.text.Text;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

public final class OnlineMusicPlayer implements AutoCloseable {
	private static final int STREAM_BUFFER_SIZE = 64 * 1024;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(12);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

	private final Object lock = new Object();
	private final Deque<Track> queue = new ArrayDeque<>();
	private final Consumer<Text> statusSink;
	private final DoubleSupplier gameVolumeSupplier;
	private final Thread workerThread;

	private volatile boolean closed;
	private volatile boolean stoppingCurrent;
	private volatile float volume = 0.75f;
	private volatile Track currentTrack;
	private volatile PlaybackSession activeSession;

	public OnlineMusicPlayer(Consumer<Text> statusSink) {
		this(statusSink, () -> 1.0);
	}

	public OnlineMusicPlayer(Consumer<Text> statusSink, DoubleSupplier gameVolumeSupplier) {
		this.statusSink = statusSink;
		this.gameVolumeSupplier = gameVolumeSupplier;
		this.workerThread = new Thread(this::runWorker, "x2k Music Player");
		this.workerThread.setDaemon(true);
		this.workerThread.start();
	}

	public void playNow(Track track) {
		stopCurrentOnly();
		synchronized (lock) {
			queue.clear();
			queue.addFirst(track);
			lock.notifyAll();
		}
	}

	public void queue(Track track) {
		synchronized (lock) {
			queue.addLast(track);
			lock.notifyAll();
		}
	}

	public boolean skip() {
		if (currentTrack == null) {
			return false;
		}
		stopCurrentOnly();
		return true;
	}

	public boolean stopAll() {
		boolean hadWork;
		synchronized (lock) {
			hadWork = currentTrack != null || !queue.isEmpty();
			queue.clear();
		}
		stopCurrentOnly();
		return hadWork;
	}

	public Track currentTrack() {
		return currentTrack;
	}

	public List<Track> queuedTracks() {
		synchronized (lock) {
			return new ArrayList<>(queue);
		}
	}

	public void setVolumePercent(int percent) {
		this.volume = Math.max(0.0f, Math.min(1.0f, percent / 100.0f));
	}

	public int volumePercent() {
		return Math.round(volume * 100.0f);
	}

	@Override
	public void close() {
		closed = true;
		synchronized (lock) {
			queue.clear();
			lock.notifyAll();
		}
		stopCurrentOnly();
	}

	private void runWorker() {
		while (!closed) {
			Track next = takeNextTrack();
			if (next == null) {
				continue;
			}

			currentTrack = next;
			stoppingCurrent = false;
			statusSink.accept(Messenger.success("正在播放：" + next.title()));

			try {
				playMp3(next);
				if (!stoppingCurrent && !closed) {
					statusSink.accept(Messenger.info("播放结束：" + next.title()));
				}
			} catch (Exception exception) {
				if (!stoppingCurrent && !closed) {
					statusSink.accept(Messenger.error("播放失败：" + exception.getMessage()));
				}
			} finally {
				PlaybackSession session = activeSession;
				if (session != null) {
					session.close();
				}
				activeSession = null;
				currentTrack = null;
				stoppingCurrent = false;
			}
		}
	}

	private Track takeNextTrack() {
		synchronized (lock) {
			while (!closed && queue.isEmpty()) {
				try {
					lock.wait();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
			return closed ? null : queue.removeFirst();
		}
	}

	private void stopCurrentOnly() {
		stoppingCurrent = true;
		PlaybackSession session = activeSession;
		if (session != null) {
			session.close();
		}
	}

	private void playMp3(Track track) throws IOException, JavaLayerException, LineUnavailableException {
		PlaybackSession session = new PlaybackSession();
		activeSession = session;

		try (InputStream rawInput = openStream(track.url());
			 BufferedInputStream input = new BufferedInputStream(rawInput, STREAM_BUFFER_SIZE)) {
			session.input = input;
			Bitstream bitstream = new Bitstream(input);
			session.bitstream = bitstream;
			Decoder decoder = new Decoder();
			byte[] pcmBytes = new byte[0];
			AudioFormat currentFormat = null;
			double decodedMillis = 0.0;

			while (!closed && !stoppingCurrent) {
				Header header = bitstream.readFrame();
				if (header == null) {
					break;
				}

				try {
					float frameMillis = header.ms_per_frame();
					boolean shouldOutput = decodedMillis + frameMillis >= track.startOffsetMillis();
					SampleBuffer sampleBuffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
					AudioFormat frameFormat = new AudioFormat(sampleBuffer.getSampleFrequency(), 16,
							sampleBuffer.getChannelCount(), true, false);

					if (shouldOutput && !sameFormat(currentFormat, frameFormat)) {
						session.replaceLine(openLine(frameFormat));
						currentFormat = frameFormat;
					}

					int byteCount = toPcmBytes(sampleBuffer, pcmBytes);
					if (pcmBytes.length < byteCount) {
						pcmBytes = new byte[byteCount];
						byteCount = toPcmBytes(sampleBuffer, pcmBytes);
					}

					SourceDataLine line = session.line;
					if (shouldOutput && line != null) {
						line.write(pcmBytes, 0, byteCount);
					}
					decodedMillis += frameMillis;
				} finally {
					bitstream.closeFrame();
				}
			}

			SourceDataLine line = session.line;
			if (line != null && !stoppingCurrent && !closed) {
				line.drain();
			}
		}
	}

	private InputStream openStream(String url) throws IOException {
		URLConnection connection = URI.create(url).toURL().openConnection();
		connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
		connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
		connection.setRequestProperty("User-Agent", "NeteaseMusic/9.3.40.1753206443(164);Dalvik/2.1.0 (Linux; U; Android 9)");
		connection.setRequestProperty("Accept", "*/*");
		connection.setRequestProperty("Referer", "https://music.163.com/");
		connection.setRequestProperty("Connection", "close");
		return connection.getInputStream();
	}

	private SourceDataLine openLine(AudioFormat format) throws LineUnavailableException {
		SourceDataLine line = AudioSystem.getSourceDataLine(format);
		line.open(format);
		line.start();
		return line;
	}

	private boolean sameFormat(AudioFormat left, AudioFormat right) {
		return left != null
				&& left.getSampleRate() == right.getSampleRate()
				&& left.getChannels() == right.getChannels()
				&& left.getSampleSizeInBits() == right.getSampleSizeInBits()
				&& left.isBigEndian() == right.isBigEndian();
	}

	private int toPcmBytes(SampleBuffer sampleBuffer, byte[] target) {
		short[] samples = sampleBuffer.getBuffer();
		int sampleCount = sampleBuffer.getBufferLength();
		int requiredLength = sampleCount * 2;
		if (target.length < requiredLength) {
			return requiredLength;
		}

		float gain = volume * gameVolume();
		int byteIndex = 0;
		for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
			int sample = Math.round(samples[sampleIndex] * gain);
			sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
			target[byteIndex++] = (byte) (sample & 0xFF);
			target[byteIndex++] = (byte) ((sample >>> 8) & 0xFF);
		}
		return requiredLength;
	}

	private float gameVolume() {
		try {
			return Math.max(0.0f, Math.min(1.0f, (float) gameVolumeSupplier.getAsDouble()));
		} catch (RuntimeException exception) {
			return 1.0f;
		}
	}

	private static final class PlaybackSession {
		private volatile InputStream input;
		private volatile Bitstream bitstream;
		private volatile SourceDataLine line;

		private void replaceLine(SourceDataLine replacement) {
			SourceDataLine oldLine = line;
			line = replacement;
			if (oldLine != null) {
				oldLine.stop();
				oldLine.close();
			}
		}

		private void close() {
			SourceDataLine currentLine = line;
			line = null;
			if (currentLine != null) {
				currentLine.stop();
				currentLine.close();
			}

			Bitstream currentBitstream = bitstream;
			bitstream = null;
			if (currentBitstream != null) {
				try {
					currentBitstream.close();
				} catch (BitstreamException ignored) {
					// Closing is best-effort during stop/skip.
				}
			}

			InputStream currentInput = input;
			input = null;
			if (currentInput != null) {
				try {
					currentInput.close();
				} catch (IOException ignored) {
					// Closing is best-effort during stop/skip.
				}
			}
		}
	}
}
