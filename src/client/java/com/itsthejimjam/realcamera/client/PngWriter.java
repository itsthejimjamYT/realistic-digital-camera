package com.itsthejimjam.realcamera.client;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.itsthejimjam.realcamera.PhotoMode;
import com.mojang.blaze3d.platform.NativeImage;

/**
 * A small, self-owned 8-bit RGBA PNG encoder — used instead of vanilla
 * {@code NativeImage.writeToFile} so every saved photo can carry a real EXIF block
 * (shutter / aperture / ISO / exposure bias). {@code NativeImage} / {@code Screenshot}
 * have no metadata hook at all, which is why Lightroom's Photo Merge → HDR couldn't
 * recognize a bracket set as one: it reads exactly these tags to group and order frames.
 */
public final class PngWriter {

	private PngWriter() {
	}

	private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

	/** The handful of camera settings a real photo's EXIF block would carry. */
	public record Exif(double shutterSeconds, float aperture, int iso, float exposureBiasEv, long timestampMillis) {
	}

	/** Write an 8-bit RGBA PNG with an embedded EXIF ({@code eXIf}) chunk. */
	public static void write(File file, NativeImage image, Exif exif) throws IOException {
		int w = image.getWidth();
		int h = image.getHeight();
		byte[] raw = new byte[h * (1 + w * 4)];
		int o = 0;
		for (int y = 0; y < h; y++) {
			raw[o++] = 0; // filter type: None (the whole scanline stream still gets deflated)
			for (int x = 0; x < w; x++) {
				int p = image.getPixel(x, y);
				raw[o++] = (byte) ((p >> 16) & 0xFF); // R
				raw[o++] = (byte) ((p >> 8) & 0xFF);  // G
				raw[o++] = (byte) (p & 0xFF);          // B
				raw[o++] = (byte) ((p >> 24) & 0xFF); // A
			}
		}

		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
			out.write(SIGNATURE);
			writeChunk(out, "IHDR", ihdr(w, h));
			byte[] exifBlob = buildExif(exif);
			if (exifBlob != null) {
				writeChunk(out, "eXIf", exifBlob);
			}
			writeChunk(out, "IDAT", deflate(raw));
			writeChunk(out, "IEND", new byte[0]);
		}
	}

	/**
	 * Write a 16-bit-per-channel RGB PNG from raw RGBA16_FLOAT bytes — tightly packed,
	 * bottom-up, native (little-endian) byte order, exactly what {@link HdrCapture}'s GPU
	 * readback hands off. No destructive tone-mapping happened upstream (see the
	 * {@code expose.fsh} shader) — this just maps the roughly 0..{@code headroom} linear-
	 * ish range onto the full 16-bit range, so nominal "white" (1.0) lands with real
	 * recoverable headroom above it instead of at the ceiling.
	 */
	public static void write16(File file, int width, int height, byte[] rgba16f, Exif exif) throws IOException {
		final int bytesPerPixel = 8; // RGBA16_FLOAT
		int rowBytes = width * bytesPerPixel;
		byte[] raw = new byte[height * (1 + width * 6)];
		int o = 0;
		for (int outY = 0; outY < height; outY++) {
			raw[o++] = 0; // filter type: None
			int srcY = height - 1 - outY; // GPU texture is bottom-up; PNG rows are top-down
			int rowBase = srcY * rowBytes;
			for (int x = 0; x < width; x++) {
				int px = rowBase + x * bytesPerPixel;
				int r16 = encode16(halfToFloat(rgba16f, px), HEADROOM);
				int g16 = encode16(halfToFloat(rgba16f, px + 2), HEADROOM);
				int b16 = encode16(halfToFloat(rgba16f, px + 4), HEADROOM);
				raw[o++] = (byte) (r16 >>> 8);
				raw[o++] = (byte) r16;
				raw[o++] = (byte) (g16 >>> 8);
				raw[o++] = (byte) g16;
				raw[o++] = (byte) (b16 >>> 8);
				raw[o++] = (byte) b16;
			}
		}

		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
			out.write(SIGNATURE);
			writeChunk(out, "IHDR", ihdr(width, height, 16, 2));
			byte[] exifBlob = buildExif(exif);
			if (exifBlob != null) {
				writeChunk(out, "eXIf", exifBlob);
			}
			writeChunk(out, "IDAT", deflate(raw));
			writeChunk(out, "IEND", new byte[0]);
		}
	}

	/** Linear scene value -> the 16-bit range, mapping 0..headroom onto 0..65535 so
	 *  nominal "white" (1.0) lands with real recoverable headroom above it. Real highlight
	 *  headroom is preserved above "nominal white" by expose.fsh's soft knee (asymptotes
	 *  toward ~3.5, but in practice rarely gets close) — 1.3 keeps most of the 16-bit range
	 *  for the actual 0..1 image while still leaving recoverable room above it, rather than
	 *  spreading across a mostly-empty 0..3.5. Shared by {@link #write16} (straight GPU
	 *  readback) and {@link #write16FromFloatRgb} (an {@link HdrMerge} result) — both need
	 *  the exact same mapping or the two kinds of 16-bit file would carry different tone
	 *  scales. */
	private static final float HEADROOM = 1.3f;

	private static int encode16(float linear, float headroom) {
		float v = Math.max(0.0f, Math.min(headroom, linear)) / headroom;
		return Math.round(v * 65535.0f);
	}

	/**
	 * Write a 16-bit-per-channel RGB PNG from already-decoded, top-down float RGB (e.g.
	 * an {@link HdrMerge} result) — unlike {@link #write16}, no half-float decode and no
	 * bottom-up-to-top-down row flip, since the caller already did both.
	 */
	public static void write16FromFloatRgb(File file, int width, int height, float[] rgb, Exif exif)
			throws IOException {
		byte[] raw = new byte[height * (1 + width * 6)];
		int o = 0;
		for (int y = 0; y < height; y++) {
			raw[o++] = 0; // filter type: None
			int rowBase = y * width * 3;
			for (int x = 0; x < width; x++) {
				int px = rowBase + x * 3;
				int r16 = encode16(rgb[px], HEADROOM);
				int g16 = encode16(rgb[px + 1], HEADROOM);
				int b16 = encode16(rgb[px + 2], HEADROOM);
				raw[o++] = (byte) (r16 >>> 8);
				raw[o++] = (byte) r16;
				raw[o++] = (byte) (g16 >>> 8);
				raw[o++] = (byte) g16;
				raw[o++] = (byte) (b16 >>> 8);
				raw[o++] = (byte) b16;
			}
		}

		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
			out.write(SIGNATURE);
			writeChunk(out, "IHDR", ihdr(width, height, 16, 2));
			byte[] exifBlob = buildExif(exif);
			if (exifBlob != null) {
				writeChunk(out, "eXIf", exifBlob);
			}
			writeChunk(out, "IDAT", deflate(raw));
			writeChunk(out, "IEND", new byte[0]);
		}
	}

	/** Decode a little-endian IEEE 754 binary16 half-float at the given byte offset
	 *  (public-domain bit-twiddling algorithm — avoids a pow() call per channel per
	 *  pixel, which matters here: an 8K frame is ~40M pixels). Package-private so
	 *  {@link HdrMerge} can reuse the exact same decode instead of a second copy. */
	static float halfToFloat(byte[] b, int off) {
		int hbits = (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
		int mant = hbits & 0x03FF;
		int exp = hbits & 0x7C00;
		if (exp == 0x7C00) {
			exp = 0x3FC00; // Inf/NaN
		} else if (exp != 0) {
			exp += 0x1C000; // normalized
		} else if (mant != 0) {
			// subnormal half -> normalize
			exp = 0x1C400;
			do {
				mant <<= 1;
				exp -= 0x400;
			} while ((mant & 0x400) == 0);
			mant &= 0x3FF;
		}
		int bits = ((hbits & 0x8000) << 16) | ((exp | mant) << 13);
		return Float.intBitsToFloat(bits);
	}

	/**
	 * Write an 8-bit RGB JPEG with a standard APP1/EXIF segment — the format every real
	 * camera actually outputs for a bracket, and the one Lightroom's Photo Merge -> HDR
	 * reliably recognizes. PNG's eXIf chunk turned out not to be enough in practice
	 * (Photo Merge apparently doesn't treat PNG as an eligible source at all, regardless
	 * of embedded metadata) — used for bracket frames specifically; the normal single
	 * photo stays PNG (lossless, no cross-file merge to worry about).
	 */
	public static void writeJpeg(File file, NativeImage image, Exif exif, float quality) throws IOException {
		int w = image.getWidth();
		int h = image.getHeight();
		BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				buffered.setRGB(x, y, image.getPixel(x, y));
			}
		}

		ByteArrayOutputStream jpegBytes = new ByteArrayOutputStream();
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
		if (!writers.hasNext()) {
			throw new IOException("no JPEG writer available");
		}
		ImageWriter writer = writers.next();
		try {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			try (ImageOutputStream ios = ImageIO.createImageOutputStream(jpegBytes)) {
				writer.setOutput(ios);
				writer.write(null, new IIOImage(buffered, null, null), param);
			}
		} finally {
			writer.dispose();
		}

		byte[] raw = jpegBytes.toByteArray();
		byte[] exifBlob = buildExif(exif);
		try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
			out.write(raw, 0, 2); // SOI
			if (exifBlob != null) {
				byte[] prefix = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
				int segLen = prefix.length + exifBlob.length + 2; // +2: the length field itself
				out.write(0xFF);
				out.write(0xE1);
				out.write((segLen >>> 8) & 0xFF);
				out.write(segLen & 0xFF);
				out.write(prefix);
				out.write(exifBlob);
			}
			out.write(raw, 2, raw.length - 2); // whatever ImageIO wrote (APP0/JFIF, scan data, EOI)
		}
	}

	private static byte[] ihdr(int w, int h) {
		return ihdr(w, h, 8, 6);
	}

	private static byte[] ihdr(int w, int h, int bitDepth, int colorType) {
		byte[] d = new byte[13];
		putInt32(d, 0, w);
		putInt32(d, 4, h);
		d[8] = (byte) bitDepth;
		d[9] = (byte) colorType; // 6 = truecolor + alpha, 2 = truecolor (no alpha)
		d[10] = 0; // compression method
		d[11] = 0; // filter method
		d[12] = 0; // interlace method
		return d;
	}

	private static byte[] deflate(byte[] raw) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 2 + 64);
		Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
		try (DeflaterOutputStream dos = new DeflaterOutputStream(bos, def, 64 * 1024)) {
			dos.write(raw);
		} finally {
			def.end();
		}
		return bos.toByteArray();
	}

	private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		byte[] lenBytes = new byte[4];
		putInt32(lenBytes, 0, data.length);
		out.write(lenBytes);
		out.write(typeBytes);
		out.write(data);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		byte[] crcBytes = new byte[4];
		putInt32(crcBytes, 0, (int) crc.getValue());
		out.write(crcBytes);
	}

	private static void putInt32(byte[] b, int off, int v) {
		b[off] = (byte) (v >>> 24);
		b[off + 1] = (byte) (v >>> 16);
		b[off + 2] = (byte) (v >>> 8);
		b[off + 3] = (byte) v;
	}

	// ---- EXIF: a minimal hand-built TIFF structure (IFD0 -> Exif sub-IFD), embedded
	// verbatim as the PNG "eXIf" chunk (no APP1 wrapper needed outside a JPEG). ----

	private static final DateTimeFormatter EXIF_DATE =
			DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private static byte[] buildExif(Exif exif) {
		try {
			String date = EXIF_DATE.format(Instant.ofEpochMilli(exif.timestampMillis()));
			byte[] dateBytes = asciiZ(date);
			byte[] makeBytes = asciiZ("Realistic Digital Camera");

			long[] shutterRational = toRational(exif.shutterSeconds());
			long[] apertureRational = toRational(exif.aperture());
			long[] evRational = toSignedRational(exif.exposureBiasEv());

			// IFD0 carries Make/Model/DateTime plus a pointer to the Exif sub-IFD, which
			// is where the actual exposure values live — the structure real cameras use,
			// and the one Lightroom's Photo Merge -> HDR reads exposure data from.
			IfdBuilder ifd0 = new IfdBuilder();
			ifd0.addAscii(0x010F, makeBytes); // Make
			ifd0.addAscii(0x0110, makeBytes); // Model
			ifd0.addAscii(0x0132, dateBytes); // DateTime
			int exifPointerIndex = ifd0.addLongPlaceholder(0x8769); // ExifIFDPointer

			IfdBuilder exifIfd = new IfdBuilder();
			exifIfd.addRational(0x829A, shutterRational);               // ExposureTime
			exifIfd.addRational(0x829D, apertureRational);              // FNumber
			exifIfd.addShort(0x8827, exif.iso());                        // ISOSpeedRatings
			exifIfd.addUndefined(0x9000, new byte[] {'0', '2', '3', '0'}); // ExifVersion 2.30
			exifIfd.addAscii(0x9003, dateBytes);                         // DateTimeOriginal
			exifIfd.addSRational(0x9204, evRational);                    // ExposureBiasValue
			exifIfd.addShort(0xA001, 1);                                 // ColorSpace: sRGB

			int headerSize = 8;
			byte[] ifd0Bytes = ifd0.build(headerSize);
			int exifIfdOffset = headerSize + ifd0Bytes.length;
			byte[] exifIfdBytes = exifIfd.build(exifIfdOffset);
			putInt32LE(ifd0Bytes, IfdBuilder.valueFieldOffset(exifPointerIndex), exifIfdOffset);

			ByteArrayOutputStream out = new ByteArrayOutputStream(headerSize + ifd0Bytes.length + exifIfdBytes.length);
			out.write(new byte[] {'I', 'I', 0x2A, 0x00}); // little-endian TIFF, magic 42
			out.write(new byte[] {8, 0, 0, 0});           // offset to IFD0
			out.write(ifd0Bytes);
			out.write(exifIfdBytes);
			return out.toByteArray();
		} catch (Exception e) {
			PhotoMode.LOGGER.warn("[Photo Mode] failed to build EXIF block: {}", e.toString());
			return null;
		}
	}

	private static byte[] asciiZ(String s) {
		byte[] raw = s.getBytes(StandardCharsets.US_ASCII);
		byte[] out = new byte[raw.length + 1]; // trailing NUL, array default-initialised to 0
		System.arraycopy(raw, 0, out, 0, raw.length);
		return out;
	}

	/** Approximate a positive value as a TIFF RATIONAL (two uint32). Recognizes the
	 *  common "1/N" shutter-speed shape exactly; otherwise scales by 1000 and reduces. */
	private static long[] toRational(double value) {
		if (value > 0 && value < 1.0) {
			double inv = 1.0 / value;
			long n = Math.round(inv);
			if (Math.abs(inv - n) < 0.01) {
				return new long[] {1, n};
			}
		}
		long num = Math.max(0, Math.round(value * 1000.0));
		return reduce(num, 1000);
	}

	private static long[] toSignedRational(double value) {
		long num = Math.round(value * 1000.0);
		long den = 1000;
		long g = gcd(Math.abs(num), den);
		return g <= 1 ? new long[] {num, den} : new long[] {num / g, den / g};
	}

	private static long[] reduce(long num, long den) {
		long g = gcd(num, den);
		return g <= 1 ? new long[] {num, den} : new long[] {num / g, den / g};
	}

	private static long gcd(long a, long b) {
		a = Math.abs(a);
		b = Math.abs(b);
		while (b != 0) {
			long t = b;
			b = a % b;
			a = t;
		}
		return a == 0 ? 1 : a;
	}

	private static void putInt32LE(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >>> 8);
		b[off + 2] = (byte) (v >>> 16);
		b[off + 3] = (byte) (v >>> 24);
	}

	/** Builds one little-endian TIFF IFD: a fixed-size directory (tag/type/count/value
	 *  entries, in the ascending-tag order they're added, followed by a zero "no next
	 *  IFD" offset) plus, trailing it, the raw bytes for any entry whose value didn't
	 *  fit in the 4-byte inline slot (anything longer, e.g. ASCII strings and all the
	 *  8-byte RATIONAL values here). */
	private static final class IfdBuilder {
		private record Entry(int tag, int type, int count, byte[] data) {
		}

		private final List<Entry> entries = new ArrayList<>();

		void addAscii(int tag, byte[] zData) {
			entries.add(new Entry(tag, 2, zData.length, zData));
		}

		void addShort(int tag, int value) {
			entries.add(new Entry(tag, 3, 1, new byte[] {(byte) value, (byte) (value >>> 8)}));
		}

		void addRational(int tag, long[] numDen) {
			entries.add(new Entry(tag, 5, 1, rationalBytes(numDen)));
		}

		void addSRational(int tag, long[] numDen) {
			entries.add(new Entry(tag, 10, 1, rationalBytes(numDen)));
		}

		void addUndefined(int tag, byte[] data) {
			entries.add(new Entry(tag, 7, data.length, data));
		}

		/** A LONG entry whose real value (e.g. a pointer to another IFD) is only known
		 *  once this IFD's own size is settled. Returns this entry's index, to be passed
		 *  to {@link #valueFieldOffset} once the built bytes exist to patch. */
		int addLongPlaceholder(int tag) {
			entries.add(new Entry(tag, 4, 1, new byte[4]));
			return entries.size() - 1;
		}

		/** Byte offset, within this IFD's own {@link #build} output, of entry
		 *  {@code index}'s 4-byte inline value/offset field. Depends only on entry count
		 *  and position, not on any external data placement. */
		static int valueFieldOffset(int index) {
			return 2 + index * 12 + 8;
		}

		byte[] build(int baseOffset) {
			int fixedSize = 2 + entries.size() * 12 + 4;
			ByteArrayOutputStream external = new ByteArrayOutputStream();
			int[] externalOffsets = new int[entries.size()];
			int running = baseOffset + fixedSize;
			for (int i = 0; i < entries.size(); i++) {
				Entry e = entries.get(i);
				if (e.data().length > 4) {
					externalOffsets[i] = running;
					external.writeBytes(e.data());
					if ((e.data().length & 1) != 0) {
						external.write(0); // pad to an even offset for the next entry
						running++;
					}
					running += e.data().length;
				}
			}

			ByteArrayOutputStream out = new ByteArrayOutputStream(fixedSize + external.size());
			out.write(entries.size() & 0xFF);
			out.write((entries.size() >>> 8) & 0xFF);
			for (int i = 0; i < entries.size(); i++) {
				Entry e = entries.get(i);
				out.write(e.tag() & 0xFF);
				out.write((e.tag() >>> 8) & 0xFF);
				out.write(e.type() & 0xFF);
				out.write((e.type() >>> 8) & 0xFF);
				writeInt32LE(out, e.count());
				if (e.data().length <= 4) {
					out.writeBytes(e.data());
					for (int pad = e.data().length; pad < 4; pad++) {
						out.write(0);
					}
				} else {
					writeInt32LE(out, externalOffsets[i]);
				}
			}
			writeInt32LE(out, 0); // no next IFD
			out.writeBytes(external.toByteArray());
			return out.toByteArray();
		}

		private static byte[] rationalBytes(long[] numDen) {
			byte[] b = new byte[8];
			writeInt32LEInto(b, 0, (int) numDen[0]);
			writeInt32LEInto(b, 4, (int) numDen[1]);
			return b;
		}

		private static void writeInt32LE(ByteArrayOutputStream out, int v) {
			out.write(v & 0xFF);
			out.write((v >>> 8) & 0xFF);
			out.write((v >>> 16) & 0xFF);
			out.write((v >>> 24) & 0xFF);
		}

		private static void writeInt32LEInto(byte[] b, int off, int v) {
			b[off] = (byte) v;
			b[off + 1] = (byte) (v >>> 8);
			b[off + 2] = (byte) (v >>> 16);
			b[off + 3] = (byte) (v >>> 24);
		}
	}
}
